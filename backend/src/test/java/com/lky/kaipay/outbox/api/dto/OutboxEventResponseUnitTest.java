package com.lky.kaipay.outbox.api.dto;

import com.lky.kaipay.outbox.domain.PaymentEventOutbox;
import com.lky.kaipay.outbox.domain.PaymentEventOutboxStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OutboxEventResponse DTO Unit Tests")
class OutboxEventResponseUnitTest {

    @Test
    @DisplayName("fromEntity accurately maps all fields from PaymentEventOutbox")
    void testFromEntityMapping() {
        UUID id = UUID.randomUUID();
        String aggregateId = UUID.randomUUID().toString();
        Instant publishedAt = Instant.now();

        PaymentEventOutbox entity = PaymentEventOutbox.builder()
                .id(id)
                .aggregateType("PAYMENT")
                .aggregateId(aggregateId)
                .eventType("PaymentInitiatedEvent")
                .payload("{\"key\":\"value\"}")
                .headers(Map.of("traceId", "tr-123"))
                .status(PaymentEventOutboxStatus.PUBLISHED)
                .retryCount(2)
                .lastError("Previous timeout")
                .publishedAt(publishedAt)
                .build();

        OutboxEventResponse dto = OutboxEventResponse.fromEntity(entity);

        assertThat(dto.getId()).isEqualTo(id);
        assertThat(dto.getAggregateType()).isEqualTo("PAYMENT");
        assertThat(dto.getAggregateId()).isEqualTo(aggregateId);
        assertThat(dto.getEventType()).isEqualTo("PaymentInitiatedEvent");
        assertThat(dto.getPayload()).isEqualTo("{\"key\":\"value\"}");
        assertThat(dto.getHeaders()).containsEntry("traceId", "tr-123");
        assertThat(dto.getStatus()).isEqualTo(PaymentEventOutboxStatus.PUBLISHED);
        assertThat(dto.getRetryCount()).isEqualTo(2);
        assertThat(dto.getLastError()).isEqualTo("Previous timeout");
        assertThat(dto.getPublishedAt()).isEqualTo(publishedAt);
    }
}
