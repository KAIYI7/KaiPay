package com.lky.kaipay.common.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
@EqualsAndHashCode
public class EventEnvelope<T> {

    @Builder.Default
    private UUID eventId = UUID.randomUUID();

    private String eventType;

    private String aggregateType;

    private String aggregateId;

    private UUID merchantId;

    @Builder.Default
    private Instant timestamp = Instant.now();

    @Builder.Default
    private int version = 1;

    private T payload;

    public static <T> EventEnvelope<T> of(
            String eventType,
            String aggregateType,
            String aggregateId,
            UUID merchantId,
            T payload
    ) {
        return EventEnvelope.<T>builder()
                .eventId(UUID.randomUUID())
                .eventType(eventType)
                .aggregateType(aggregateType)
                .aggregateId(aggregateId)
                .merchantId(merchantId)
                .timestamp(Instant.now())
                .version(1)
                .payload(payload)
                .build();
    }
}
