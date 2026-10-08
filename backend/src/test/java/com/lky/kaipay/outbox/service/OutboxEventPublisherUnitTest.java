package com.lky.kaipay.outbox.service;

import com.lky.kaipay.outbox.domain.PaymentEventOutbox;
import com.lky.kaipay.outbox.domain.PaymentEventOutboxStatus;
import com.lky.kaipay.outbox.repository.PaymentEventOutboxRepository;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.Collections;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
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
        verify(kafkaTemplate, never()).send(any(), any(), any());
    }

    @Test
    @DisplayName("publishPendingEvents should publish pending records and mark them as PUBLISHED")
    void testPublishPendingEvents_Success() {
        PaymentEventOutbox record1 = createSampleOutbox("pay-1");
        PaymentEventOutbox record2 = createSampleOutbox("pay-2");

        when(outboxRepository.findPendingEventsForUpdate(anyInt(), any(Instant.class))).thenReturn(List.of(record1, record2));

        CompletableFuture<SendResult<String, String>> future = CompletableFuture.completedFuture(null);
        when(kafkaTemplate.send(eq("kaipay.payment.requests"), eq("pay-1"), eq("{\"amount\": 1000}"))).thenReturn(future);
        when(kafkaTemplate.send(eq("kaipay.payment.requests"), eq("pay-2"), eq("{\"amount\": 1000}"))).thenReturn(future);

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
        when(kafkaTemplate.send(eq("kaipay.payment.requests"), eq("pay-1"), eq("{\"amount\": 1000}"))).thenReturn(failedFuture);
        when(kafkaTemplate.send(eq("kaipay.payment.requests"), eq("pay-2"), any())).thenReturn(CompletableFuture.completedFuture(null));

        int result = outboxEventPublisher.publishPendingEvents();

        assertThat(result).isEqualTo(1);
        assertThat(record1.getStatus()).isEqualTo(PaymentEventOutboxStatus.PENDING);
        assertThat(record1.getRetryCount()).isEqualTo(1);
        assertThat(record1.getLastError()).contains("Kafka broker unreachable");
        assertThat(record1.getNextAttemptAt()).isEqualTo(now.plusSeconds(1));
        assertThat(record1.getLastAttemptAt()).isEqualTo(now);
        verify(outboxRepository).save(record1);

        verify(kafkaTemplate, times(2)).send(any(), any(), any());
        assertThat(record2.getStatus()).isEqualTo(PaymentEventOutboxStatus.PUBLISHED);
        assertThat(record2.getRetryCount()).isEqualTo(0);
        verify(outboxRepository).save(record2);
    }

    @Test
    void quarantineOversizedRecordButContinue() {
        PaymentEventOutbox oversized = createSampleOutbox("large");
        PaymentEventOutbox healthy = createSampleOutbox("healthy");
        when(outboxRepository.findPendingEventsForUpdate(20, now)).thenReturn(List.of(oversized, healthy));
        when(kafkaTemplate.send(any(), eq("large"), any())).thenReturn(CompletableFuture.failedFuture(
                new org.apache.kafka.common.errors.RecordTooLargeException("Too large")));
        when(kafkaTemplate.send(any(), eq("healthy"), any())).thenReturn(CompletableFuture.completedFuture(null));
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
        when(kafkaTemplate.send(any(), any(), any())).thenReturn(CompletableFuture.failedFuture(
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
        when(kafkaTemplate.send(any(), any(), any())).thenThrow(new org.apache.kafka.common.errors.SerializationException("Bad serializer configuration"));
        assertThat(outboxEventPublisher.publishPendingEvents()).isZero();
        assertThat(record.getStatus()).isEqualTo(PaymentEventOutboxStatus.PENDING);
        assertThat(record.getNextAttemptAt()).isEqualTo(now.plusSeconds(1));
    }

    @Test
    void timeoutDoesNotQuarantineOrCancelFuture() throws Exception {
        PaymentEventOutbox record = createSampleOutbox("ambiguous");
        CompletableFuture<SendResult<String, String>> future = org.mockito.Mockito.mock(CompletableFuture.class);
        when(outboxRepository.findPendingEventsForUpdate(20, now)).thenReturn(List.of(record));
        when(kafkaTemplate.send(any(), any(), any())).thenReturn(future);
        when(future.get(4000, TimeUnit.MILLISECONDS)).thenThrow(new TimeoutException("Outcome unknown"));
        assertThat(outboxEventPublisher.publishPendingEvents()).isZero();
        assertThat(record.getStatus()).isEqualTo(PaymentEventOutboxStatus.PENDING);
        assertThat(record.getPublishedAt()).isNull();
        verify(future, never()).cancel(org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void databaseFailureAfterAckEscapesWithoutRetryClassification() {
        PaymentEventOutbox record = createSampleOutbox("acked");
        when(outboxRepository.findPendingEventsForUpdate(20, now)).thenReturn(List.of(record));
        when(kafkaTemplate.send(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(null));
        when(outboxRepository.save(record)).thenThrow(new IllegalStateException("DB unavailable"));
        assertThatThrownBy(() -> outboxEventPublisher.publishPendingEvents()).hasMessage("DB unavailable");
        assertThat(record.getRetryCount()).isZero();
        assertThat(record.getLastError()).isNull();
        assertThat(record.getStatus()).isEqualTo(PaymentEventOutboxStatus.PUBLISHED);
    }

    @Test
    void interruptionRestoresFlagAndStopsSending() throws Exception {
        PaymentEventOutbox record = createSampleOutbox("interrupted");
        PaymentEventOutbox untouched = createSampleOutbox("untouched");
        CompletableFuture<SendResult<String, String>> future = org.mockito.Mockito.mock(CompletableFuture.class);
        when(outboxRepository.findPendingEventsForUpdate(20, now)).thenReturn(List.of(record, untouched));
        when(kafkaTemplate.send(any(), eq("interrupted"), any())).thenReturn(future);
        when(future.get(4000, TimeUnit.MILLISECONDS)).thenThrow(new InterruptedException("Shutdown"));
        try {
            assertThat(outboxEventPublisher.publishPendingEvents()).isZero();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(record.getNextAttemptAt()).isEqualTo(now.plusSeconds(1));
            verify(kafkaTemplate, never()).send(any(), eq("untouched"), any());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void rejectsInvalidBackoffConfiguration() {
        org.springframework.test.util.ReflectionTestUtils.setField(outboxEventPublisher, "initialDelayMs", 0L);
        assertThatThrownBy(outboxEventPublisher::validateConfiguration).isInstanceOf(IllegalArgumentException.class);
    }
}
