package com.lky.kaipay.payment.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lky.kaipay.AbstractPostgresIntegrationTest;
import com.lky.kaipay.common.event.EventEnvelope;
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
import com.lky.kaipay.payment.service.acquirer.AcquirerAuthorizationResult;
import com.lky.kaipay.payment.service.acquirer.MockBankAcquirerClient;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Payment Gateway Crash & Kafka Redelivery Integration Tests")
class PaymentGatewayRedeliveryIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private MerchantRepository merchantRepository;

    @Autowired
    private CustomerRepository customerRepository;

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
        paymentRepository.deleteAll();
        customerRepository.deleteAll();
        merchantRepository.deleteAll();

        testMerchant = merchantRepository.save(
                Merchant.builder()
                        .name("Redelivery Merchant Corp")
                        .apiKeyHash("test_hash_" + UUID.randomUUID())
                        .status(MerchantStatus.ACTIVE)
                        .build()
        );

        testCustomer = customerRepository.save(
                Customer.builder()
                        .merchant(testMerchant)
                        .email("redelivery-test@example.com")
                        .fullName("Redelivery Test User")
                        .build()
        );
    }

    @Test
    @DisplayName("Simulate Gateway crash before Tx2 commit and Kafka redelivery maintaining idempotency")
    void testGatewayCrashAndKafkaRedeliveryMaintainsIdempotency() throws Exception {
        // Set up test payment via paymentRepository.save -> status is CREATED
        long amountCents = 5000L;
        String currency = "USD";
        String idempotencyKey = "crash-key-" + UUID.randomUUID();

        Payment createdPayment = paymentRepository.save(
                Payment.builder()
                        .merchant(testMerchant)
                        .customer(testCustomer)
                        .amountCents(amountCents)
                        .currency(currency)
                        .status(PaymentStatus.CREATED)
                        .idempotencyKey(idempotencyKey)
                        .build()
        );
        UUID paymentId = createdPayment.getId();
        assertThat(createdPayment.getStatus()).isEqualTo(PaymentStatus.CREATED);

        // Clear/prepare acquirer ledger
        mockBankAcquirerClient.clearLedger();

        // Build serialized PaymentInitiatedEvent message payload
        PaymentInitiatedEvent initiatedEvent = PaymentInitiatedEvent.fromPayment(createdPayment);
        EventEnvelope<PaymentInitiatedEvent> envelope = EventEnvelope.of(
                "PaymentInitiatedEvent",
                "PAYMENT",
                paymentId.toString(),
                testMerchant.getId(),
                initiatedEvent
        );
        String messagePayload = objectMapper.writeValueAsString(envelope);

        // -------------------------------------------------------------------------
        // Step 1: Simulate First Attempt with Crash before Tx 2
        // -------------------------------------------------------------------------
        // Call paymentService.transitionToProcessing (Tx 1 commits)
        paymentService.transitionToProcessing(paymentId);

        // Call acquirer client to authorize (Gateway returns auth code)
        AcquirerAuthorizationResult firstAuth = mockBankAcquirerClient.authorize(paymentId, amountCents, currency);
        assertThat(firstAuth.isApproved()).isTrue();
        assertThat(firstAuth.getAuthorizationCode()).isNotNull();

        // Simulate crash: Do NOT call Tx 2 or offset ack.
        // Payment remains in PROCESSING in PostgreSQL
        Payment processingPayment = paymentRepository.findById(paymentId).orElseThrow();
        assertThat(processingPayment.getStatus()).isEqualTo(PaymentStatus.PROCESSING);

        // -------------------------------------------------------------------------
        // Step 2: Simulate Kafka Redelivery
        // -------------------------------------------------------------------------
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "kaipay.payment.requests",
                0,
                0L,
                paymentId.toString(),
                messagePayload
        );
        paymentProcessingConsumer.processPaymentRequest(record, null);

        // -------------------------------------------------------------------------
        // Step 3: Verify Invariants
        // -------------------------------------------------------------------------
        Payment finalPayment = paymentRepository.findById(paymentId).orElseThrow();
        assertThat(finalPayment.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(finalPayment.getGatewayReference()).isEqualTo(firstAuth.getAuthorizationCode());
        assertThat(mockBankAcquirerClient.getExecutionCount(paymentId)).isEqualTo(2);
        assertThat(mockBankAcquirerClient.getUniqueAuthorizationsCount()).isEqualTo(1);
    }
}
