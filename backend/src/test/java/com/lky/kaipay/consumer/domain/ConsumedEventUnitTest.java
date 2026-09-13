package com.lky.kaipay.consumer.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ConsumedEvent and ConsumedEventId Domain Unit Tests")
class ConsumedEventUnitTest {

    @Test
    @DisplayName("ConsumedEventId builder and getters/setters work correctly")
    void testConsumedEventIdProperties() {
        UUID eventId = UUID.randomUUID();
        String group = "kaipay-payment-processor-group";

        ConsumedEventId id = ConsumedEventId.builder()
                .eventId(eventId)
                .consumerGroup(group)
                .build();

        assertThat(id.getEventId()).isEqualTo(eventId);
        assertThat(id.getConsumerGroup()).isEqualTo(group);

        UUID newEventId = UUID.randomUUID();
        id.setEventId(newEventId);
        id.setConsumerGroup("new-group");

        assertThat(id.getEventId()).isEqualTo(newEventId);
        assertThat(id.getConsumerGroup()).isEqualTo("new-group");
    }

    @Test
    @DisplayName("ConsumedEventId equals and hashCode contract")
    void testConsumedEventIdEqualsAndHashCode() {
        UUID eventId1 = UUID.randomUUID();
        UUID eventId2 = UUID.randomUUID();
        String group1 = "group-1";
        String group2 = "group-2";

        ConsumedEventId id1 = new ConsumedEventId(eventId1, group1);
        ConsumedEventId id1Copy = new ConsumedEventId(eventId1, group1);
        ConsumedEventId id2 = new ConsumedEventId(eventId1, group2);
        ConsumedEventId id3 = new ConsumedEventId(eventId2, group1);

        assertThat(id1).isEqualTo(id1Copy);
        assertThat(id1.hashCode()).isEqualTo(id1Copy.hashCode());

        assertThat(id1).isNotEqualTo(id2);
        assertThat(id1).isNotEqualTo(id3);
        assertThat(id1).isNotEqualTo(null);
        assertThat(id1).isNotEqualTo("some-string");
    }

    @Test
    @DisplayName("ConsumedEvent builder and getters/setters work correctly")
    void testConsumedEventProperties() {
        UUID eventId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        ConsumedEventId id = new ConsumedEventId(eventId, "group-a");
        Instant now = Instant.now();

        ConsumedEvent event = ConsumedEvent.builder()
                .id(id)
                .paymentId(paymentId)
                .eventType("PAYMENT_INITIATED")
                .status("PROCESSED")
                .processedAt(now)
                .build();

        assertThat(event.getId()).isEqualTo(id);
        assertThat(event.getPaymentId()).isEqualTo(paymentId);
        assertThat(event.getEventType()).isEqualTo("PAYMENT_INITIATED");
        assertThat(event.getStatus()).isEqualTo("PROCESSED");
        assertThat(event.getProcessedAt()).isEqualTo(now);

        UUID newPaymentId = UUID.randomUUID();
        event.setPaymentId(newPaymentId);
        event.setStatus("DUPLICATE_SKIPPED");
        event.setEventType("PAYMENT_AUTHORIZED");

        assertThat(event.getPaymentId()).isEqualTo(newPaymentId);
        assertThat(event.getStatus()).isEqualTo("DUPLICATE_SKIPPED");
        assertThat(event.getEventType()).isEqualTo("PAYMENT_AUTHORIZED");
    }
}
