package com.lky.kaipay.outbox.service;

import com.lky.kaipay.AbstractPostgresIntegrationTest;
import com.lky.kaipay.outbox.domain.PaymentEventOutbox;
import com.lky.kaipay.outbox.domain.PaymentEventOutboxStatus;
import com.lky.kaipay.outbox.repository.PaymentEventOutboxRepository;
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

class OutboxEventPublisherIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private OutboxEventPublisher outboxEventPublisher;

    @Autowired
    private PaymentEventOutboxRepository outboxRepository;

    @BeforeEach
    void setUp() {
        outboxRepository.deleteAll();
    }

    @Test
    @DisplayName("publishPendingEvents should return 0 when no rows exist")
    void testPublishPendingEvents_EmptyList() {
        int result = outboxEventPublisher.publishPendingEvents();
        assertThat(result).isEqualTo(0);
    }

    @Test
    @DisplayName("publishPendingEvents should publish PENDING outbox events to Kafka and mark them PUBLISHED in DB")
    void testPublishPendingEvents_Success() {
        String aggregateId = "pay-" + UUID.randomUUID();
        String payload = "{\"paymentId\":\"" + aggregateId + "\",\"amountCents\":15000,\"currency\":\"USD\"}";

        PaymentEventOutbox outbox = PaymentEventOutbox.builder()
                .aggregateType("PAYMENT")
                .aggregateId(aggregateId)
                .eventType("PAYMENT_INITIATED")
                .payload(payload)
                .headers(Map.of("traceId", UUID.randomUUID().toString()))
                .status(PaymentEventOutboxStatus.PENDING)
                .retryCount(0)
                .build();

        PaymentEventOutbox saved = outboxRepository.save(outbox);
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getStatus()).isEqualTo(PaymentEventOutboxStatus.PENDING);
        assertThat(saved.getPublishedAt()).isNull();

        // Trigger poller
        int publishedCount = outboxEventPublisher.publishPendingEvents();
        assertThat(publishedCount).isEqualTo(1);

        // Verify outbox record in DB is now PUBLISHED with publishedAt != null
        PaymentEventOutbox updated = outboxRepository.findById(saved.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(PaymentEventOutboxStatus.PUBLISHED);
        assertThat(updated.getPublishedAt()).isNotNull();

        // Verify Kafka consumer receives the record from topic kaipay.payment.requests
        Properties consumerProps = new Properties();
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA_CONTAINER.getBootstrapServers());
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

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
                    if (aggregateId.equals(record.key()) && record.value().contains(aggregateId)) {
                        received = true;
                        break;
                    }
                }
            }

            assertThat(received).as("Expected Kafka message with key %s and containing payload %s", aggregateId, aggregateId).isTrue();
        }
    }
}
