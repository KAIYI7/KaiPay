package com.lky.kaipay.payment.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lky.kaipay.AbstractPostgresIntegrationTest;
import com.lky.kaipay.common.event.EventEnvelope;
import com.lky.kaipay.consumer.repository.ConsumedEventRepository;
import com.lky.kaipay.customer.domain.Customer;
import com.lky.kaipay.customer.repository.CustomerRepository;
import com.lky.kaipay.dlt.domain.DeadLetterEvent;
import com.lky.kaipay.dlt.repository.DeadLetterEventRepository;
import com.lky.kaipay.merchant.domain.Merchant;
import com.lky.kaipay.merchant.domain.MerchantStatus;
import com.lky.kaipay.merchant.repository.MerchantRepository;
import com.lky.kaipay.payment.domain.Payment;
import com.lky.kaipay.payment.domain.PaymentStatus;
import com.lky.kaipay.payment.domain.event.PaymentInitiatedEvent;
import com.lky.kaipay.payment.repository.PaymentRepository;
import com.lky.kaipay.payment.service.acquirer.MockBankAcquirerClient;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PaymentProcessingConsumer Kafka Integration Tests")
class PaymentProcessingConsumerIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private MerchantRepository merchantRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private ConsumedEventRepository consumedEventRepository;

    @Autowired
    private DeadLetterEventRepository deadLetterEventRepository;

    @Autowired
    private MockBankAcquirerClient mockBankAcquirerClient;

    @Autowired
    private PaymentProcessingConsumer paymentProcessingConsumer;

    @Autowired
    private ObjectMapper objectMapper;

    private Merchant testMerchant;
    private Customer testCustomer;

    @BeforeEach
    void setUp() {
        mockBankAcquirerClient.clearLedger();
        deadLetterEventRepository.deleteAll();
        consumedEventRepository.deleteAll();
        paymentRepository.deleteAll();
        customerRepository.deleteAll();
        merchantRepository.deleteAll();

        testMerchant = merchantRepository.save(
                Merchant.builder()
                        .name("Acme Processing Corp")
                        .apiKeyHash("test_hash_" + UUID.randomUUID())
                        .status(MerchantStatus.ACTIVE)
                        .build()
        );

        testCustomer = customerRepository.save(
                Customer.builder()
                        .merchant(testMerchant)
                        .email("consumer-test@example.com")
                        .fullName("Consumer Test User")
                        .build()
        );
    }

    @Test
    @DisplayName("Payment request with amount < 999900L should transition to AUTHORIZED and record ConsumedEvent")
    void testProcessPayment_Success_TransitionsToAuthorized() throws Exception {
        Payment payment = paymentRepository.save(
                Payment.builder()
                        .merchant(testMerchant)
                        .customer(testCustomer)
                        .amountCents(5000L) // $50.00
                        .currency("USD")
                        .status(PaymentStatus.CREATED)
                        .idempotencyKey("key-" + UUID.randomUUID())
                        .build()
        );

        PaymentInitiatedEvent event = PaymentInitiatedEvent.fromPayment(payment);
        EventEnvelope<PaymentInitiatedEvent> envelope = EventEnvelope.of(
                "PaymentInitiatedEvent",
                "PAYMENT",
                payment.getId().toString(),
                testMerchant.getId(),
                event
        );

        String payload = objectMapper.writeValueAsString(envelope);
        kafkaTemplate.send("kaipay.payment.requests", payment.getId().toString(), payload).get();

        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    Payment updated = paymentRepository.findById(payment.getId()).orElseThrow();
                    assertThat(updated.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
                    assertThat(updated.getGatewayReference()).isNotNull().startsWith("AUTH-");
                    assertThat(updated.getFailureCode()).isNull();
                    assertThat(updated.getFailureMessage()).isNull();

                    boolean consumed = consumedEventRepository.existsByIdEventIdAndIdConsumerGroup(
                            envelope.getEventId(),
                            "kaipay-payment-processor-group"
                    );
                    assertThat(consumed).isTrue();
                });
    }

    @Test
    @DisplayName("Payment request with amount >= 999900L should transition to DECLINED and record ConsumedEvent")
    void testProcessPayment_Decline_TransitionsToDeclined() throws Exception {
        Payment payment = paymentRepository.save(
                Payment.builder()
                        .merchant(testMerchant)
                        .customer(testCustomer)
                        .amountCents(999900L) // $9999.00 -> triggers decline in MockBankAcquirerClient
                        .currency("USD")
                        .status(PaymentStatus.CREATED)
                        .idempotencyKey("key-" + UUID.randomUUID())
                        .build()
        );

        PaymentInitiatedEvent event = PaymentInitiatedEvent.fromPayment(payment);
        EventEnvelope<PaymentInitiatedEvent> envelope = EventEnvelope.of(
                "PaymentInitiatedEvent",
                "PAYMENT",
                payment.getId().toString(),
                testMerchant.getId(),
                event
        );

        String payload = objectMapper.writeValueAsString(envelope);
        kafkaTemplate.send("kaipay.payment.requests", payment.getId().toString(), payload).get();

        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    Payment updated = paymentRepository.findById(payment.getId()).orElseThrow();
                    assertThat(updated.getStatus()).isEqualTo(PaymentStatus.DECLINED);
                    assertThat(updated.getFailureCode()).isEqualTo("INSUFFICIENT_FUNDS");
                    assertThat(updated.getFailureMessage()).contains("insufficient funds");
                    assertThat(updated.getGatewayReference()).isNull();

                    boolean consumed = consumedEventRepository.existsByIdEventIdAndIdConsumerGroup(
                            envelope.getEventId(),
                            "kaipay-payment-processor-group"
                    );
                    assertThat(consumed).isTrue();
                });
    }

    @Test
    @DisplayName("Duplicate event replay should be deduplicated and maintain final AUTHORIZED status")
    void testProcessPayment_DuplicateEvent_IsIdempotent() throws Exception {
        Payment payment = paymentRepository.save(
                Payment.builder()
                        .merchant(testMerchant)
                        .customer(testCustomer)
                        .amountCents(7500L)
                        .currency("USD")
                        .status(PaymentStatus.CREATED)
                        .idempotencyKey("key-" + UUID.randomUUID())
                        .build()
        );

        PaymentInitiatedEvent event = PaymentInitiatedEvent.fromPayment(payment);
        EventEnvelope<PaymentInitiatedEvent> envelope = EventEnvelope.of(
                "PaymentInitiatedEvent",
                "PAYMENT",
                payment.getId().toString(),
                testMerchant.getId(),
                event
        );

        String payload = objectMapper.writeValueAsString(envelope);

        // Send first event
        kafkaTemplate.send("kaipay.payment.requests", payment.getId().toString(), payload).get();

        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    Payment updated = paymentRepository.findById(payment.getId()).orElseThrow();
                    assertThat(updated.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
                });

        String initialAuthCode = paymentRepository.findById(payment.getId()).orElseThrow().getGatewayReference();

        // Send duplicate event for the exact same payment and eventId
        kafkaTemplate.send("kaipay.payment.requests", payment.getId().toString(), payload).get();

        // Allow some time for duplicate processing
        Awaitility.await()
                .during(Duration.ofMillis(500))
                .atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> {
                    Payment updated = paymentRepository.findById(payment.getId()).orElseThrow();
                    assertThat(updated.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
                    assertThat(updated.getGatewayReference()).isEqualTo(initialAuthCode);
                });
    }

    @Test
    @DisplayName("DLT handler marks payment as FAILED and saves DeadLetterEvent record")
    void testHandleDltMessage_MarksPaymentFailedAndSavesDeadLetterEvent() throws Exception {
        Payment payment = paymentRepository.save(
                Payment.builder()
                        .merchant(testMerchant)
                        .customer(testCustomer)
                        .amountCents(5000L)
                        .currency("USD")
                        .status(PaymentStatus.PROCESSING)
                        .idempotencyKey("dlt-key-" + UUID.randomUUID())
                        .build()
        );

        UUID eventId = UUID.randomUUID();
        String jsonPayload = String.format("{\"eventId\":\"%s\",\"payload\":{\"paymentId\":\"%s\",\"amountCents\":5000}}",
                eventId, payment.getId());

        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "kaipay.payment.requests-dlt",
                0,
                15L,
                payment.getId().toString(),
                jsonPayload
        );

        paymentProcessingConsumer.handleDltMessage(
                record,
                "org.springframework.kafka.listener.ListenerExecutionFailedException",
                "com.lky.kaipay.common.exception.GatewayTimeoutException",
                "Gateway timeout after max retry attempts",
                null
        );

        Payment updatedPayment = paymentRepository.findById(payment.getId()).orElseThrow();
        assertThat(updatedPayment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(updatedPayment.getFailureCode()).isEqualTo("DLT_ROUTED");
        assertThat(updatedPayment.getFailureMessage()).isEqualTo("Gateway timeout after max retry attempts");

        List<DeadLetterEvent> dltEvents = deadLetterEventRepository.findByPaymentIdOrderByCreatedAtDesc(payment.getId());
        assertThat(dltEvents).hasSize(1);
        DeadLetterEvent dltEvent = dltEvents.get(0);
        assertThat(dltEvent.getOriginalTopic()).isEqualTo("kaipay.payment.requests-dlt");
        assertThat(dltEvent.getOriginalPartition()).isEqualTo(0);
        assertThat(dltEvent.getOriginalOffset()).isEqualTo(15L);
        assertThat(dltEvent.getEventId()).isEqualTo(eventId);
        assertThat(dltEvent.getPaymentId()).isEqualTo(payment.getId());
        assertThat(dltEvent.getExceptionClass()).isEqualTo("com.lky.kaipay.common.exception.GatewayTimeoutException");
        assertThat(dltEvent.getFailureMessage()).isEqualTo("Gateway timeout after max retry attempts");
        assertThat(dltEvent.getPayload()).contains(payment.getId().toString());
    }
}
