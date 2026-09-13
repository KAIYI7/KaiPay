package com.lky.kaipay.outbox.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.lky.kaipay.outbox.domain.PaymentEventOutbox;
import com.lky.kaipay.outbox.domain.PaymentEventOutboxStatus;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OutboxEventResponse {
    private final UUID id;
    private final String aggregateType;
    private final String aggregateId;
    private final String eventType;
    private final String payload;
    private final Map<String, Object> headers;
    private final PaymentEventOutboxStatus status;
    private final int retryCount;
    private final String lastError;
    private final Instant createdAt;
    private final Instant publishedAt;

    public static OutboxEventResponse fromEntity(PaymentEventOutbox outbox) {
        return OutboxEventResponse.builder()
                .id(outbox.getId())
                .aggregateType(outbox.getAggregateType())
                .aggregateId(outbox.getAggregateId())
                .eventType(outbox.getEventType())
                .payload(outbox.getPayload())
                .headers(outbox.getHeaders())
                .status(outbox.getStatus())
                .retryCount(outbox.getRetryCount())
                .lastError(outbox.getLastError())
                .createdAt(outbox.getCreatedAt())
                .publishedAt(outbox.getPublishedAt())
                .build();
    }
}
