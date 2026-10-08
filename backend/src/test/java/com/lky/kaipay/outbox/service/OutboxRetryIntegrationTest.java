package com.lky.kaipay.outbox.service;

import com.lky.kaipay.AbstractPostgresIntegrationTest;
import com.lky.kaipay.outbox.domain.PaymentEventOutbox;
import com.lky.kaipay.outbox.domain.PaymentEventOutboxStatus;
import com.lky.kaipay.outbox.repository.PaymentEventOutboxRepository;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxRetryIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private PaymentEventOutboxRepository repository;
    @Autowired private OutboxEventPublisher realPublisher;
    @Autowired private KafkaTemplate<String, String> realKafka;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private JdbcTemplate jdbc;

    private final MutableClock clock = new MutableClock();

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        clock.now = Instant.parse("2030-01-01T00:00:00Z");
    }

    @Test
    void realKafkaOversizedRecordIsRetainedAndDoesNotBlockNewerEvent() {
        PaymentEventOutbox oversized = event("{\"padding\":\"" + "x".repeat(2_000_000) + "\"}");
        PaymentEventOutbox healthy = notification();
        jdbc.update("UPDATE payment_events_outbox SET created_at = NOW() - INTERVAL '1 minute' WHERE id = ?", oversized.getId());
        assertThat(realPublisher.publishPendingEvents()).isEqualTo(1);
        PaymentEventOutbox failed = reload(oversized);
        assertThat(failed.getStatus()).isEqualTo(PaymentEventOutboxStatus.QUARANTINED);
        assertThat(failed.getLastError()).contains("RecordTooLargeException");
        assertThat(failed.getQuarantinedAt()).isNotNull();
        assertThat(failed.getRetryCount()).isEqualTo(1);
        assertThat(failed.getPayload()).contains("x".repeat(1000));
        assertThat(failed.getPublishedAt()).isNull();
        assertThat(reload(healthy).getStatus()).isEqualTo(PaymentEventOutboxStatus.PUBLISHED);
        assertThat(realPublisher.publishPendingEvents()).isZero();

        // Guarded requeue after a test-only correction of this synthetic oversized payload.
        // Operators must preserve real financial event semantics when remediating records.
        int requeued = jdbc.update("UPDATE payment_events_outbox SET payload = ?::jsonb, status = 'PENDING', " +
                "next_attempt_at = NOW() WHERE id = ? AND status = 'QUARANTINED' AND published_at IS NULL",
                healthy.getPayload(), oversized.getId());
        assertThat(requeued).isEqualTo(1);
        assertThat(realPublisher.publishPendingEvents()).isEqualTo(1);
        assertThat(reload(oversized).getStatus()).isEqualTo(PaymentEventOutboxStatus.PUBLISHED);
        assertThat(reload(oversized).getRetryCount()).isEqualTo(1);
        assertThat(reload(oversized).getLastError()).contains("RecordTooLargeException");
    }

    @Test
    void retryEligibilityAndBackoffPersistAcrossPublisherInstances() {
        PaymentEventOutbox retry = notification();
        KafkaTemplate<String, String> failingKafka = mock(KafkaTemplate.class);
        when(failingKafka.send(anyString(), anyString(), anyString())).thenReturn(
                CompletableFuture.failedFuture(new TimeoutException("Broker offline")));
        OutboxEventPublisher first = publisher(failingKafka);
        assertThat(publish(first)).isZero();
        assertThat(reload(retry).getNextAttemptAt()).isEqualTo(clock.now.plusSeconds(1));
        assertThat(reload(retry).getLastAttemptAt()).isEqualTo(clock.now);

        OutboxEventPublisher restarted = publisher(failingKafka);
        assertThat(publish(restarted)).isZero();
        verify(failingKafka, times(1)).send(anyString(), anyString(), anyString());
        clock.now = clock.now.plusSeconds(1);
        assertThat(publish(restarted)).isZero();
        assertThat(reload(retry).getNextAttemptAt()).isEqualTo(clock.now.plusSeconds(2));
        assertThat(reload(retry).getRetryCount()).isEqualTo(2);

        // A due older retry must not outrank a fresh event indefinitely, even with batch-size=1.
        PaymentEventOutbox newer = notification();
        clock.now = clock.now.plusSeconds(2);
        OutboxEventPublisher recovered = publisher(realKafka);
        ReflectionTestUtils.setField(recovered, "batchSize", 1);
        assertThat(publish(recovered)).isEqualTo(1);
        assertThat(reload(newer).getStatus()).isEqualTo(PaymentEventOutboxStatus.PUBLISHED);
        assertThat(reload(retry).getStatus()).isEqualTo(PaymentEventOutboxStatus.PENDING);
        assertThat(publish(recovered)).isEqualTo(1);
        assertThat(reload(retry).getStatus()).isEqualTo(PaymentEventOutboxStatus.PUBLISHED);
        assertThat(reload(retry).getNextAttemptAt()).isNull();
        assertThat(reload(retry).getRetryCount()).isEqualTo(2);
    }

    @Test
    void concurrentPublishersSkipLockedClaimAndPublishDistinctRows() throws Exception {
        PaymentEventOutbox first = notification();
        PaymentEventOutbox second = notification();
        jdbc.update("UPDATE payment_events_outbox SET created_at = NOW() - INTERVAL '1 minute' WHERE id = ?", first.getId());
        KafkaTemplate<String, String> controlledKafka = mock(KafkaTemplate.class);
        CountDownLatch firstSend = new CountDownLatch(1);
        CompletableFuture<SendResult<String, String>> heldAck = new CompletableFuture<>();
        when(controlledKafka.send(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            if (first.getAggregateId().equals(invocation.getArgument(1))) {
                firstSend.countDown();
                return heldAck;
            }
            return CompletableFuture.completedFuture(null);
        });
        OutboxEventPublisher workerA = publisher(controlledKafka);
        OutboxEventPublisher workerB = publisher(controlledKafka);
        ReflectionTestUtils.setField(workerA, "batchSize", 1);
        ReflectionTestUtils.setField(workerB, "batchSize", 1);
        ReflectionTestUtils.setField(workerA, "sendTimeoutMs", 10000L);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var firstResult = executor.submit(() -> publish(workerA));
            try {
                assertThat(firstSend.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(publish(workerB)).isEqualTo(1);
                assertThat(reload(first).getStatus()).isEqualTo(PaymentEventOutboxStatus.PENDING);
                assertThat(reload(second).getStatus()).isEqualTo(PaymentEventOutboxStatus.PUBLISHED);
            } finally {
                heldAck.complete(null);
            }
            assertThat(firstResult.get(5, TimeUnit.SECONDS)).isEqualTo(1);
        }
        assertThat(repository.findAll()).allMatch(row -> row.getStatus() == PaymentEventOutboxStatus.PUBLISHED);
        verify(controlledKafka, times(1)).send(anyString(), org.mockito.ArgumentMatchers.eq(first.getAggregateId()), anyString());
        verify(controlledKafka, times(1)).send(anyString(), org.mockito.ArgumentMatchers.eq(second.getAggregateId()), anyString());
    }

    @Test
    void genuineUnavailableBrokerPersistsRetryInsteadOfQuarantine() {
        PaymentEventOutbox pending = notification();
        Map<String, Object> properties = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "127.0.0.1:1",
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.MAX_BLOCK_MS_CONFIG, 500,
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 1000,
                ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 500);
        var factory = new DefaultKafkaProducerFactory<String, String>(properties);
        try {
            KafkaTemplate<String, String> unavailable = new KafkaTemplate<>(factory);
            assertThat(publish(publisher(unavailable))).isZero();
            PaymentEventOutbox failed = reload(pending);
            assertThat(failed.getStatus()).isEqualTo(PaymentEventOutboxStatus.PENDING);
            assertThat(failed.getRetryCount()).isEqualTo(1);
            assertThat(failed.getLastError()).contains("TimeoutException");
            assertThat(failed.getNextAttemptAt()).isEqualTo(clock.now.plusSeconds(1));
            assertThat(failed.getPublishedAt()).isNull();
            assertThat(failed.getQuarantinedAt()).isNull();
            assertThat(publish(publisher(unavailable))).isZero();
            assertThat(reload(pending).getRetryCount()).isEqualTo(1);
        } finally {
            factory.destroy();
        }
    }

    @Test
    void rollbackAfterKafkaAckKeepsPendingForAtLeastOnceRedelivery() {
        PaymentEventOutbox pending = notification();
        new TransactionTemplate(transactions).execute(status -> {
            assertThat(realPublisher.publishPendingEvents()).isEqualTo(1);
            status.setRollbackOnly();
            return null;
        });
        assertThat(reload(pending).getStatus()).isEqualTo(PaymentEventOutboxStatus.PENDING);
        assertThat(reload(pending).getPublishedAt()).isNull();
        assertThat(realPublisher.publishPendingEvents()).isEqualTo(1);
        assertThat(reload(pending).getStatus()).isEqualTo(PaymentEventOutboxStatus.PUBLISHED);
    }

    private PaymentEventOutbox notification() {
        return event("{\"eventType\":\"PaymentCapturedEvent\",\"payload\":{\"paymentId\":\"" + UUID.randomUUID() + "\"}}");
    }

    private PaymentEventOutbox event(String payload) {
        return repository.save(PaymentEventOutbox.builder().aggregateType("PAYMENT")
                .aggregateId(UUID.randomUUID().toString()).eventType("PaymentCapturedEvent").payload(payload).build());
    }

    private PaymentEventOutbox reload(PaymentEventOutbox row) {
        return repository.findById(row.getId()).orElseThrow();
    }

    private OutboxEventPublisher publisher(KafkaTemplate<String, String> kafka) {
        return new OutboxEventPublisher(repository, kafka, clock);
    }

    private int publish(OutboxEventPublisher publisher) {
        return new TransactionTemplate(transactions).execute(status -> publisher.publishPendingEvents());
    }

    private static class MutableClock extends Clock {
        private volatile Instant now;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
