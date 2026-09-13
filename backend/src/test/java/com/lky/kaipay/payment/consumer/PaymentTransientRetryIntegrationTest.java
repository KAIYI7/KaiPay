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
        mockBankAcquirerClient.clearLedger();
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
        long amountCents = 888800L; // $8,888.00 triggers timeout on attempts 1 & 2, recovers on attempt 3
        String currency = "USD";
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

        // 2. Clear mockBankAcquirerClient ledger
        mockBankAcquirerClient.clearLedger();

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
                    // Assert mockBankAcquirerClient.getExecutionCount(paymentId) == 3
                    assertThat(mockBankAcquirerClient.getExecutionCount(paymentId)).isEqualTo(3);

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
}
