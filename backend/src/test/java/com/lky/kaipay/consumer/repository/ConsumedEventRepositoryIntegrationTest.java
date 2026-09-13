package com.lky.kaipay.consumer.repository;

import com.lky.kaipay.AbstractPostgresIntegrationTest;
import com.lky.kaipay.consumer.domain.ConsumedEvent;
import com.lky.kaipay.consumer.domain.ConsumedEventId;
import com.lky.kaipay.consumer.service.ConsumerDeduplicationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ConsumedEventRepository and ConsumerDeduplicationService Integration Tests")
class ConsumedEventRepositoryIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private ConsumedEventRepository consumedEventRepository;

    @Autowired
    private ConsumerDeduplicationService deduplicationService;

    @BeforeEach
    void setUp() {
        consumedEventRepository.deleteAll();
    }

    @Test
    @Transactional
    @DisplayName("Persist consumed event and verify exists and find queries by composite key")
    void testPersistAndFindByIdQueries() {
        UUID eventId = UUID.randomUUID();
        String consumerGroup = "kaipay-payment-processor-group";
        UUID paymentId = UUID.randomUUID();

        ConsumedEvent event = ConsumedEvent.builder()
                .id(ConsumedEventId.builder().eventId(eventId).consumerGroup(consumerGroup).build())
                .paymentId(paymentId)
                .eventType("PAYMENT_INITIATED")
                .status("PROCESSED")
                .build();

        consumedEventRepository.save(event);

        boolean exists = consumedEventRepository.existsByIdEventIdAndIdConsumerGroup(eventId, consumerGroup);
        assertThat(exists).isTrue();

        boolean notExistsOtherGroup = consumedEventRepository.existsByIdEventIdAndIdConsumerGroup(eventId, "other-group");
        assertThat(notExistsOtherGroup).isFalse();

        boolean notExistsOtherEvent = consumedEventRepository.existsByIdEventIdAndIdConsumerGroup(UUID.randomUUID(), consumerGroup);
        assertThat(notExistsOtherEvent).isFalse();

        Optional<ConsumedEvent> found = consumedEventRepository.findByIdEventIdAndIdConsumerGroup(eventId, consumerGroup);
        assertThat(found).isPresent();
        assertThat(found.get().getPaymentId()).isEqualTo(paymentId);
        assertThat(found.get().getEventType()).isEqualTo("PAYMENT_INITIATED");
        assertThat(found.get().getStatus()).isEqualTo("PROCESSED");
        assertThat(found.get().getProcessedAt()).isNotNull();
    }

    @Test
    @DisplayName("findByPaymentIdOrderByProcessedAtAsc returns records in chronological order")
    void testFindByPaymentIdOrderByProcessedAtAsc() throws InterruptedException {
        UUID paymentId = UUID.randomUUID();
        String group = "kaipay-payment-processor-group";

        UUID eventId1 = UUID.randomUUID();
        UUID eventId2 = UUID.randomUUID();

        ConsumedEvent event1 = ConsumedEvent.builder()
                .id(new ConsumedEventId(eventId1, group))
                .paymentId(paymentId)
                .eventType("PAYMENT_INITIATED")
                .status("PROCESSED")
                .build();
        consumedEventRepository.save(event1);

        // Small delay to ensure timestamp separation
        Thread.sleep(50);

        ConsumedEvent event2 = ConsumedEvent.builder()
                .id(new ConsumedEventId(eventId2, group))
                .paymentId(paymentId)
                .eventType("PAYMENT_AUTHORIZED")
                .status("PROCESSED")
                .build();
        consumedEventRepository.save(event2);

        List<ConsumedEvent> events = consumedEventRepository.findByPaymentIdOrderByProcessedAtAsc(paymentId);

        assertThat(events).hasSize(2);
        assertThat(events.get(0).getId().getEventId()).isEqualTo(eventId1);
        assertThat(events.get(0).getEventType()).isEqualTo("PAYMENT_INITIATED");
        assertThat(events.get(1).getId().getEventId()).isEqualTo(eventId2);
        assertThat(events.get(1).getEventType()).isEqualTo("PAYMENT_AUTHORIZED");
        assertThat(events.get(0).getProcessedAt()).isBeforeOrEqualTo(events.get(1).getProcessedAt());
    }

    @Test
    @DisplayName("ConsumerDeduplicationService recordConsumed and isEventConsumed work end-to-end")
    void testDeduplicationServiceIntegration() {
        UUID eventId = UUID.randomUUID();
        String group = "kaipay-notification-group";
        UUID paymentId = UUID.randomUUID();

        assertThat(deduplicationService.isEventConsumed(eventId, group)).isFalse();

        ConsumedEvent recorded = deduplicationService.recordConsumed(
                eventId,
                group,
                paymentId,
                "PAYMENT_CAPTURED",
                "SUCCESS"
        );

        assertThat(recorded).isNotNull();
        assertThat(recorded.getId().getEventId()).isEqualTo(eventId);
        assertThat(recorded.getId().getConsumerGroup()).isEqualTo(group);
        assertThat(recorded.getPaymentId()).isEqualTo(paymentId);
        assertThat(recorded.getEventType()).isEqualTo("PAYMENT_CAPTURED");
        assertThat(recorded.getStatus()).isEqualTo("SUCCESS");

        assertThat(deduplicationService.isEventConsumed(eventId, group)).isTrue();
    }
}
