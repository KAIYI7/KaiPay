package com.lky.kaipay.dlt.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.lky.kaipay.dlt.domain.DeadLetterEvent;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DeadLetterEventResponse {
    private final UUID id;
    private final String originalTopic;
    private final int originalPartition;
    private final long originalOffset;
    private final UUID eventId;
    private final UUID paymentId;
    private final String exceptionClass;
    private final String failureMessage;
    private final int retryCount;
    private final String payload;
    private final Map<String, Object> headers;
    private final Instant createdAt;

    public static DeadLetterEventResponse fromEntity(DeadLetterEvent entity) {
        return DeadLetterEventResponse.builder()
                .id(entity.getId())
                .originalTopic(entity.getOriginalTopic())
                .originalPartition(entity.getOriginalPartition())
                .originalOffset(entity.getOriginalOffset())
                .eventId(entity.getEventId())
                .paymentId(entity.getPaymentId())
                .exceptionClass(entity.getExceptionClass())
                .failureMessage(entity.getFailureMessage())
                .retryCount(entity.getRetryCount())
                .payload(entity.getPayload())
                .headers(entity.getHeaders())
                .createdAt(entity.getCreatedAt())
                .build();
    }
}
