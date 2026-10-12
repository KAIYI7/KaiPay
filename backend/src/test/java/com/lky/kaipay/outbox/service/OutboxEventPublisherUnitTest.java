package com.lky.kaipay.outbox.service;

import com.lky.kaipay.outbox.domain.PaymentEventOutbox;
import com.lky.kaipay.outbox.domain.PaymentEventOutboxStatus;
import com.lky.kaipay.outbox.repository.PaymentEventOutboxRepository;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.slf4j.MDC;

import java.util.Collections;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxEventPublisherUnitTest {

    @Mock
    private PaymentEventOutboxRepository outboxRepository;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private OutboxEventPublisher outboxEventPublisher;

    private final Instant now = Instant.parse("2030-01-01T00:00:00Z");

    @BeforeEach
    void setUp() {
        outboxEventPublisher = new OutboxEventPublisher(outboxRepository, kafkaTemplate, Clock.fixed(now, ZoneOffset.UTC));
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    private ProducerRecord<String, String> messageWithKey(String key) {
        return argThat(record -> record != null && key.equals(record.key())
                && "kaipay.payment.requests".equals(record.topic())
                && "{\"amount\": 1000}".equals(record.value()));
    }

    private PaymentEventOutbox createSampleOutbox(String aggregateId) {
        return PaymentEventOutbox.builder()
                .id(UUID.randomUUID())
                .aggregateType("PAYMENT")
                .aggregateId(aggregateId)
                .eventType("PAYMENT_INITIATED")
                .payload("{\"amount\": 1000}")
                .status(PaymentEventOutboxStatus.PENDING)
                .retryCount(0)
                .build();
    }

    @Test
    @DisplayName("publishPendingEvents should return 0 when no pending events are found")
    void testPublishPendingEvents_EmptyList() {
        when(outboxRepository.findPendingEventsForUpdate(anyInt(), any(Instant.class))).thenReturn(Collections.emptyList());

        int result = outboxEventPublisher.publishPendingEvents();

        assertThat(result).isEqualTo(0);
        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
    }

    @Test
    @DisplayName("publishPendingEvents should publish pending records and mark them as PUBLISHED")
    void testPublishPendingEvents_Success() {
        PaymentEventOutbox record1 = createSampleOutbox("pay-1");
        PaymentEventOutbox record2 = createSampleOutbox("pay-2");

        when(outboxRepository.findPendingEventsForUpdate(anyInt(), any(Instant.class))).thenReturn(List.of(record1, record2));

        CompletableFuture<SendResult<String, String>> future = CompletableFuture.completedFuture(null);
        when(kafkaTemplate.send(messageWithKey("pay-1"))).thenReturn(future);
        when(kafkaTemplate.send(messageWithKey("pay-2"))).thenReturn(future);

        int result = outboxEventPublisher.publishPendingEvents();

        assertThat(result).isEqualTo(2);
        assertThat(record1.getStatus()).isEqualTo(PaymentEventOutboxStatus.PUBLISHED);
        assertThat(record1.getPublishedAt()).isNotNull();
        assertThat(record2.getStatus()).isEqualTo(PaymentEventOutboxStatus.PUBLISHED);
        assertThat(record2.getPublishedAt()).isNotNull();

        verify(outboxRepository).save(record1);
        verify(outboxRepository).save(record2);
    }

    @Test
    @DisplayName("A timeout is scheduled for retry while a newer record is still published")
    void testPublishPendingEvents_KafkaFailure_ContinuesBatch() {
        PaymentEventOutbox record1 = createSampleOutbox("pay-1");
        PaymentEventOutbox record2 = createSampleOutbox("pay-2");

        when(outboxRepository.findPendingEventsForUpdate(anyInt(), any(Instant.class))).thenReturn(List.of(record1, record2));

        CompletableFuture<SendResult<String, String>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new TimeoutException("Kafka broker unreachable"));
        when(kafkaTemplate.send(messageWithKey("pay-1"))).thenReturn(failedFuture);
        when(kafkaTemplate.send(messageWithKey("pay-2"))).thenReturn(CompletableFuture.completedFuture(null));

        int result = outboxEventPublisher.publishPendingEvents();

        assertThat(result).isEqualTo(1);
        assertThat(record1.getStatus()).isEqualTo(PaymentEventOutboxStatus.PENDING);
        assertThat(record1.getRetryCount()).isEqualTo(1);
        assertThat(record1.getLastError()).contains("Kafka broker unreachable");
        assertThat(record1.getNextAttemptAt()).isEqualTo(now.plusSeconds(1));
        assertThat(record1.getLastAttemptAt()).isEqualTo(now);
        verify(outboxRepository).save(record1);

        verify(kafkaTemplate, times(2)).send(any(ProducerRecord.class));
        assertThat(record2.getStatus()).isEqualTo(PaymentEventOutboxStatus.PUBLISHED);
        assertThat(record2.getRetryCount()).isEqualTo(0);
        verify(outboxRepository).save(record2);
    }

    @Test
    void quarantineOversizedRecordButContinue() {
        PaymentEventOutbox oversized = createSampleOutbox("large");
        PaymentEventOutbox healthy = createSampleOutbox("healthy");
        when(outboxRepository.findPendingEventsForUpdate(20, now)).thenReturn(List.of(oversized, healthy));
        when(kafkaTemplate.send(messageWithKey("large"))).thenReturn(CompletableFuture.failedFuture(
                new org.apache.kafka.common.errors.RecordTooLargeException("Too large")));
        when(kafkaTemplate.send(messageWithKey("healthy"))).thenReturn(CompletableFuture.completedFuture(null));
        assertThat(outboxEventPublisher.publishPendingEvents()).isEqualTo(1);
        assertThat(oversized.getStatus()).isEqualTo(PaymentEventOutboxStatus.QUARANTINED);
        assertThat(oversized.getQuarantinedAt()).isEqualTo(now);
        assertThat(oversized.getNextAttemptAt()).isNull();
        assertThat(oversized.getPayload()).isEqualTo("{\"amount\": 1000}");
        assertThat(healthy.getStatus()).isEqualTo(PaymentEventOutboxStatus.PUBLISHED);
    }

    @Test
    void transientFailuresNeverExhaustDeliveryBudgetAndBackoffCannotOverflow() {
        PaymentEventOutbox record = createSampleOutbox("retry");
        record.setRetryCount(Integer.MAX_VALUE);
        when(outboxRepository.findPendingEventsForUpdate(20, now)).thenReturn(List.of(record));
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.failedFuture(
                new org.apache.kafka.common.errors.TopicAuthorizationException("Operator can repair ACL")));
        assertThat(outboxEventPublisher.publishPendingEvents()).isZero();
        assertThat(record.getStatus()).isEqualTo(PaymentEventOutboxStatus.PENDING);
        assertThat(record.getRetryCount()).isEqualTo(Integer.MAX_VALUE);
        assertThat(record.getNextAttemptAt()).isEqualTo(now.plusSeconds(60));
        assertThat(record.getQuarantinedAt()).isNull();
    }

    @Test
    void serializationConfigurationFailureIsNotQuarantined() {
        PaymentEventOutbox record = createSampleOutbox("retry");
        when(outboxRepository.findPendingEventsForUpdate(20, now)).thenReturn(List.of(record));
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenThrow(new org.apache.kafka.common.errors.SerializationException("Bad serializer configuration"));
        assertThat(outboxEventPublisher.publishPendingEvents()).isZero();
        assertThat(record.getStatus()).isEqualTo(PaymentEventOutboxStatus.PENDING);
        assertThat(record.getNextAttemptAt()).isEqualTo(now.plusSeconds(1));
    }

    @Test
    void timeoutDoesNotQuarantineOrCancelFuture() throws Exception {
        PaymentEventOutbox record = createSampleOutbox("ambiguous");
        CompletableFuture<SendResult<String, String>> future = org.mockito.Mockito.mock(CompletableFuture.class);
        when(outboxRepository.findPendingEventsForUpdate(20, now)).thenReturn(List.of(record));
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(future);
        when(future.get(4000, TimeUnit.MILLISECONDS)).thenThrow(new TimeoutException("Outcome unknown"));
        assertThat(outboxEventPublisher.publishPendingEvents()).isZero();
        assertThat(record.getStatus()).isEqualTo(PaymentEventOutboxStatus.PENDING);
        assertThat(record.getPublishedAt()).isNull();
        verify(future, never()).cancel(org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void databaseFailureAfterAckEscapesWithoutRetryClassification() {
        PaymentEventOutbox record = createSampleOutbox("acked");
        record.setHeaders(Map.of("correlationId", "acked-request"));
        MDC.put("traceId", "scheduler-context");
        MDC.put("user", "retained");
        when(outboxRepository.findPendingEventsForUpdate(20, now)).thenReturn(List.of(record));
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
        when(outboxRepository.save(record)).thenAnswer(invocation -> {
            assertThat(MDC.get("traceId")).isEqualTo("acked-request");
            throw new IllegalStateException("DB unavailable");
        });
        assertThatThrownBy(() -> outboxEventPublisher.publishPendingEvents()).hasMessage("DB unavailable");
        assertThat(record.getRetryCount()).isZero();
        assertThat(record.getLastError()).isNull();
        assertThat(record.getStatus()).isEqualTo(PaymentEventOutboxStatus.PUBLISHED);
        assertThat(MDC.get("traceId")).isEqualTo("scheduler-context");
        assertThat(MDC.get("user")).isEqualTo("retained");
    }

    @Test
    void interruptionRestoresFlagAndStopsSending() throws Exception {
        MDC.put("traceId", "scheduler-context");
        PaymentEventOutbox record = createSampleOutbox("interrupted");
        PaymentEventOutbox untouched = createSampleOutbox("untouched");
        CompletableFuture<SendResult<String, String>> future = org.mockito.Mockito.mock(CompletableFuture.class);
        when(outboxRepository.findPendingEventsForUpdate(20, now)).thenReturn(List.of(record, untouched));
        when(kafkaTemplate.send(messageWithKey("interrupted"))).thenReturn(future);
        when(future.get(4000, TimeUnit.MILLISECONDS)).thenThrow(new InterruptedException("Shutdown"));
        try {
            assertThat(outboxEventPublisher.publishPendingEvents()).isZero();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(record.getNextAttemptAt()).isEqualTo(now.plusSeconds(1));
            assertThat(MDC.get("traceId")).isEqualTo("scheduler-context");
            verify(kafkaTemplate, never()).send(messageWithKey("untouched"));
        } finally {
            Thread.interrupted();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"kafka", "future-kafka", "wrapped-kafka", "wrapped-java"})
    void sendInterruptionStopsBeforeTouchingLaterRows(String path) {
        PaymentEventOutbox interrupted = createSampleOutbox("interrupted");
        interrupted.setHeaders(Map.of("correlationId", "interrupted-request"));
        PaymentEventOutbox untouched = createSampleOutbox("untouched");
        Map<String, Object> originalHeaders = Map.of("merchantId", "merchant-1");
        untouched.setHeaders(originalHeaders);
        MDC.put("traceId", "scheduler-context");
        MDC.put("user", "retained");
        when(outboxRepository.findPendingEventsForUpdate(20, now)).thenReturn(List.of(interrupted, untouched));
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenAnswer(invocation -> {
            ProducerRecord<String, String> message = invocation.getArgument(0);
            if (!message.key().equals("interrupted")) return CompletableFuture.completedFuture(null);
            assertThat(MDC.get("traceId")).isEqualTo("interrupted-request");
            if (path.equals("kafka")) {
                throw new org.apache.kafka.common.errors.InterruptException("Metadata wait interrupted");
            }
            Throwable failure = path.equals("wrapped-java")
                    ? new InterruptedException("Acknowledgement wait interrupted")
                    : new org.apache.kafka.common.errors.InterruptException("Metadata wait interrupted");
            if (path.startsWith("wrapped")) {
                failure = new java.util.concurrent.CompletionException(
                        new org.springframework.kafka.KafkaException("Send failed", failure));
            }
            return CompletableFuture.failedFuture(failure);
        });

        try {
            assertThat(outboxEventPublisher.publishPendingEvents()).isZero();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(interrupted.getStatus()).isEqualTo(PaymentEventOutboxStatus.PENDING);
            assertThat(interrupted.getRetryCount()).isEqualTo(1);
            assertThat(interrupted.getLastAttemptAt()).isEqualTo(now);
            assertThat(interrupted.getNextAttemptAt()).isEqualTo(now.plusSeconds(1));
            assertThat(interrupted.getPublishedAt()).isNull();
            assertThat(interrupted.getQuarantinedAt()).isNull();
            assertThat(interrupted.getLastError()).contains(path.equals("wrapped-java")
                    ? "java.lang.InterruptedException" : "org.apache.kafka.common.errors.InterruptException");
            verify(kafkaTemplate, times(1)).send(any(ProducerRecord.class));
            verify(outboxRepository).save(interrupted);
            verify(outboxRepository, never()).save(untouched);
            assertThat(untouched.getHeaders()).isSameAs(originalHeaders);
            assertThat(untouched.getStatus()).isEqualTo(PaymentEventOutboxStatus.PENDING);
            assertThat(untouched.getRetryCount()).isZero();
            assertThat(untouched.getLastAttemptAt()).isNull();
            assertThat(untouched.getNextAttemptAt()).isNull();
            assertThat(untouched.getLastError()).isNull();
            assertThat(untouched.getPublishedAt()).isNull();
            assertThat(MDC.get("traceId")).isEqualTo("scheduler-context");
            assertThat(MDC.get("user")).isEqualTo("retained");
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void rejectsInvalidBackoffConfiguration() {
        org.springframework.test.util.ReflectionTestUtils.setField(outboxEventPublisher, "initialDelayMs", 0L);
        assertThatThrownBy(outboxEventPublisher::validateConfiguration).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void publishesPersistedOrStableLegacyCorrelationWithoutUsingSchedulerMdc() {
        PaymentEventOutbox canonical = createSampleOutbox("canonical");
        canonical.setHeaders(Map.of("correlationId", "original-request", "merchantId", "merchant-1"));
        PaymentEventOutbox alias = createSampleOutbox("alias");
        alias.setHeaders(Map.of("traceId", "legacy-request"));
        PaymentEventOutbox invalid = createSampleOutbox("invalid");
        invalid.setHeaders(Map.of("correlationId", "unsafe value", "traceId", "ignored-alias"));
        PaymentEventOutbox nonString = createSampleOutbox("nonString");
        nonString.setHeaders(Map.of("correlationId", 1234, "traceId", "ignored-alias"));
        PaymentEventOutbox missing = createSampleOutbox("missing");
        PaymentEventOutbox nullHeaders = createSampleOutbox("nullHeaders");
        nullHeaders.setHeaders(null);
        List<PaymentEventOutbox> records = List.of(canonical, alias, invalid, nonString, missing, nullHeaders);
        List<String> expected = List.of("original-request", "legacy-request", invalid.getId().toString(),
                nonString.getId().toString(), missing.getId().toString(), nullHeaders.getId().toString());
        when(outboxRepository.findPendingEventsForUpdate(20, now)).thenReturn(records);
        MDC.put("traceId", "scheduler-context");
        MDC.put("user", "retained");
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenAnswer(invocation -> {
            ProducerRecord<String, String> message = invocation.getArgument(0);
            int index = records.stream().map(PaymentEventOutbox::getAggregateId).toList().indexOf(message.key());
            assertThat(MDC.get("traceId")).isEqualTo(expected.get(index));
            assertThat(MDC.get("user")).isEqualTo("retained");
            assertThat(message.topic()).isEqualTo("kaipay.payment.requests");
            assertThat(message.value()).isEqualTo(records.get(index).getPayload());
            assertThat(message.headers().headers("X-Correlation-Id")).hasSize(1);
            assertThat(new String(message.headers().lastHeader("X-Correlation-Id").value(), StandardCharsets.US_ASCII))
                    .isEqualTo(expected.get(index));
            assertThat(message.headers().lastHeader("merchantId")).isNull();
            return CompletableFuture.completedFuture(null);
        });
        when(outboxRepository.save(any())).thenAnswer(invocation -> {
            PaymentEventOutbox saved = invocation.getArgument(0);
            assertThat(MDC.get("traceId")).isEqualTo(saved.getHeaders().get("correlationId"));
            return saved;
        });

        assertThat(outboxEventPublisher.publishPendingEvents()).isEqualTo(records.size());
        for (int index = 0; index < records.size(); index++) {
            assertThat(records.get(index).getHeaders()).containsEntry("correlationId", expected.get(index));
        }
        assertThat(canonical.getHeaders()).containsEntry("merchantId", "merchant-1");
        assertThat(alias.getHeaders()).containsEntry("traceId", "legacy-request");
        assertThat(MDC.get("traceId")).isEqualTo("scheduler-context");
        assertThat(MDC.get("user")).isEqualTo("retained");
    }

    @Test
    void retryAndQuarantinePersistenceRemainInRowContextAndDoNotLeakAcrossBatch() {
        PaymentEventOutbox retry = createSampleOutbox("retry");
        retry.setHeaders(Map.of("correlationId", "retry-request"));
        PaymentEventOutbox oversized = createSampleOutbox("oversized");
        oversized.setHeaders(Map.of("correlationId", "large-request"));
        when(outboxRepository.findPendingEventsForUpdate(20, now)).thenReturn(List.of(retry, oversized));
        when(kafkaTemplate.send(messageWithKey("retry")))
                .thenReturn(CompletableFuture.failedFuture(new TimeoutException("offline")));
        when(kafkaTemplate.send(messageWithKey("oversized")))
                .thenReturn(CompletableFuture.failedFuture(new org.apache.kafka.common.errors.RecordTooLargeException("large")));
        when(outboxRepository.save(any())).thenAnswer(invocation -> {
            PaymentEventOutbox saved = invocation.getArgument(0);
            assertThat(MDC.get("traceId")).isEqualTo(saved.getHeaders().get("correlationId"));
            return saved;
        });

        assertThat(outboxEventPublisher.publishPendingEvents()).isZero();
        assertThat(retry.getStatus()).isEqualTo(PaymentEventOutboxStatus.PENDING);
        assertThat(oversized.getStatus()).isEqualTo(PaymentEventOutboxStatus.QUARANTINED);
        assertThat(MDC.get("traceId")).isNull();
    }
}
