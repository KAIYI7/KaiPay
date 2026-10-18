package com.lky.kaipay.common.observability;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.AppenderBase;
import com.lky.kaipay.AbstractPostgresIntegrationTest;
import com.lky.kaipay.dlt.domain.DeadLetterEvent;
import com.lky.kaipay.dlt.repository.DeadLetterEventRepository;
import com.lky.kaipay.outbox.domain.PaymentEventOutbox;
import com.lky.kaipay.outbox.domain.PaymentEventOutboxStatus;
import com.lky.kaipay.outbox.repository.PaymentEventOutboxRepository;
import com.lky.kaipay.outbox.service.OutboxEventPublisher;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Capture root logs, including framework failures, against real Kafka and PostgreSQL. */
class PrivacyLoggingIntegrationTest extends AbstractPostgresIntegrationTest {
    @Autowired DeadLetterEventRepository deadLetters;
    @Autowired PaymentEventOutboxRepository outbox;
    @Autowired OutboxEventPublisher publisher;
    @Autowired org.springframework.kafka.core.KafkaTemplate<String, String> kafkaTemplate;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired org.springframework.scheduling.TaskScheduler scheduler;
    @Autowired org.springframework.context.ApplicationEventPublisher events;

    @Test void scheduledTransactionCommitFailureOmitsRowEvidenceAndKeepsPendingRecord() {
        String marker = "private-scheduled-row-" + UUID.randomUUID();
        var event = outbox.saveAndFlush(PaymentEventOutbox.builder().aggregateType("PAYMENT")
                .aggregateId(UUID.randomUUID().toString()).eventType("PaymentCapturedEvent")
                .payload("{\"eventType\":\"PaymentCapturedEvent\",\"payload\":{\"paymentId\":\""
                        + UUID.randomUUID() + "\",\"description\":\"" + marker + "\"}}").build());
        jdbc.execute("ALTER TABLE payment_events_outbox ADD CONSTRAINT privacy_scheduled_probe CHECK (id <> '"
                + event.getId() + "'::uuid OR status <> 'PUBLISHED')");
        java.util.concurrent.ScheduledFuture<?> scheduled = null;
        try (var logs = new Logs()) {
            scheduled = scheduler.scheduleWithFixedDelay(publisher::publishPendingEvents,
                    java.time.Instant.now(), Duration.ofHours(1));
            Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                    assertThat(logs.content()).contains("Scheduled task failed:", "sqlState=23514"));
            assertThat(logs.content()).doesNotContain(marker, "Failing row");
            var pending = outbox.findById(event.getId()).orElseThrow();
            assertThat(pending.getStatus()).isEqualTo(PaymentEventOutboxStatus.PENDING);
            assertThat(pending.getRetryCount()).isZero();
            assertThat(pending.getPayload()).contains(marker);
        } finally {
            if (scheduled != null) scheduled.cancel(false);
            jdbc.execute("ALTER TABLE payment_events_outbox DROP CONSTRAINT privacy_scheduled_probe");
            outbox.deleteById(event.getId());
        }
    }

    @Test void lifecycleEventsRetainFatalSignalsWithoutRenderingEventSources() {
        String marker = "private-lifecycle-source";
        Object source = new Object() { @Override public String toString() { return marker; } };
        try (var logs = new Logs()) {
            events.publishEvent(new org.springframework.kafka.event.ConsumerStartedEvent(source, source));
            events.publishEvent(new org.springframework.kafka.event.ConsumerFailedToStartEvent(source, source));
            for (var reason : org.springframework.kafka.event.ConsumerStoppedEvent.Reason.values()) {
                events.publishEvent(new org.springframework.kafka.event.ConsumerStoppedEvent(source, source, reason));
                assertThat(logs.content()).contains("Kafka consumer stopped: reason=" + reason.name());
            }
            events.publishEvent(org.mockito.Mockito.mock(org.springframework.kafka.event.NonResponsiveConsumerEvent.class));
            assertThat(logs.content()).contains("Kafka consumer started", "Kafka consumer failed to start",
                    "Kafka consumer poll nonresponsive").doesNotContain(marker);
        }
    }

    @Test void malformedBrokerRecordKeepsEvidenceWithoutLoggingKeyPayloadOrHeaders() throws Exception {
        String marker = "private-poison-" + UUID.randomUUID();
        var record = new ProducerRecord<String, String>("kaipay.payment.requests", marker, marker);
        record.headers().add("Authorization", marker.getBytes(StandardCharsets.UTF_8));
        try (var logs = new Logs()) {
            kafkaTemplate.send(record).get(5, TimeUnit.SECONDS);
            Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                var saved = deadLetters.findAll().stream().filter(e -> e.getPayload().contains(marker)).toList();
                assertThat(saved).hasSize(1);
                assertThat(saved.getFirst().getHeaders()).containsEntry("Authorization", marker);
                assertThat(logs.content()).contains("Saved dead letter event record", "Fatal event processing error");
            });
            assertThat(logs.content()).doesNotContain(marker);
        } finally {
            deadLetters.findAll().stream().filter(e -> e.getPayload().contains(marker)).forEach(deadLetters::delete);
        }
    }

    @Test void oversizedPublishRetainsQuarantineAndCategoryWithoutProducerPayloadLogging() {
        String marker = "private-oversized-" + UUID.randomUUID();
        var event = outbox.saveAndFlush(PaymentEventOutbox.builder().aggregateType("PAYMENT")
                .aggregateId(UUID.randomUUID().toString()).eventType("PaymentInitiatedEvent")
                .payload("{\"" + marker + "\":\"" + "x".repeat(2_000_000) + "\"}").build());
        try (var logs = new Logs()) {
            publisher.publishPendingEvents();
            var failed = outbox.findById(event.getId()).orElseThrow();
            assertThat(failed.getStatus()).isEqualTo(PaymentEventOutboxStatus.QUARANTINED);
            assertThat(failed.getRetryCount()).isEqualTo(1);
            assertThat(failed.getLastError()).isEqualTo("RecordTooLargeException");
            assertThat(failed.getPayload()).contains(marker);
            assertThat(logs.content()).contains("Failed to publish outbox event", "RecordTooLargeException")
                    .doesNotContain(marker);
        } finally { outbox.deleteById(event.getId()); }
    }

    @Test void postgresFailureDoesNotLogFailedRowContents() {
        String marker = "private-sql-row-" + UUID.randomUUID();
        var event = DeadLetterEvent.builder().originalTopic(marker).originalPartition(0)
                .originalOffset(0).exceptionClass("ProcessingFailure")
                .payload("\"" + marker + "\"").headers(Map.of("secret", marker)).build();
        jdbc.execute("ALTER TABLE dead_letter_events ADD CONSTRAINT privacy_logging_probe CHECK (original_topic <> '" + marker + "')");
        try (var logs = new Logs()) {
            assertThatThrownBy(() -> deadLetters.saveAndFlush(event)).isInstanceOf(RuntimeException.class);
            assertThat(logs.content()).doesNotContain(marker, "Failing row", "insert into dead_letter_events");
        } finally { jdbc.execute("ALTER TABLE dead_letter_events DROP CONSTRAINT privacy_logging_probe"); }
    }

    private static final class Logs extends AppenderBase<ILoggingEvent> implements AutoCloseable {
        private final Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        private final CopyOnWriteArrayList<String> events = new CopyOnWriteArrayList<>();
        Logs() { start(); root.addAppender(this); }
        @Override protected void append(ILoggingEvent event) {
            events.add(event.getFormattedMessage() + (event.getThrowableProxy() == null ? ""
                    : ThrowableProxyUtil.asString(event.getThrowableProxy())));
        }
        String content() { return String.join("\n", events); }
        @Override public void close() { root.detachAppender(this); stop(); }
    }
}
