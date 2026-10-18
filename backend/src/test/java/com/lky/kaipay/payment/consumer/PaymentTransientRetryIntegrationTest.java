package com.lky.kaipay.payment.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lky.kaipay.AbstractPostgresIntegrationTest;
import com.lky.kaipay.common.event.EventEnvelope;
import com.lky.kaipay.consumer.repository.ConsumedEventRepository;
import com.lky.kaipay.customer.domain.Customer;
import com.lky.kaipay.customer.repository.CustomerRepository;
import com.lky.kaipay.merchant.domain.Merchant;
import com.lky.kaipay.merchant.domain.MerchantStatus;
import com.lky.kaipay.merchant.repository.MerchantRepository;
import com.lky.kaipay.payment.api.dto.CreatePaymentRequest;
import com.lky.kaipay.payment.api.dto.PaymentResponse;
import com.lky.kaipay.payment.domain.Payment;
import com.lky.kaipay.payment.domain.PaymentStatus;
import com.lky.kaipay.payment.domain.event.PaymentInitiatedEvent;
import com.lky.kaipay.payment.repository.PaymentRepository;
import com.lky.kaipay.payment.service.PaymentService;
import com.lky.kaipay.payment.service.acquirer.MockBankAcquirerClient;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Payment Transient Failure & Retry-to-Success Integration Tests")
class PaymentTransientRetryIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private PaymentService paymentService;

    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired private com.lky.kaipay.dlt.repository.DeadLetterEventRepository deadLetters;

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
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private ObjectMapper objectMapper;

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
                        .name("Retry Merchant Corp")
                        .apiKeyHash("test_hash_" + UUID.randomUUID())
                        .status(MerchantStatus.ACTIVE)
                        .build()
        );

        testCustomer = customerRepository.save(
                Customer.builder()
                        .merchant(testMerchant)
                        .email("retry-test@example.com")
                        .fullName("Retry Test User")
                        .build()
        );
    }

    @Test
    @DisplayName("Transient gateway timeout triggers Spring Kafka retry topic and recovers on third attempt")
    void testTransientTimeout_RetriesAndSucceedsOnThirdAttempt() throws Exception {
        long amountCents = 888800L; // RM8,888.00 triggers timeout on attempts 1 & 2, recovers on attempt 3
        String currency = "MYR";
        String idempotencyKey = "retry-key-" + UUID.randomUUID();

        // 1. Create payment with amount 888800L via paymentService.createPayment -> status CREATED
        CreatePaymentRequest createPaymentRequest = CreatePaymentRequest.builder()
                .customerId(testCustomer.getId())
                .amountCents(amountCents)
                .currency(currency)
                .build();

        PaymentResponse response = paymentService.createPayment(testMerchant.getId(), idempotencyKey, createPaymentRequest);
        UUID paymentId = response.getId();

        Payment initialPayment = paymentRepository.findById(paymentId).orElseThrow();
        assertThat(initialPayment.getStatus()).isEqualTo(PaymentStatus.CREATED);


        // 3. Build serialized EventEnvelope<PaymentInitiatedEvent> with distinct UUID eventId = UUID.randomUUID()
        UUID eventId = UUID.randomUUID();
        PaymentInitiatedEvent initiatedEvent = PaymentInitiatedEvent.fromPayment(initialPayment);
        EventEnvelope<PaymentInitiatedEvent> envelope = EventEnvelope.<PaymentInitiatedEvent>builder()
                .eventId(eventId)
                .eventType("PaymentInitiatedEvent")
                .aggregateType("PAYMENT")
                .aggregateId(paymentId.toString())
                .merchantId(testMerchant.getId())
                .payload(initiatedEvent)
                .build();

        String payload = objectMapper.writeValueAsString(envelope);

        // 4. Send to topic kaipay.payment.requests with key paymentId.toString()
        kafkaTemplate.send("kaipay.payment.requests", paymentId.toString(), payload).get();

        // 5. Use Awaitility to await up to 10s and assert retry-to-success
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    // Assert mockBankAcquirerClient.getEvaluationCount(paymentId) == 3
                    assertThat(mockBankAcquirerClient.getEvaluationCount(paymentId)).isEqualTo(3);

                    // Assert Payment in PostgreSQL has status PaymentStatus.AUTHORIZED and gatewayReference contains AUTH-RECOVERED-
                    Payment updatedPayment = paymentRepository.findById(paymentId).orElseThrow();
                    assertThat(updatedPayment.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
                    assertThat(updatedPayment.getGatewayReference()).isNotNull().contains("AUTH-RECOVERED-");

                    // Assert consumedEventRepository.existsByIdEventIdAndIdConsumerGroup(eventId, "kaipay-payment-processor-group") is true
                    boolean eventConsumed = consumedEventRepository.existsByIdEventIdAndIdConsumerGroup(
                            eventId,
                            "kaipay-payment-processor-group"
                    );
                    assertThat(eventConsumed).isTrue();
                });
    }

    @Test
    @DisplayName("A real transient Tx2 serialization failure retries through Kafka using the durable simulator result")
    void localTransientCompletionFailureRollsBackThenRetriesWithoutReevaluation() throws Exception {
        Payment initial = paymentRepository.saveAndFlush(Payment.builder().merchant(testMerchant).customer(testCustomer)
                .amountCents(5000L).currency("MYR").status(PaymentStatus.CREATED)
                .idempotencyKey("local-retry-" + UUID.randomUUID()).build());
        var envelope = EventEnvelope.of("PaymentInitiatedEvent", "PAYMENT", initial.getId().toString(),
                testMerchant.getId(), PaymentInitiatedEvent.fromPayment(initial));
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String sequence = "kp_retry_seq_" + suffix;
        String function = "kp_retry_function_" + suffix;
        String trigger = "kp_retry_trigger_" + suffix;
        jdbc.execute("CREATE SEQUENCE " + sequence);
        try {
            jdbc.execute("CREATE FUNCTION " + function + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                    + "IF NEW.payment_id = '" + initial.getId() + "'::uuid THEN "
                    + "IF nextval('" + sequence + "') = 1 THEN RAISE EXCEPTION 'injected serialization conflict' USING ERRCODE = '40001'; "
                    + "END IF; END IF; RETURN NEW; END $$");
            jdbc.execute("CREATE TRIGGER " + trigger + " BEFORE INSERT ON consumed_events FOR EACH ROW EXECUTE FUNCTION " + function + "()");
            kafkaTemplate.send("kaipay.payment.requests", initial.getId().toString(),
                    objectMapper.writeValueAsString(envelope)).get();
            Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                Payment completed = paymentRepository.findById(initial.getId()).orElseThrow();
                assertThat(completed.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
                assertThat(completed.getGatewayReference()).startsWith("AUTH-");
                assertThat(completed.getVersion()).isEqualTo(initial.getVersion() + 2);
                assertThat(consumedEventRepository.findByPaymentIdOrderByProcessedAtAsc(initial.getId()))
                        .singleElement().satisfies(marker -> {
                            assertThat(marker.getId().getEventId()).isEqualTo(envelope.getEventId());
                            assertThat(marker.getStatus()).isEqualTo("AUTHORIZED");
                        });
                assertThat(jdbc.queryForObject("select last_value from " + sequence, Long.class)).isEqualTo(2);
                assertThat(mockBankAcquirerClient.getEvaluationCount(initial.getId())).isEqualTo(1);
                assertThat(jdbc.queryForObject("select disposition from simulated_authorization_operations where payment_id = ?",
                        String.class, initial.getId())).isEqualTo("APPROVED");
                assertThat(deadLetters.findByPaymentIdOrderByCreatedAtDesc(initial.getId())).isEmpty();
            });
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS " + trigger + " ON consumed_events");
            jdbc.execute("DROP FUNCTION IF EXISTS " + function + "()");
            jdbc.execute("DROP SEQUENCE IF EXISTS " + sequence);
        }
    }
}
