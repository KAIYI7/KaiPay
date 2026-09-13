package com.lky.kaipay.payment.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lky.kaipay.AbstractPostgresIntegrationTest;
import com.lky.kaipay.common.event.EventEnvelope;
import com.lky.kaipay.customer.domain.Customer;
import com.lky.kaipay.customer.repository.CustomerRepository;
import com.lky.kaipay.dlt.domain.DeadLetterEvent;
import com.lky.kaipay.dlt.repository.DeadLetterEventRepository;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Payment Poison Pill & DLT Integration Tests")
class PaymentPoisonPillAndDltIntegrationTest extends AbstractPostgresIntegrationTest {

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
    private DeadLetterEventRepository deadLetterEventRepository;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private Merchant testMerchant;
    private Customer testCustomer;

    @BeforeEach
    void setUp() {
        mockBankAcquirerClient.clearLedger();
        deadLetterEventRepository.deleteAll();
        paymentRepository.deleteAll();
        customerRepository.deleteAll();
        merchantRepository.deleteAll();

        testMerchant = merchantRepository.save(
                Merchant.builder()
                        .name("DLT Test Merchant Corp")
                        .apiKeyHash("test_hash_" + UUID.randomUUID())
                        .status(MerchantStatus.ACTIVE)
                        .build()
        );

        testCustomer = customerRepository.save(
                Customer.builder()
                        .merchant(testMerchant)
                        .email("dlt-test@example.com")
                        .fullName("DLT Test User")
                        .build()
        );
    }

    @Test
    @DisplayName("Persistent timeout exhausts retries (3 attempts), fails payment, and routes to DLT")
    void testRetryExhaustion_PaymentFailsAndRoutesToDlt() throws Exception {
        long amountCents = 777700L; // $7,777.00 - persistent timeout
        String currency = "USD";
        String idempotencyKey = "dlt-exhaustion-" + UUID.randomUUID();

        CreatePaymentRequest createPaymentRequest = CreatePaymentRequest.builder()
                .customerId(testCustomer.getId())
                .amountCents(amountCents)
                .currency(currency)
                .build();

        PaymentResponse response = paymentService.createPayment(testMerchant.getId(), idempotencyKey, createPaymentRequest);
        UUID paymentId = response.getId();

        Payment initialPayment = paymentRepository.findById(paymentId).orElseThrow();
        assertThat(initialPayment.getStatus()).isEqualTo(PaymentStatus.CREATED);

        mockBankAcquirerClient.clearLedger();

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
        kafkaTemplate.send("kaipay.payment.requests", paymentId.toString(), payload).get();

        Awaitility.await()
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    assertThat(mockBankAcquirerClient.getExecutionCount(paymentId)).isEqualTo(3);

                    Payment updatedPayment = paymentRepository.findById(paymentId).orElseThrow();
                    assertThat(updatedPayment.getStatus()).isEqualTo(PaymentStatus.FAILED);
                    assertThat(updatedPayment.getFailureCode()).isEqualTo("DLT_ROUTED");

                    List<DeadLetterEvent> dltEvents = deadLetterEventRepository.findByPaymentIdOrderByCreatedAtDesc(paymentId);
                    assertThat(dltEvents).hasSize(1);
                    assertThat(dltEvents.get(0).getExceptionClass()).contains("GatewayTimeoutException");
                });
    }

    @Test
    @DisplayName("Unresolvable poison pill routes directly to DLT without payment update")
    void testPoisonPill_UnresolvableIdentity_RoutesDirectlyToDltWithoutPaymentUpdate() throws Exception {
        String poisonPillPayload = "invalid-poison-pill-payload-no-json";
        String messageKey = "unresolvable-key-" + UUID.randomUUID();

        kafkaTemplate.send("kaipay.payment.requests", messageKey, poisonPillPayload).get();

        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    List<DeadLetterEvent> allDltEvents = deadLetterEventRepository.findAll();
                    assertThat(allDltEvents).anySatisfy(event -> {
                        assertThat(event.getPaymentId()).isNull();
                        assertThat(event.getPayload()).contains("invalid-poison-pill");
                    });
                });
    }

    @Test
    @DisplayName("Resolvable poison pill with fatal error marks payment FAILED and routes directly to DLT bypassing retries")
    void testPoisonPill_ResolvableIdentity_FatalError_MarksPaymentFailedAndRoutesToDlt() throws Exception {
        long amountCents = 666600L; // $6,666.00 - non-retryable fatal error
        String currency = "USD";
        String idempotencyKey = "fatal-error-" + UUID.randomUUID();

        CreatePaymentRequest createPaymentRequest = CreatePaymentRequest.builder()
                .customerId(testCustomer.getId())
                .amountCents(amountCents)
                .currency(currency)
                .build();

        PaymentResponse response = paymentService.createPayment(testMerchant.getId(), idempotencyKey, createPaymentRequest);
        UUID paymentId = response.getId();

        Payment initialPayment = paymentRepository.findById(paymentId).orElseThrow();
        assertThat(initialPayment.getStatus()).isEqualTo(PaymentStatus.CREATED);

        mockBankAcquirerClient.clearLedger();

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
        kafkaTemplate.send("kaipay.payment.requests", paymentId.toString(), payload).get();

        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    assertThat(mockBankAcquirerClient.getExecutionCount(paymentId)).isEqualTo(1);

                    Payment updatedPayment = paymentRepository.findById(paymentId).orElseThrow();
                    assertThat(updatedPayment.getStatus()).isEqualTo(PaymentStatus.FAILED);

                    List<DeadLetterEvent> dltEvents = deadLetterEventRepository.findByPaymentIdOrderByCreatedAtDesc(paymentId);
                    assertThat(dltEvents).hasSize(1);
                    assertThat(dltEvents.get(0).getExceptionClass()).contains("NonRetryableGatewayException");
                });
    }
}
