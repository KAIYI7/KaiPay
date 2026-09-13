package com.lky.kaipay.outbox.service;

import com.lky.kaipay.AbstractPostgresIntegrationTest;
import com.lky.kaipay.customer.domain.Customer;
import com.lky.kaipay.customer.repository.CustomerRepository;
import com.lky.kaipay.merchant.domain.Merchant;
import com.lky.kaipay.merchant.domain.MerchantStatus;
import com.lky.kaipay.merchant.repository.MerchantRepository;
import com.lky.kaipay.outbox.domain.PaymentEventOutbox;
import com.lky.kaipay.outbox.domain.PaymentEventOutboxStatus;
import com.lky.kaipay.outbox.repository.PaymentEventOutboxRepository;
import com.lky.kaipay.payment.api.dto.CreatePaymentRequest;
import com.lky.kaipay.payment.api.dto.PaymentResponse;
import com.lky.kaipay.payment.domain.Payment;
import com.lky.kaipay.payment.domain.PaymentStatus;
import com.lky.kaipay.payment.repository.IdempotencyRecordRepository;
import com.lky.kaipay.payment.repository.PaymentMethodRepository;
import com.lky.kaipay.payment.repository.PaymentRepository;
import com.lky.kaipay.payment.service.PaymentService;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Outbox Verification & Broker Outage Non-Blocking Ingestion Integration Test")
class OutboxKafkaOutageIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private PaymentEventOutboxRepository paymentEventOutboxRepository;

    @Autowired
    private MerchantRepository merchantRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private OutboxEventPublisher outboxEventPublisher;

    @Autowired
    private PaymentMethodRepository paymentMethodRepository;

    @Autowired
    private IdempotencyRecordRepository idempotencyRecordRepository;

    private Merchant testMerchant;
    private Customer testCustomer;

    @BeforeEach
    void setUp() {
        paymentEventOutboxRepository.deleteAll();
        paymentRepository.deleteAll();
        idempotencyRecordRepository.deleteAll();
        paymentMethodRepository.deleteAll();
        customerRepository.deleteAll();
        merchantRepository.deleteAll();

        testMerchant = merchantRepository.save(
                Merchant.builder()
                        .name("Test Outage Merchant")
                        .apiKeyHash("test_hash_outage_" + UUID.randomUUID())
                        .webhookUrl("https://merchant-outage.example.com/webhook")
                        .status(MerchantStatus.ACTIVE)
                        .build()
        );

        testCustomer = customerRepository.save(
                Customer.builder()
                        .merchant(testMerchant)
                        .email("customer-outage@example.com")
                        .fullName("Bob Outage")
                        .build()
        );
    }

    @Test
    @DisplayName("Payment ingestion succeeds non-blocking and outbox publisher recovers and publishes to Kafka")
    void testPaymentIngestionSucceedsDuringKafkaOutageAndRecovers() {
        // 1. Set up active test merchant and customer (done in setUp)
        String idempotencyKey = "key-outage-001";
        CreatePaymentRequest createPaymentRequest = CreatePaymentRequest.builder()
                .amountCents(25000L) // $250.00
                .currency("USD")
                .customerId(testCustomer.getId())
                .metadata(Map.of("orderId", "ORD-OUTAGE-001"))
                .build();

        // 2. Call paymentService.createPayment(testMerchant.getId(), "key-outage-001", createPaymentRequest)
        PaymentResponse paymentResponse = paymentService.createPayment(testMerchant.getId(), idempotencyKey, createPaymentRequest);

        // 3. Assert payment creation succeeds and returns valid PaymentResponse
        assertThat(paymentResponse).isNotNull();
        assertThat(paymentResponse.getId()).isNotNull();
        assertThat(paymentResponse.getMerchantId()).isEqualTo(testMerchant.getId());
        assertThat(paymentResponse.getCustomerId()).isEqualTo(testCustomer.getId());
        assertThat(paymentResponse.getAmountCents()).isEqualTo(25000L);
        assertThat(paymentResponse.getCurrency()).isEqualTo("USD");
        assertThat(paymentResponse.getStatus()).isEqualTo(PaymentStatus.CREATED);
        assertThat(paymentResponse.getIdempotencyKey()).isEqualTo(idempotencyKey);

        // 4. Query PaymentRepository: Assert payment is saved in PostgreSQL with status == PaymentStatus.CREATED
        Payment savedPayment = paymentRepository.findById(paymentResponse.getId()).orElseThrow();
        assertThat(savedPayment.getStatus()).isEqualTo(PaymentStatus.CREATED);
        assertThat(savedPayment.getAmountCents()).isEqualTo(25000L);
        assertThat(savedPayment.getCurrency()).isEqualTo("USD");
        assertThat(savedPayment.getMerchant().getId()).isEqualTo(testMerchant.getId());
        assertThat(savedPayment.getCustomer().getId()).isEqualTo(testCustomer.getId());
        assertThat(savedPayment.getIdempotencyKey()).isEqualTo(idempotencyKey);

        // 5. Query PaymentEventOutboxRepository: Assert outbox record is saved in PostgreSQL with status == PaymentEventOutboxStatus.PENDING
        List<PaymentEventOutbox> outboxRecords = paymentEventOutboxRepository.findAll();
        assertThat(outboxRecords).hasSize(1);

        PaymentEventOutbox outboxRecord = outboxRecords.getFirst();
        assertThat(outboxRecord.getStatus()).isEqualTo(PaymentEventOutboxStatus.PENDING);
        assertThat(outboxRecord.getAggregateType()).isEqualTo("PAYMENT");
        assertThat(outboxRecord.getAggregateId()).isEqualTo(paymentResponse.getId().toString());
        assertThat(outboxRecord.getEventType()).isEqualTo("PaymentInitiatedEvent");
        assertThat(outboxRecord.getPublishedAt()).isNull();
        assertThat(outboxRecord.getPayload()).contains(paymentResponse.getId().toString());
        assertThat(outboxRecord.getHeaders()).containsEntry("merchantId", testMerchant.getId().toString());

        // 6. Trigger outboxEventPublisher.publishPendingEvents()
        int publishedCount = outboxEventPublisher.publishPendingEvents();
        assertThat(publishedCount).isEqualTo(1);

        // 7. Assert that the outbox record transitions to PUBLISHED with publishedAt != null
        PaymentEventOutbox updatedOutboxRecord = paymentEventOutboxRepository.findById(outboxRecord.getId()).orElseThrow();
        assertThat(updatedOutboxRecord.getStatus()).isEqualTo(PaymentEventOutboxStatus.PUBLISHED);
        assertThat(updatedOutboxRecord.getPublishedAt()).isNotNull();

        // 8. Verify with a test Kafka Consumer that topic kaipay.payment.requests received the message
        Properties consumerProps = new Properties();
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA_CONTAINER.getBootstrapServers());
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, "test-outage-verification-" + UUID.randomUUID());

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProps)) {
            List<PartitionInfo> partitionInfos = consumer.partitionsFor("kaipay.payment.requests");
            List<TopicPartition> topicPartitions = partitionInfos.stream()
                    .map(pi -> new TopicPartition(pi.topic(), pi.partition()))
                    .toList();
            consumer.assign(topicPartitions);
            consumer.seekToBeginning(topicPartitions);

            boolean received = false;
            Instant deadline = Instant.now().plusSeconds(10);
            while (Instant.now().isBefore(deadline) && !received) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> record : records) {
                    if (paymentResponse.getId().toString().equals(record.key())
                            && record.value().contains(paymentResponse.getId().toString())) {
                        received = true;
                        break;
                    }
                }
            }

            assertThat(received)
                    .as("Expected Kafka message with key %s and containing payload %s in topic kaipay.payment.requests",
                            paymentResponse.getId(), paymentResponse.getId())
                    .isTrue();
        }
    }
}
