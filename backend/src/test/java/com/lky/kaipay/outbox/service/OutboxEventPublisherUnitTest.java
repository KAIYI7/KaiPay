package com.lky.kaipay.outbox.service;

import com.lky.kaipay.outbox.domain.PaymentEventOutbox;
import com.lky.kaipay.outbox.domain.PaymentEventOutboxStatus;
import com.lky.kaipay.outbox.repository.PaymentEventOutboxRepository;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
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

    @InjectMocks
    private OutboxEventPublisher outboxEventPublisher;

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
        when(outboxRepository.findPendingEventsForUpdate(anyInt())).thenReturn(Collections.emptyList());

        int result = outboxEventPublisher.publishPendingEvents();

        assertThat(result).isEqualTo(0);
        verify(kafkaTemplate, never()).send(any(), any(), any());
    }

    @Test
    @DisplayName("publishPendingEvents should publish pending records and mark them as PUBLISHED")
    void testPublishPendingEvents_Success() {
        PaymentEventOutbox record1 = createSampleOutbox("pay-1");
        PaymentEventOutbox record2 = createSampleOutbox("pay-2");

        when(outboxRepository.findPendingEventsForUpdate(anyInt())).thenReturn(List.of(record1, record2));

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
    @DisplayName("publishPendingEvents should record error and break batch when Kafka send fails")
    void testPublishPendingEvents_KafkaFailure_BreaksBatch() {
        PaymentEventOutbox record1 = createSampleOutbox("pay-1");
        PaymentEventOutbox record2 = createSampleOutbox("pay-2");

        when(outboxRepository.findPendingEventsForUpdate(anyInt())).thenReturn(List.of(record1, record2));

        CompletableFuture<SendResult<String, String>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new TimeoutException("Kafka broker unreachable"));
        when(kafkaTemplate.send(eq("kaipay.payment.requests"), eq("pay-1"), eq("{\"amount\": 1000}"))).thenReturn(failedFuture);

        int result = outboxEventPublisher.publishPendingEvents();

        assertThat(result).isEqualTo(0);
        assertThat(record1.getStatus()).isEqualTo(PaymentEventOutboxStatus.PENDING);
        assertThat(record1.getRetryCount()).isEqualTo(1);
        assertThat(record1.getLastError()).contains("Kafka broker unreachable");
        verify(outboxRepository).save(record1);

        // Second record must not be processed due to break
        verify(kafkaTemplate, times(1)).send(any(), any(), any());
        assertThat(record2.getStatus()).isEqualTo(PaymentEventOutboxStatus.PENDING);
        assertThat(record2.getRetryCount()).isEqualTo(0);
        verify(outboxRepository, never()).save(record2);
    }
}
