package com.lky.kaipay.consumer.service;

import com.lky.kaipay.consumer.domain.ConsumedEvent;
import com.lky.kaipay.consumer.domain.ConsumedEventId;
import com.lky.kaipay.consumer.repository.ConsumedEventRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ConsumerDeduplicationService Unit Tests")
class ConsumerDeduplicationServiceUnitTest {

    @Mock
    private ConsumedEventRepository consumedEventRepository;

    @InjectMocks
    private ConsumerDeduplicationService deduplicationService;

    @Test
    @DisplayName("isEventConsumed returns true when event already exists in repository")
    void testIsEventConsumed_True() {
        UUID eventId = UUID.randomUUID();
        String group = "test-consumer-group";

        when(consumedEventRepository.existsByIdEventIdAndIdConsumerGroup(eventId, group)).thenReturn(true);

        boolean consumed = deduplicationService.isEventConsumed(eventId, group);

        assertThat(consumed).isTrue();
        verify(consumedEventRepository).existsByIdEventIdAndIdConsumerGroup(eventId, group);
    }

    @Test
    @DisplayName("isEventConsumed returns false when event does not exist in repository")
    void testIsEventConsumed_False() {
        UUID eventId = UUID.randomUUID();
        String group = "test-consumer-group";

        when(consumedEventRepository.existsByIdEventIdAndIdConsumerGroup(eventId, group)).thenReturn(false);

        boolean consumed = deduplicationService.isEventConsumed(eventId, group);

        assertThat(consumed).isFalse();
        verify(consumedEventRepository).existsByIdEventIdAndIdConsumerGroup(eventId, group);
    }

    @Test
    @DisplayName("recordConsumed constructs entity and saves to repository")
    void testRecordConsumed() {
        UUID eventId = UUID.randomUUID();
        String group = "test-consumer-group";
        UUID paymentId = UUID.randomUUID();
        String eventType = "PAYMENT_INITIATED";
        String status = "PROCESSED";

        when(consumedEventRepository.save(any(ConsumedEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ConsumedEvent result = deduplicationService.recordConsumed(eventId, group, paymentId, eventType, status);

        ArgumentCaptor<ConsumedEvent> captor = ArgumentCaptor.forClass(ConsumedEvent.class);
        verify(consumedEventRepository).save(captor.capture());

        ConsumedEvent saved = captor.getValue();
        assertThat(saved.getId()).isEqualTo(new ConsumedEventId(eventId, group));
        assertThat(saved.getPaymentId()).isEqualTo(paymentId);
        assertThat(saved.getEventType()).isEqualTo(eventType);
        assertThat(saved.getStatus()).isEqualTo(status);
        assertThat(result).isSameAs(saved);
    }
}
