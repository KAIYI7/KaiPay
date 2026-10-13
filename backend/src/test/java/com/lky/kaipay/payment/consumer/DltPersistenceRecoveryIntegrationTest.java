package com.lky.kaipay.payment.consumer;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import ch.qos.logback.core.AppenderBase;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lky.kaipay.AbstractPostgresIntegrationTest;
import com.lky.kaipay.common.observability.CorrelationContext;
import com.lky.kaipay.dlt.repository.DeadLetterEventRepository;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = "spring.kafka.consumer.max-poll-records=1")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DltPersistenceRecoveryIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String SUFFIX = UUID.randomUUID().toString();
    private static final String TOPIC = "kaipay.dlt-recovery-" + SUFFIX;
    private static final String DLT_TOPIC = TOPIC + "-dlt";
    private static final String CONSTRAINT = "dlt_persistence_test_rejection";
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    @Autowired private KafkaTemplate<String, String> kafkaTemplate;
    @Autowired private KafkaListenerEndpointRegistry registry;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DeadLetterEventRepository deadLetters;
    @Autowired private ObjectMapper objectMapper;

    @DynamicPropertySource
    static void isolateKafka(DynamicPropertyRegistry properties) {
        properties.add("kaipay.kafka.topics.payment-requests", () -> TOPIC);
        properties.add("spring.kafka.consumer.group-id", () -> "kaipay-dlt-recovery-" + SUFFIX);
    }

    @Test
    void failedDatabaseSaveKeepsBrokerEvidenceUntilRecoveryAndOffsetCompletion() throws Exception {
        UUID eventId = UUID.randomUUID();
        String correlation = "dlt-recovery-" + eventId;
        String payload = objectMapper.writeValueAsString(Map.of("eventId", eventId,
                "payload", Map.of("description", "persist this dead-letter evidence")));
        MessageListenerContainer dlt = registry.getAllListenerContainers().stream()
                .filter(container -> Arrays.asList(container.getContainerProperties().getTopics()).contains(DLT_TOPIC))
                .findFirst().orElseThrow();
        CountDownLatch saveFailed = new CountDownLatch(1);
        AtomicReference<Throwable> rejection = new AtomicReference<>();
        Logger logger = (Logger) LoggerFactory.getLogger(PaymentProcessingConsumer.class);
        AppenderBase<ILoggingEvent> barrier = new AppenderBase<>() {
            @Override
            protected void append(ILoggingEvent event) {
                if ("Failed to persist dead letter event record".equals(event.getFormattedMessage())
                        && correlation.equals(event.getMDCPropertyMap().get("traceId"))) {
                    if (event.getThrowableProxy() instanceof ThrowableProxy throwable
                            && rejection.compareAndSet(null, throwable.getThrowable())) {
                        // Called synchronously on the listener thread, before the exception is rethrown.
                        // One record per poll ensures no next DLT attempt runs before the pause.
                        dlt.pause();
                        saveFailed.countDown();
                    }
                }
            }
        };
        barrier.start();
        logger.addAppender(barrier);

        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                KAFKA_CONTAINER.getBootstrapServers()));
             KafkaConsumer<String, String> probe = new KafkaConsumer<>(Map.of(
                     ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA_CONTAINER.getBootstrapServers(),
                     ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                     ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                     ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false))) {
            jdbc.execute("ALTER TABLE dead_letter_events ADD CONSTRAINT " + CONSTRAINT
                    + " CHECK (event_id IS DISTINCT FROM '" + eventId + "'::uuid)");
            Awaitility.await().atMost(TIMEOUT).until(() -> dlt.getAssignedPartitions() != null
                    && !dlt.getAssignedPartitions().isEmpty());
            var topic = admin.describeTopics(List.of(DLT_TOPIC)).allTopicNames().get(5, TimeUnit.SECONDS)
                    .get(DLT_TOPIC);
            List<TopicPartition> partitions = topic.partitions().stream()
                    .map(partition -> new TopicPartition(DLT_TOPIC, partition.partition())).toList();
            probe.assign(partitions);
            probe.seekToBeginning(partitions);
            ProducerRecord<String, String> input = new ProducerRecord<>(DLT_TOPIC, 0, eventId.toString(), payload);
            input.headers().add(CorrelationContext.HEADER_NAME, correlation.getBytes(StandardCharsets.US_ASCII));
            var original = kafkaTemplate.send(input).get(5, TimeUnit.SECONDS).getRecordMetadata();

            assertThat(saveFailed.await(20, TimeUnit.SECONDS)).as("real PostgreSQL save failure").isTrue();
            Awaitility.await().atMost(TIMEOUT).until(dlt::isContainerPaused);
            assertThat(isInjectedConstraintViolation(rejection.get())).as("targeted CHECK violation, SQLSTATE 23514")
                    .isTrue();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM dead_letter_events WHERE event_id = ?",
                    Long.class, eventId)).isZero();

            List<ConsumerRecord<String, String>> retained = new ArrayList<>();
            Awaitility.await().atMost(TIMEOUT).untilAsserted(() -> {
                probe.poll(Duration.ofMillis(100)).forEach(record -> {
                    if (eventId.toString().equals(record.key())) retained.add(record);
                });
                assertThat(retained).hasSize(2);
            });
            assertThat(retained).allSatisfy(record -> {
                assertThat(record.value()).isEqualTo(payload);
                assertThat(new String(record.headers().lastHeader(CorrelationContext.HEADER_NAME).value(),
                        StandardCharsets.US_ASCII)).isEqualTo(correlation);
            });
            assertThat(retained).anySatisfy(record -> {
                assertThat(record.partition()).isEqualTo(original.partition());
                assertThat(record.offset()).isEqualTo(original.offset());
            });
            ConsumerRecord<String, String> forwarded = retained.stream()
                    .filter(record -> record.partition() != original.partition() || record.offset() != original.offset())
                    .findFirst().orElseThrow();
            assertThat(forwarded.partition()).isEqualTo(original.partition());
            assertThat(forwarded.offset()).isGreaterThan(original.offset());
            TopicPartition forwardedPartition = new TopicPartition(forwarded.topic(), forwarded.partition());
            var pausedOffset = admin.listConsumerGroupOffsets(dlt.getGroupId()).partitionsToOffsetAndMetadata()
                    .get(5, TimeUnit.SECONDS).get(forwardedPartition);
            // The failed source offset may advance after forwarding. Its replacement must remain outstanding.
            if (pausedOffset != null) assertThat(pausedOffset.offset()).isLessThanOrEqualTo(forwarded.offset());

            jdbc.execute("ALTER TABLE dead_letter_events DROP CONSTRAINT " + CONSTRAINT);
            dlt.resume();
            Awaitility.await().atMost(TIMEOUT).untilAsserted(() -> {
                var saved = deadLetters.findAll().stream().filter(event -> eventId.equals(event.getEventId())).toList();
                assertThat(saved).hasSize(1);
                assertThat(objectMapper.readTree(saved.get(0).getPayload())).isEqualTo(objectMapper.readTree(payload));
                assertThat(saved.get(0).getHeaders()).containsEntry(CorrelationContext.HEADER_NAME, correlation);
                assertThat(saved.get(0).getOriginalTopic()).isEqualTo(DLT_TOPIC);
                assertThat(saved.get(0).getOriginalPartition()).isEqualTo(forwarded.partition());
                assertThat(saved.get(0).getOriginalOffset()).isEqualTo(forwarded.offset());
                var committed = admin.listConsumerGroupOffsets(dlt.getGroupId()).partitionsToOffsetAndMetadata()
                        .get(5, TimeUnit.SECONDS).get(forwardedPartition);
                assertThat(committed).isNotNull();
                assertThat(committed.offset()).isGreaterThanOrEqualTo(forwarded.offset() + 1);
            });
        } finally {
            try {
                jdbc.execute("ALTER TABLE dead_letter_events DROP CONSTRAINT IF EXISTS " + CONSTRAINT);
            } finally {
                logger.detachAppender(barrier);
                barrier.stop();
                dlt.resume();
                // This class owns a unique listener context; stop it before removing its audit row
                // so a failed assertion cannot leave an in-flight recovery writing after cleanup.
                dlt.stop();
                jdbc.update("DELETE FROM dead_letter_events WHERE event_id = ?", eventId);
            }
        }
    }

    private boolean isInjectedConstraintViolation(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && "23514".equals(sql.getSQLState())
                    && sql.getMessage().contains(CONSTRAINT)) return true;
        }
        return false;
    }
}
