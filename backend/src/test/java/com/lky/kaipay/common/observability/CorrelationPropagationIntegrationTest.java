package com.lky.kaipay.common.observability;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lky.kaipay.AbstractPostgresIntegrationTest;
import com.lky.kaipay.consumer.repository.ConsumedEventRepository;
import com.lky.kaipay.customer.domain.Customer;
import com.lky.kaipay.customer.repository.CustomerRepository;
import com.lky.kaipay.dlt.repository.DeadLetterEventRepository;
import com.lky.kaipay.merchant.domain.Merchant;
import com.lky.kaipay.merchant.repository.MerchantRepository;
import com.lky.kaipay.outbox.domain.PaymentEventOutbox;
import com.lky.kaipay.outbox.domain.PaymentEventOutboxStatus;
import com.lky.kaipay.outbox.repository.PaymentEventOutboxRepository;
import com.lky.kaipay.outbox.service.OutboxEventPublisher;
import com.lky.kaipay.payment.api.dto.CreatePaymentRequest;
import com.lky.kaipay.payment.domain.PaymentStatus;
import com.lky.kaipay.payment.repository.PaymentRepository;
import com.lky.kaipay.payment.service.acquirer.MockBankAcquirerClient;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Real ingress, JSONB, broker forwarding and listener evidence without replacing application beans. */
class CorrelationPropagationIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String HEADER = "X-Correlation-Id";
    private static final String TOPIC = "kaipay.payment.requests";
    private static final String GROUP = "kaipay-payment-processor-group";

    @Autowired private TestRestTemplate http;
    @Autowired private ObjectMapper mapper;
    @Autowired private MerchantRepository merchants;
    @Autowired private CustomerRepository customers;
    @Autowired private PaymentRepository payments;
    @Autowired private PaymentEventOutboxRepository outbox;
    @Autowired private OutboxEventPublisher publisher;
    @Autowired private KafkaTemplate<String, String> kafka;
    @Autowired private ConsumedEventRepository consumed;
    @Autowired private DeadLetterEventRepository deadLetters;
    @Autowired private MockBankAcquirerClient gateway;
    @Autowired private JdbcTemplate jdbc;

    private Merchant merchant;
    private Customer customer;
    private Logger logger;
    private SnapshotAppender appender;
    private Map<String, String> originalMdc;

    @BeforeEach
    void setUp() {
        originalMdc = MDC.getCopyOfContextMap();
        merchant = merchants.save(Merchant.builder().name("Correlation integration")
                .apiKeyHash(UUID.randomUUID().toString()).build());
        customer = customers.save(Customer.builder().merchant(merchant)
                .email(UUID.randomUUID() + "@example.com").fullName("Correlation customer").build());
        logger = (Logger) LoggerFactory.getLogger("com.lky.kaipay");
        appender = new SnapshotAppender();
        appender.setContext(logger.getLoggerContext());
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        if (logger != null && appender != null) {
            logger.detachAppender(appender);
            appender.stop();
        }
        if (originalMdc == null) MDC.clear();
        else MDC.setContextMap(originalMdc);
        // Delete only this test's tenant and effects; never clear another test's broker/database state.
        if (merchant != null) {
            UUID id = merchant.getId();
            jdbc.update("delete from ledger_entries where journal_id in (select id from journals where merchant_id = ?)", id);
            jdbc.update("delete from journals where merchant_id = ?", id);
            jdbc.update("delete from accounts where merchant_id = ?", id);
            jdbc.update("delete from consumed_events where payment_id in (select id from payments where merchant_id = ?)", id);
            jdbc.update("delete from dead_letter_events where payment_id in (select id from payments where merchant_id = ?)", id);
            jdbc.update("delete from payment_events_outbox where aggregate_id in (select id::text from payments where merchant_id = ?) "
                    + "or aggregate_id in (select id::text from refunds where merchant_id = ?)", id, id);
            jdbc.update("delete from refunds where merchant_id = ?", id);
            jdbc.update("delete from simulated_authorization_operations where merchant_id = ?", id);
            jdbc.update("delete from payments where merchant_id = ?", id);
            jdbc.update("delete from idempotency_records where merchant_id = ?", id);
            jdbc.update("delete from customers where merchant_id = ?", id);
            jdbc.update("delete from merchants where id = ?", id);
        }
    }

    @Test
    void httpOutboxKafkaAndRetryConsumerPreserveCorrelationForAllThreeWriters() throws Exception {
        String createTrace = "create-" + UUID.randomUUID();
        Creation creation = createPayment(888800L, createTrace);
        assertThat(creation.trace()).isEqualTo(createTrace);
        assertTraceLog("Created payment " + creation.paymentId(), createTrace);
        publishAndObserve(creation.row(), createTrace);
        awaitAuthorized(creation.paymentId(), creation.row(), createTrace, 3);
        assertGatewayAttempts(creation.paymentId(), createTrace, 3);

        String captureTrace = "capture-" + UUID.randomUUID();
        ResponseEntity<String> capture = http.exchange("/v1/payments/" + creation.paymentId() + "/capture",
                HttpMethod.POST, new HttpEntity<>(headers(captureTrace)), String.class);
        assertThat(capture.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(capture.getHeaders().getFirst(HEADER)).isEqualTo(captureTrace);
        PaymentEventOutbox captured = row(creation.paymentId().toString(), "PaymentCapturedEvent");
        assertPersistedTrace(captured, captureTrace);
        publishAndObserve(captured, captureTrace);
        assertLifecycleConsumed(captured, captureTrace);

        String refundTrace = "refund-" + UUID.randomUUID();
        ResponseEntity<String> refund = http.exchange("/v1/payments/" + creation.paymentId() + "/refunds",
                HttpMethod.POST, new HttpEntity<>(Map.of("amountCents", 100L, "reason", "Correlation test"), headers(refundTrace)), String.class);
        assertThat(refund.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(refund.getHeaders().getFirst(HEADER)).isEqualTo(refundTrace);
        String refundId = mapper.readTree(refund.getBody()).path("data").path("id").asText();
        PaymentEventOutbox refunded = row(refundId, "PaymentRefundedEvent");
        assertPersistedTrace(refunded, refundTrace);
        publishAndObserve(refunded, refundTrace);
        assertLifecycleConsumed(refunded, refundTrace);
        assertThat(payments.findById(creation.paymentId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
        assertThat(consumed.findByPaymentIdOrderByProcessedAtAsc(creation.paymentId())).hasSize(1);
    }

    @ParameterizedTest
    @MethodSource("invalidHttpIds")
    void missingOrInvalidHttpMetadataGetsAnEffectiveIdPersistedAndConsumed(String supplied) throws Exception {
        Creation creation = createPayment(10000L, supplied);
        assertThat(UUID.fromString(creation.trace()).toString()).isEqualTo(creation.trace());
        assertThat(creation.trace()).isNotEqualTo(supplied);
        assertTraceLog("Created payment " + creation.paymentId(), creation.trace());
        publishAndObserve(creation.row(), creation.trace());
        awaitAuthorized(creation.paymentId(), creation.row(), creation.trace(), 1);
    }

    static Stream<String> invalidHttpIds() {
        return Stream.of(null, "contains space", "x".repeat(65));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "invalid", "legacy"})
    void legacyOrInvalidOutboxMetadataIsStableAcrossPublicationAndConsumer(String kind) throws Exception {
        Creation creation = createPayment(10000L, "ingress-" + UUID.randomUUID());
        PaymentEventOutbox legacy = outbox.findById(creation.row().getId()).orElseThrow();
        legacy.setHeaders(switch (kind) {
            case "invalid" -> Map.of("merchantId", merchant.getId().toString(), "correlationId", "bad value");
            case "legacy" -> Map.of("merchantId", merchant.getId().toString(), "traceId", "legacy-safe-value");
            default -> Map.of("merchantId", merchant.getId().toString());
        });
        outbox.saveAndFlush(legacy);
        String fallback = kind.equals("legacy") ? "legacy-safe-value" : legacy.getId().toString();
        publishAndObserve(legacy, fallback);
        awaitAuthorized(creation.paymentId(), legacy, fallback, 1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void missingOrInvalidKafkaMetadataIsNormalizedBeforeRetryForwarding(boolean invalid) throws Exception {
        Creation creation = createPayment(888800L, "unused-outbox-" + UUID.randomUUID());
        ProducerRecord<String, String> record = new ProducerRecord<>(TOPIC, creation.paymentId().toString(), creation.row().getPayload());
        if (invalid) record.headers().add(HEADER, "bad\nvalue".getBytes(StandardCharsets.UTF_8));
        var metadata = kafka.send(record).get().getRecordMetadata();
        String effective = UUID.nameUUIDFromBytes(("kaipay:" + metadata.topic() + ":" + metadata.partition() + ":" + metadata.offset())
                .getBytes(StandardCharsets.UTF_8)).toString();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(payments.findById(creation.paymentId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
            assertThat(gateway.getEvaluationCount(creation.paymentId())).isEqualTo(3);
            assertThat(consumed.existsByIdEventIdAndIdConsumerGroup(eventId(creation.row()), GROUP)).isTrue();
            List<LogSnapshot> attempts = gatewayAttempts(creation.paymentId());
            assertThat(attempts).hasSize(3);
            assertThat(attempts).allSatisfy(log -> assertThat(log.mdc()).containsEntry("traceId", effective));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"AB12", "ABCD1234"})
    void exhaustedRetriesAndDltKeepFourAndEightCharacterIdsAsStrings(String trace) throws Exception {
        Creation creation = createPayment(777700L, trace);
        publishAndObserve(creation.row(), trace);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(gateway.getEvaluationCount(creation.paymentId())).isEqualTo(3);
            assertThat(payments.findById(creation.paymentId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.PROCESSING);
            assertThat(consumed.findByPaymentIdOrderByProcessedAtAsc(creation.paymentId())).isEmpty();
            var events = deadLetters.findByPaymentIdOrderByCreatedAtDesc(creation.paymentId());
            assertThat(events).hasSize(1);
            assertThat(events.getFirst().getHeaders().get(HEADER)).isInstanceOf(String.class).isEqualTo(trace);
            assertThat(events.getFirst().getExceptionClass()).contains("GatewayTimeoutException");
            var persisted = events.getFirst();
            assertTraceLog("topic=" + persisted.getOriginalTopic() + ", partition="
                    + persisted.getOriginalPartition() + ", offset=" + persisted.getOriginalOffset(),
                    "Handling Dead Letter", trace);
            assertTraceLog("Saved dead letter event record", trace);
        });
        assertGatewayAttempts(creation.paymentId(), trace, 3);
    }

    private Creation createPayment(long amount, String supplied) throws Exception {
        CreatePaymentRequest request = CreatePaymentRequest.builder().customerId(customer.getId())
                .amountCents(amount).currency("MYR").build();
        ResponseEntity<String> response = http.exchange("/v1/payments", HttpMethod.POST,
                new HttpEntity<>(request, headers(supplied)), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String effective = response.getHeaders().getFirst(HEADER);
        assertThat(effective).isNotBlank();
        UUID paymentId = UUID.fromString(mapper.readTree(response.getBody()).path("data").path("id").asText());
        PaymentEventOutbox persisted = row(paymentId.toString(), "PaymentInitiatedEvent");
        assertPersistedTrace(persisted, effective);
        return new Creation(paymentId, persisted, effective);
    }

    private HttpHeaders headers(String trace) {
        HttpHeaders headers = security().merchantHeaders(merchant.getId());
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Merchant-Id", merchant.getId().toString());
        headers.set("Idempotency-Key", "correlation-" + UUID.randomUUID());
        if (trace != null) headers.set(HEADER, trace);
        return headers;
    }

    private PaymentEventOutbox row(String aggregateId, String eventType) {
        List<PaymentEventOutbox> matches = outbox.findAll().stream()
                .filter(event -> aggregateId.equals(event.getAggregateId()) && eventType.equals(event.getEventType())).toList();
        assertThat(matches).hasSize(1);
        return matches.getFirst();
    }

    private void assertPersistedTrace(PaymentEventOutbox row, String trace) {
        assertThat(row.getHeaders()).containsEntry("correlationId", trace)
                .containsEntry("merchantId", merchant.getId().toString());
    }

    private void publishAndObserve(PaymentEventOutbox row, String trace) throws Exception {
        String previous = MDC.get("traceId");
        MDC.put("traceId", "parent-worker-context");
        try {
            await().pollInSameThread().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                if (outbox.findById(row.getId()).orElseThrow().getStatus() == PaymentEventOutboxStatus.PENDING) {
                    publisher.publishPendingEvents();
                }
                assertThat(outbox.findById(row.getId()).orElseThrow().getStatus()).isEqualTo(PaymentEventOutboxStatus.PUBLISHED);
            });
            assertThat(MDC.get("traceId")).isEqualTo("parent-worker-context");
        } finally {
            if (previous == null) MDC.remove("traceId");
            else MDC.put("traceId", previous);
        }
        assertTraceLog("Published outbox event [id=" + row.getId(), trace);
        ConsumerRecord<String, String> brokerRecord = brokerRecord(row);
        assertThat(brokerRecord.headers().lastHeader(HEADER)).isNotNull();
        assertThat(new String(brokerRecord.headers().lastHeader(HEADER).value(), StandardCharsets.UTF_8)).isEqualTo(trace);
        assertThat(mapper.readTree(brokerRecord.value())).isEqualTo(mapper.readTree(row.getPayload()));
        assertThat(outbox.findById(row.getId()).orElseThrow().getHeaders()).containsEntry("correlationId", trace);
    }

    private ConsumerRecord<String, String> brokerRecord(PaymentEventOutbox row) throws Exception {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA_CONTAINER.getBootstrapServers());
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        try (KafkaConsumer<String, String> observer = new KafkaConsumer<>(properties)) {
            List<TopicPartition> partitions = observer.partitionsFor(TOPIC).stream()
                    .map(partition -> new TopicPartition(TOPIC, partition.partition())).toList();
            observer.assign(partitions);
            observer.seekToBeginning(partitions);
            UUID id = eventId(row);
            AtomicReference<ConsumerRecord<String, String>> found = new AtomicReference<>();
            await().pollInSameThread().atMost(Duration.ofSeconds(10)).until(() -> {
                for (ConsumerRecord<String, String> record : observer.poll(Duration.ofMillis(100))) {
                    if (row.getAggregateId().equals(record.key())
                            && id.toString().equals(mapper.readTree(record.value()).path("eventId").asText())) {
                        found.set(record);
                        return true;
                    }
                }
                return false;
            });
            return found.get();
        }
    }

    private void awaitAuthorized(UUID paymentId, PaymentEventOutbox row, String trace, int attempts) {
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(payments.findById(paymentId).orElseThrow().getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
            assertThat(gateway.getEvaluationCount(paymentId)).isEqualTo(attempts);
            assertThat(consumed.existsByIdEventIdAndIdConsumerGroup(eventId(row), GROUP)).isTrue();
            assertTraceLog("Completed authorization with deduplication for payment " + paymentId, trace);
        });
    }

    private UUID eventId(PaymentEventOutbox row) {
        try {
            return UUID.fromString(mapper.readTree(row.getPayload()).path("eventId").asText());
        } catch (Exception exception) {
            throw new AssertionError("Invalid fixture event envelope", exception);
        }
    }

    private void assertLifecycleConsumed(PaymentEventOutbox row, String trace) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            UUID paymentId = UUID.fromString(mapper.readTree(row.getPayload()).path("payload").path("paymentId").asText());
            assertTraceLog("paymentId=" + paymentId + ", eventType=" + row.getEventType(),
                    "Skipping lifecycle notification", trace);
        });
    }

    private List<LogSnapshot> gatewayAttempts(UUID paymentId) {
        return appender.logs.stream().filter(log -> log.logger().endsWith("MockBankAcquirerClient")
                && log.message().contains(paymentId.toString()) && log.message().contains("attempt")).toList();
    }

    private void assertGatewayAttempts(UUID paymentId, String trace, int count) {
        List<LogSnapshot> attempts = gatewayAttempts(paymentId);
        assertThat(attempts).hasSize(count).allSatisfy(log -> {
            assertThat(log.mdc()).containsEntry("traceId", trace);
            assertThat(log.thread()).isNotEqualTo(Thread.currentThread().getName());
        });
    }

    private void assertTraceLog(String contains, String trace) {
        assertTraceLog(contains, "", trace);
    }

    private void assertTraceLog(String contains, String alsoContains, String trace) {
        List<LogSnapshot> logs = appender.logs.stream().filter(log -> log.message().contains(contains)
                && log.message().contains(alsoContains)).toList();
        assertThat(logs).as("observed processing log for %s", contains).isNotEmpty()
                .allSatisfy(log -> assertThat(log.mdc()).containsEntry("traceId", trace));
    }

    private record Creation(UUID paymentId, PaymentEventOutbox row, String trace) {}
    private record LogSnapshot(String logger, String message, String thread, Map<String, String> mdc) {}

    private static final class SnapshotAppender extends AppenderBase<ILoggingEvent> {
        private final ConcurrentLinkedQueue<LogSnapshot> logs = new ConcurrentLinkedQueue<>();

        @Override
        protected void append(ILoggingEvent event) {
            // Read immediately on the emitting thread; retaining ILoggingEvent would defer MDC lookup.
            logs.add(new LogSnapshot(event.getLoggerName(), event.getFormattedMessage(), event.getThreadName(),
                    Map.copyOf(event.getMDCPropertyMap())));
        }
    }
}
