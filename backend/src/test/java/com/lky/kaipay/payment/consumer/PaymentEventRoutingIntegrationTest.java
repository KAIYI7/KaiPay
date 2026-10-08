package com.lky.kaipay.payment.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lky.kaipay.AbstractPostgresIntegrationTest;
import com.lky.kaipay.common.event.EventEnvelope;
import com.lky.kaipay.consumer.repository.ConsumedEventRepository;
import com.lky.kaipay.customer.domain.Customer;
import com.lky.kaipay.customer.repository.CustomerRepository;
import com.lky.kaipay.dlt.repository.DeadLetterEventRepository;
import com.lky.kaipay.ledger.domain.Journal;
import com.lky.kaipay.ledger.repository.AccountRepository;
import com.lky.kaipay.ledger.repository.JournalRepository;
import com.lky.kaipay.ledger.repository.LedgerEntryRepository;
import com.lky.kaipay.merchant.domain.Merchant;
import com.lky.kaipay.merchant.repository.MerchantRepository;
import com.lky.kaipay.outbox.domain.PaymentEventOutbox;
import com.lky.kaipay.outbox.domain.PaymentEventOutboxStatus;
import com.lky.kaipay.outbox.repository.PaymentEventOutboxRepository;
import com.lky.kaipay.outbox.service.OutboxEventPublisher;
import com.lky.kaipay.payment.api.dto.CreatePaymentRequest;
import com.lky.kaipay.payment.domain.Payment;
import com.lky.kaipay.payment.domain.PaymentStatus;
import com.lky.kaipay.payment.repository.IdempotencyRecordRepository;
import com.lky.kaipay.payment.repository.PaymentMethodRepository;
import com.lky.kaipay.payment.repository.PaymentRepository;
import com.lky.kaipay.payment.service.PaymentService;
import com.lky.kaipay.payment.service.acquirer.MockBankAcquirerClient;
import com.lky.kaipay.refund.api.dto.CreateRefundRequest;
import com.lky.kaipay.refund.domain.RefundStatus;
import com.lky.kaipay.refund.repository.RefundRepository;
import com.lky.kaipay.refund.service.RefundService;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.common.TopicPartition;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Payment Event Routing Kafka Integration Tests")
class PaymentEventRoutingIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String TOPIC = "kaipay.payment.requests";
    private static final String GROUP = "kaipay-payment-processor-group";

    @Autowired private PaymentService paymentService;
    @Autowired private RefundService refundService;
    @Autowired private OutboxEventPublisher publisher;
    @Autowired private KafkaTemplate<String, String> kafkaTemplate;
    @Autowired private MockBankAcquirerClient gateway;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PaymentRepository payments;
    @Autowired private MerchantRepository merchants;
    @Autowired private CustomerRepository customers;
    @Autowired private PaymentEventOutboxRepository outbox;
    @Autowired private ConsumedEventRepository consumedEvents;
    @Autowired private DeadLetterEventRepository deadLetters;
    @Autowired private RefundRepository refunds;
    @Autowired private JournalRepository journals;
    @Autowired private LedgerEntryRepository entries;
    @Autowired private AccountRepository accounts;
    @Autowired private IdempotencyRecordRepository idempotencyRecords;
    @Autowired private PaymentMethodRepository paymentMethods;

    private Merchant merchant;
    private Customer customer;

    @BeforeEach
    void setUp() {
        cleanup();
        gateway.clearLedger();
        merchant = merchants.save(Merchant.builder().name("Routing Test Merchant")
                .apiKeyHash("routing-" + UUID.randomUUID()).build());
        customer = customers.save(Customer.builder().merchant(merchant)
                .email(UUID.randomUUID() + "@routing.example.com").fullName("Routing Test Customer").build());
    }

    @AfterEach
    void cleanup() {
        // These repositories use the isolated PostgreSQL Testcontainer from the integration-test base.
        deadLetters.deleteAll();
        consumedEvents.deleteAll();
        refunds.deleteAll();
        entries.deleteAll();
        journals.deleteAll();
        accounts.deleteAll();
        outbox.deleteAll();
        idempotencyRecords.deleteAll();
        payments.deleteAll();
        paymentMethods.deleteAll();
        customers.deleteAll();
        merchants.deleteAll();
    }

    @Test
    @DisplayName("Service-produced capture/refund notifications commit offsets without authorizing or reposting accounting")
    void outboxLifecycleNotificationsAreAcknowledgedWithoutBusinessSideEffects() throws Exception {
        try (Admin admin = admin()) {
            UUID paymentId = paymentService.createPayment(merchant.getId(), "create-" + UUID.randomUUID(),
                    CreatePaymentRequest.builder().customerId(customer.getId())
                            .amountCents(10000L).currency("USD").build()).getId();
            PaymentEventOutbox initiated = event("PaymentInitiatedEvent");
            publishAndAwaitCommit(admin);
            assertThat(payments.findById(paymentId).orElseThrow().getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
            assertThat(consumedEvents.existsByIdEventIdAndIdConsumerGroup(eventId(initiated), GROUP)).isTrue();
            assertThat(gateway.getExecutionCount(paymentId)).isEqualTo(1);
            String authorizationCode = payments.findById(paymentId).orElseThrow().getGatewayReference();

            paymentService.capturePayment(merchant.getId(), paymentId, "capture-" + UUID.randomUUID());
            PaymentEventOutbox captured = event("PaymentCapturedEvent");
            List<UUID> captureJournals = journalIds();
            assertThat(captureJournals).hasSize(1); // Posted before publishing the notification.
            publishAndAwaitCommit(admin);
            assertThat(payments.findById(paymentId).orElseThrow().getStatus()).isEqualTo(PaymentStatus.CAPTURED);
            assertThat(journalIds()).containsExactlyInAnyOrderElementsOf(captureJournals);
            assertSkipped(paymentId, authorizationCode, captured);

            UUID refundId = refundService.createRefund(merchant.getId(), paymentId, "refund-" + UUID.randomUUID(),
                    CreateRefundRequest.builder().amountCents(4000L).reason("Routing regression").build()).getId();
            PaymentEventOutbox refunded = event("PaymentRefundedEvent");
            assertThat(objectMapper.readTree(refunded.getPayload()).get("payload").has("amountCents")).isFalse();
            List<UUID> refundJournals = journalIds();
            long entryCount = entries.count();
            assertThat(refundJournals).hasSize(2); // Reversing journal is also posted synchronously.
            publishAndAwaitCommit(admin);
            assertSkipped(paymentId, authorizationCode, refunded);
            assertThat(payments.findById(paymentId).orElseThrow().getStatus()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
            assertThat(refunds.findById(refundId).orElseThrow().getStatus()).isEqualTo(RefundStatus.COMPLETED);
            assertThat(journalIds()).containsExactlyInAnyOrderElementsOf(refundJournals);
            assertThat(entries.count()).isEqualTo(entryCount);

            // Redeliver the exact notifications, keeping their event IDs and original aggregate keys.
            kafkaTemplate.send(TOPIC, captured.getAggregateId(), captured.getPayload()).get(5, TimeUnit.SECONDS);
            kafkaTemplate.send(TOPIC, refunded.getAggregateId(), refunded.getPayload()).get(5, TimeUnit.SECONDS);
            awaitMainOffsetsCommitted(admin);
            assertSkipped(paymentId, authorizationCode, captured);
            assertSkipped(paymentId, authorizationCode, refunded);
            assertThat(journalIds()).containsExactlyInAnyOrderElementsOf(refundJournals);
            assertThat(entries.count()).isEqualTo(entryCount);
            assertThat(payments.findById(paymentId).orElseThrow().getStatus()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
            assertThat(refunds.findById(refundId).orElseThrow().getAmountCents()).isEqualTo(4000L);
            assertThat(consumedEvents.count()).isEqualTo(1); // Only initiation belongs to this group.
            assertThat(deadLetters.findAll()).isEmpty();
        }
    }

    @Test
    @DisplayName("Unknown event types go directly to DLT without authorization or retry-topic publication")
    void unknownEventTypeRoutesDirectlyToDlt() throws Exception {
        Payment payment = payments.save(Payment.builder().merchant(merchant).customer(customer)
                .amountCents(5000L).currency("USD").status(PaymentStatus.CREATED)
                .idempotencyKey("unknown-" + UUID.randomUUID()).build());
        EventEnvelope<Map<String, Object>> envelope = EventEnvelope.of("UnsupportedPaymentEvent", "PAYMENT",
                payment.getId().toString(), merchant.getId(),
                Map.of("paymentId", payment.getId(), "amountCents", 5000L, "currency", "USD"));
        String payload = objectMapper.writeValueAsString(envelope);

        try (Admin admin = admin()) {
            var retryTopics = admin.listTopics().names().get(5, TimeUnit.SECONDS).stream()
                    .filter(name -> name.startsWith(TOPIC + "-retry")).toList();
            assertThat(retryTopics).isNotEmpty();
            Map<TopicPartition, Long> retryOffsets = endOffsets(admin, retryTopics);

            kafkaTemplate.send(TOPIC, payment.getId().toString(), payload).get(5, TimeUnit.SECONDS);
            Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                var failures = deadLetters.findByPaymentIdOrderByCreatedAtDesc(payment.getId());
                assertThat(failures).hasSize(1);
                assertThat(failures.get(0).getEventId()).isEqualTo(envelope.getEventId());
                // PostgreSQL JSONB normalizes whitespace and property order; compare the full JSON value.
                assertThat(objectMapper.readTree(failures.get(0).getPayload()))
                        .isEqualTo(objectMapper.readTree(payload));
                // The exception-message header may describe the listener wrapper; the trace retains its cause.
                assertThat(failures.get(0).getHeaders().get(KafkaHeaders.EXCEPTION_STACKTRACE).toString())
                        .contains("Unsupported payment event type: UnsupportedPaymentEvent");
            });
            awaitMainOffsetsCommitted(admin);

            assertThat(endOffsets(admin, retryTopics)).isEqualTo(retryOffsets);
            assertThat(gateway.getExecutionCount(payment.getId())).isZero();
            assertThat(consumedEvents.findAll()).isEmpty();
            Payment failed = payments.findById(payment.getId()).orElseThrow();
            assertThat(failed.getStatus()).isEqualTo(PaymentStatus.FAILED);
            assertThat(failed.getFailureCode()).isEqualTo("DLT_ROUTED");
        }
    }

    private Admin admin() {
        return Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA_CONTAINER.getBootstrapServers()));
    }

    private PaymentEventOutbox event(String eventType) {
        return outbox.findAll().stream().filter(record -> eventType.equals(record.getEventType()))
                .findFirst().orElseThrow();
    }

    private UUID eventId(PaymentEventOutbox record) throws Exception {
        return UUID.fromString(objectMapper.readTree(record.getPayload()).get("eventId").asText());
    }

    private List<UUID> journalIds() {
        return journals.findAll().stream().map(Journal::getId).toList();
    }

    private void assertSkipped(UUID paymentId, String authorizationCode, PaymentEventOutbox record) throws Exception {
        assertThat(gateway.getExecutionCount(paymentId)).isEqualTo(1);
        assertThat(payments.findById(paymentId).orElseThrow().getGatewayReference()).isEqualTo(authorizationCode);
        assertThat(consumedEvents.existsByIdEventIdAndIdConsumerGroup(eventId(record), GROUP)).isFalse();
    }

    private void publishAndAwaitCommit(Admin admin) throws Exception {
        assertThat(publisher.publishPendingEvents()).isEqualTo(1);
        assertThat(outbox.findAll()).allMatch(record -> record.getStatus() == PaymentEventOutboxStatus.PUBLISHED);
        awaitMainOffsetsCommitted(admin);
    }

    private void awaitMainOffsetsCommitted(Admin admin) throws Exception {
        // Wait for the authorization group's broker-committed offsets, not just a DB state or elapsed time.
        Map<TopicPartition, Long> expected = endOffsets(admin, List.of(TOPIC));
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            var committed = admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata()
                    .get(5, TimeUnit.SECONDS);
            expected.forEach((partition, offset) -> {
                if (offset > 0) {
                    assertThat(committed.get(partition)).isNotNull();
                    assertThat(committed.get(partition).offset()).isGreaterThanOrEqualTo(offset);
                }
            });
        });
    }

    private Map<TopicPartition, Long> endOffsets(Admin admin, List<String> topics) throws Exception {
        Map<TopicPartition, OffsetSpec> request = new HashMap<>();
        admin.describeTopics(topics).allTopicNames().get(5, TimeUnit.SECONDS).forEach((topic, description) ->
                description.partitions().forEach(partition ->
                        request.put(new TopicPartition(topic, partition.partition()), OffsetSpec.latest())));
        Map<TopicPartition, Long> offsets = new HashMap<>();
        admin.listOffsets(request).all().get(5, TimeUnit.SECONDS)
                .forEach((partition, info) -> offsets.put(partition, info.offset()));
        return offsets;
    }
}
