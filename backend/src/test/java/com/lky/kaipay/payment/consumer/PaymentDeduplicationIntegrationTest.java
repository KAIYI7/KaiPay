package com.lky.kaipay.payment.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lky.kaipay.AbstractPostgresIntegrationTest;
import com.lky.kaipay.common.event.EventEnvelope;
import com.lky.kaipay.consumer.domain.ConsumedEvent;
import com.lky.kaipay.consumer.repository.ConsumedEventRepository;
import com.lky.kaipay.consumer.service.ConsumerDeduplicationService;
import com.lky.kaipay.customer.domain.Customer;
import com.lky.kaipay.customer.repository.CustomerRepository;
import com.lky.kaipay.merchant.domain.Merchant;
import com.lky.kaipay.merchant.domain.MerchantStatus;
import com.lky.kaipay.merchant.repository.MerchantRepository;
import com.lky.kaipay.payment.domain.Payment;
import com.lky.kaipay.payment.domain.PaymentStatus;
import com.lky.kaipay.payment.domain.event.PaymentInitiatedEvent;
import com.lky.kaipay.payment.repository.PaymentRepository;
import com.lky.kaipay.payment.service.acquirer.MockBankAcquirerClient;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.springframework.kafka.support.Acknowledgment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@DisplayName("Payment Consumer Deduplication & Crash-Window Integration Tests")
class PaymentDeduplicationIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String CONSUMER_GROUP = "kaipay-payment-processor-group";
    private static final String TOPIC = "kaipay.payment.requests";

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private MerchantRepository merchantRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private MockBankAcquirerClient mockBankAcquirerClient;

    @Autowired
    private ConsumedEventRepository consumedEventRepository;

    @Autowired
    private PaymentProcessingConsumer paymentProcessingConsumer;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ConsumerDeduplicationService consumerDeduplicationService;

    private Merchant testMerchant;
    private Customer testCustomer;

    @BeforeEach
    void setUp() {
        consumedEventRepository.deleteAll();
        paymentRepository.deleteAll();
        customerRepository.deleteAll();
        merchantRepository.deleteAll();

        testMerchant = merchantRepository.save(
                Merchant.builder()
                        .name("Deduplication Test Merchant")
                        .apiKeyHash("test_hash_" + UUID.randomUUID())
                        .status(MerchantStatus.ACTIVE)
                        .build()
        );

        testCustomer = customerRepository.save(
                Customer.builder()
                        .merchant(testMerchant)
                        .email("dedup-test@example.com")
                        .fullName("Dedup Test User")
                        .build()
        );
    }

    @Test
    @DisplayName("A success marker on CREATED is inconsistent and quarantined before the gateway")
    void testInconsistentConsumedMarker_DoesNotAuthorizeOrAcknowledge() throws Exception {
        // 1. Setup test payment (status = CREATED)
        Payment payment = paymentRepository.save(
                Payment.builder()
                        .merchant(testMerchant)
                        .customer(testCustomer)
                        .amountCents(5000L)
                        .currency("MYR")
                        .status(PaymentStatus.CREATED)
                        .idempotencyKey("pre-check-key-" + UUID.randomUUID())
                        .build()
        );
        UUID paymentId = payment.getId();
        UUID eventId = UUID.randomUUID();

        // 2. Manually record consumedEvent with eventId and consumerGroup = "kaipay-payment-processor-group"
        consumerDeduplicationService.recordConsumed(
                eventId,
                CONSUMER_GROUP,
                paymentId,
                "PaymentInitiatedEvent",
                "AUTHORIZED"
        );

        // 3. Build ConsumerRecord with that eventId
        PaymentInitiatedEvent initiatedEvent = PaymentInitiatedEvent.fromPayment(payment);
        EventEnvelope<PaymentInitiatedEvent> envelope = EventEnvelope.<PaymentInitiatedEvent>builder()
                .eventId(eventId)
                .eventType("PaymentInitiatedEvent")
                .aggregateType("PAYMENT")
                .aggregateId(paymentId.toString())
                .merchantId(testMerchant.getId())
                .payload(initiatedEvent)
                .build();

        String payload = objectMapper.writeValueAsString(envelope);
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                TOPIC,
                0,
                0L,
                paymentId.toString(),
                payload
        );

        // A marker cannot conceal an incompatible payment lifecycle.
        Acknowledgment acknowledgment = mock(Acknowledgment.class);
        assertThatThrownBy(() -> paymentProcessingConsumer.processPaymentRequest(record, acknowledgment))
                .isInstanceOf(RuntimeException.class)
                .hasRootCauseMessage("Conflicting authorization marker");
        org.mockito.Mockito.verifyNoInteractions(acknowledgment);

        // 5. Assert mockBankAcquirerClient.getEvaluationCount(paymentId) == 0
        assertThat(mockBankAcquirerClient.getEvaluationCount(paymentId)).isEqualTo(0);

        // 6. Assert Payment in PostgreSQL remains in CREATED status
        Payment reloadedPayment = paymentRepository.findById(paymentId).orElseThrow();
        assertThat(reloadedPayment.getStatus()).isEqualTo(PaymentStatus.CREATED);
    }

    @Test
    @DisplayName("Consumer crash window: offset recovery and duplicate suppression after Tx2 commit")
    void testConsumerCrashWindow_OffsetRecoveryAndDuplicateSuppression() throws Exception {
        // 1. Setup test payment (status = CREATED)
        Payment payment = paymentRepository.save(
                Payment.builder()
                        .merchant(testMerchant)
                        .customer(testCustomer)
                        .amountCents(5000L)
                        .currency("MYR")
                        .status(PaymentStatus.CREATED)
                        .idempotencyKey("crash-window-key-" + UUID.randomUUID())
                        .build()
        );
        UUID paymentId = payment.getId();
        UUID eventId = UUID.randomUUID();

        PaymentInitiatedEvent initiatedEvent = PaymentInitiatedEvent.fromPayment(payment);
        EventEnvelope<PaymentInitiatedEvent> envelope = EventEnvelope.<PaymentInitiatedEvent>builder()
                .eventId(eventId)
                .eventType("PaymentInitiatedEvent")
                .aggregateType("PAYMENT")
                .aggregateId(paymentId.toString())
                .merchantId(testMerchant.getId())
                .payload(initiatedEvent)
                .build();

        String payload = objectMapper.writeValueAsString(envelope);
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                TOPIC,
                0,
                0L,
                paymentId.toString(),
                payload
        );

        // 2. Run normal complete processing of eventId
        paymentProcessingConsumer.processPaymentRequest(record, null);

        // Assert Payment transitions to AUTHORIZED and consumed_events contains record
        Payment authorizedPayment = paymentRepository.findById(paymentId).orElseThrow();
        assertThat(authorizedPayment.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(authorizedPayment.getGatewayReference()).isNotNull().startsWith("AUTH-");
        assertThat(consumedEventRepository.existsByIdEventIdAndIdConsumerGroup(eventId, CONSUMER_GROUP)).isTrue();
        assertThat(mockBankAcquirerClient.getEvaluationCount(paymentId)).isEqualTo(1);

        // 3. Simulate crash before offset ack & Kafka redelivery: Invoke processPaymentRequest again with identical record
        paymentProcessingConsumer.processPaymentRequest(record, null);

        // 4. Assert zero extra simulator evaluations and Payment remains AUTHORIZED
        assertThat(mockBankAcquirerClient.getEvaluationCount(paymentId)).isEqualTo(1);
        Payment recheckedPayment = paymentRepository.findById(paymentId).orElseThrow();
        assertThat(recheckedPayment.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(recheckedPayment.getGatewayReference()).isEqualTo(authorizedPayment.getGatewayReference());
    }

    @Test
    @DisplayName("Concurrent duplicate event delivery race resolution")
    void testConcurrentDuplicateEventDelivery_RaceResolution() throws Exception {
        // 1. Setup test payment
        Payment payment = paymentRepository.save(
                Payment.builder()
                        .merchant(testMerchant)
                        .customer(testCustomer)
                        .amountCents(5000L)
                        .currency("MYR")
                        .status(PaymentStatus.CREATED)
                        .idempotencyKey("concurrent-key-" + UUID.randomUUID())
                        .build()
        );
        UUID paymentId = payment.getId();
        UUID eventId = UUID.randomUUID();

        // 2. Build record with eventId
        PaymentInitiatedEvent initiatedEvent = PaymentInitiatedEvent.fromPayment(payment);
        EventEnvelope<PaymentInitiatedEvent> envelope = EventEnvelope.<PaymentInitiatedEvent>builder()
                .eventId(eventId)
                .eventType("PaymentInitiatedEvent")
                .aggregateType("PAYMENT")
                .aggregateId(paymentId.toString())
                .merchantId(testMerchant.getId())
                .payload(initiatedEvent)
                .build();

        String payload = objectMapper.writeValueAsString(envelope);
        // Each delivery has independent headers and acknowledgment; collect both outcomes.
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Acknowledgment ack1 = mock(Acknowledgment.class);
        Acknowledgment ack2 = mock(Acknowledgment.class);
        CompletableFuture<Void> future1 = CompletableFuture.runAsync(() ->
                consumeTogether(new ConsumerRecord<>(TOPIC, 0, 0L, paymentId.toString(), payload), ack1, ready, start));
        CompletableFuture<Void> future2 = CompletableFuture.runAsync(() ->
                consumeTogether(new ConsumerRecord<>(TOPIC, 0, 0L, paymentId.toString(), payload), ack2, ready, start));
        assertThat(ready.await(5, TimeUnit.SECONDS)).as("both competing deliveries ready").isTrue();
        start.countDown();
        Throwable failure1 = future1.handle((unused, failure) -> failure).get(10, TimeUnit.SECONDS);
        Throwable failure2 = future2.handle((unused, failure) -> failure).get(10, TimeUnit.SECONDS);
        assertThat(java.util.Arrays.asList(failure1, failure2)).as("every competing delivery outcome").containsOnlyNulls();
        verify(ack1).acknowledge();
        verify(ack2).acknowledge();

        // 5. Assert Payment in PostgreSQL is AUTHORIZED
        Payment finalPayment = paymentRepository.findById(paymentId).orElseThrow();
        assertThat(finalPayment.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(finalPayment.getGatewayReference()).isNotNull().startsWith("AUTH-");

        // Definitive replay never reevaluates or replaces the durable operation.
        assertThat(mockBankAcquirerClient.getEvaluationCount(paymentId)).isEqualTo(1);

        // 7. Assert consumedEventRepository.findAll() contains exactly 1 record for eventId
        List<ConsumedEvent> matchingEvents = consumedEventRepository.findAll().stream()
                .filter(ce -> ce.getId().getEventId().equals(eventId))
                .toList();
        assertThat(matchingEvents).hasSize(1);
        assertThat(matchingEvents.get(0).getId().getConsumerGroup()).isEqualTo(CONSUMER_GROUP);
        assertThat(matchingEvents.get(0).getPaymentId()).isEqualTo(paymentId);
    }
    private void consumeTogether(ConsumerRecord<String, String> record, Acknowledgment ack,
                                 CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        try {
            if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("start barrier timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("delivery interrupted", interrupted);
        }
        paymentProcessingConsumer.processPaymentRequest(record, ack);
    }
}
