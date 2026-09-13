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
import org.apache.kafka.clients.producer.ProducerRecord;
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

@DisplayName("Payment Head-of-Line Partition Non-Blocking Progress Integration Tests")
class PaymentHeadOfLinePartitionIntegrationTest extends AbstractPostgresIntegrationTest {

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
                        .name("HOL Test Merchant Corp")
                        .apiKeyHash("test_hash_" + UUID.randomUUID())
                        .status(MerchantStatus.ACTIVE)
                        .build()
        );

        testCustomer = customerRepository.save(
                Customer.builder()
                        .merchant(testMerchant)
                        .email("hol-test@example.com")
                        .fullName("HOL Test User")
                        .build()
        );
    }

    @Test
    @DisplayName("Poison pill in partition 0 does not block subsequent valid payment processing in the same partition")
    void testPartitionNonBlockingProgress_PoisonPillDoesNotBlockQueue() throws Exception {
        // 1. Create Payment A ($50.00 / 5000 cents) -> status CREATED
        CreatePaymentRequest requestA = CreatePaymentRequest.builder()
                .customerId(testCustomer.getId())
                .amountCents(5000L)
                .currency("USD")
                .build();
        PaymentResponse responseA = paymentService.createPayment(testMerchant.getId(), "idemp-hol-a-" + UUID.randomUUID(), requestA);
        Payment paymentA = paymentRepository.findById(responseA.getId()).orElseThrow();
        assertThat(paymentA.getStatus()).isEqualTo(PaymentStatus.CREATED);

        // 2. Create Payment C ($60.00 / 6000 cents) -> status CREATED
        CreatePaymentRequest requestC = CreatePaymentRequest.builder()
                .customerId(testCustomer.getId())
                .amountCents(6000L)
                .currency("USD")
                .build();
        PaymentResponse responseC = paymentService.createPayment(testMerchant.getId(), "idemp-hol-c-" + UUID.randomUUID(), requestC);
        Payment paymentC = paymentRepository.findById(responseC.getId()).orElseThrow();
        assertThat(paymentC.getStatus()).isEqualTo(PaymentStatus.CREATED);

        // 3. Clear mockBankAcquirerClient ledger before sending events
        mockBankAcquirerClient.clearLedger();

        // 4. Build serialized EventEnvelope<PaymentInitiatedEvent> for Payment A
        PaymentInitiatedEvent eventA = PaymentInitiatedEvent.fromPayment(paymentA);
        EventEnvelope<PaymentInitiatedEvent> envelopeA = EventEnvelope.<PaymentInitiatedEvent>builder()
                .eventId(UUID.randomUUID())
                .eventType("PaymentInitiatedEvent")
                .aggregateType("PAYMENT")
                .aggregateId(paymentA.getId().toString())
                .merchantId(testMerchant.getId())
                .payload(eventA)
                .build();
        String payloadA = objectMapper.writeValueAsString(envelopeA);

        // 5. Build poison string Message B: "corrupted-poison-message-partition-0"
        String poisonPayloadB = "corrupted-poison-message-partition-0";

        // 6. Build serialized EventEnvelope<PaymentInitiatedEvent> for Payment C
        PaymentInitiatedEvent eventC = PaymentInitiatedEvent.fromPayment(paymentC);
        EventEnvelope<PaymentInitiatedEvent> envelopeC = EventEnvelope.<PaymentInitiatedEvent>builder()
                .eventId(UUID.randomUUID())
                .eventType("PaymentInitiatedEvent")
                .aggregateType("PAYMENT")
                .aggregateId(paymentC.getId().toString())
                .merchantId(testMerchant.getId())
                .payload(eventC)
                .build();
        String payloadC = objectMapper.writeValueAsString(envelopeC);

        // 7. Send Message A to kaipay.payment.requests, partition 0
        kafkaTemplate.send(new ProducerRecord<>("kaipay.payment.requests", 0, paymentA.getId().toString(), payloadA)).get();

        // 8. Send Message B to kaipay.payment.requests, partition 0
        kafkaTemplate.send(new ProducerRecord<>("kaipay.payment.requests", 0, "poison-key", poisonPayloadB)).get();

        // 9. Send Message C to kaipay.payment.requests, partition 0
        kafkaTemplate.send(new ProducerRecord<>("kaipay.payment.requests", 0, paymentC.getId().toString(), payloadC)).get();

        // 10. Use Awaitility.await().atMost(Duration.ofSeconds(15)) to assert non-blocking progress
        Awaitility.await()
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    Payment updatedPaymentA = paymentRepository.findById(paymentA.getId()).orElseThrow();
                    assertThat(updatedPaymentA.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);

                    Payment updatedPaymentC = paymentRepository.findById(paymentC.getId()).orElseThrow();
                    assertThat(updatedPaymentC.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);

                    List<DeadLetterEvent> allDltEvents = deadLetterEventRepository.findAll();
                    assertThat(allDltEvents).anySatisfy(event -> {
                        assertThat(event.getPayload()).contains("corrupted-poison-message");
                    });
                });
    }
}
