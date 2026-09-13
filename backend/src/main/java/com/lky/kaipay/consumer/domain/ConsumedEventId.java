package com.lky.kaipay.consumer.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.util.UUID;

@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
@Builder
public class ConsumedEventId implements Serializable {

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "consumer_group", nullable = false, length = 100)
    private String consumerGroup;
}
