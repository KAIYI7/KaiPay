package com.lky.kaipay.dlt.api.dto;

import com.lky.kaipay.dlt.domain.DeadLetterEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DeadLetterEventResponse DTO Unit Tests")
class DeadLetterEventResponseUnitTest {

    @Test
    @DisplayName("fromEntity accurately maps all fields from DeadLetterEvent")
    void testFromEntityMapping() {
        UUID id = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        Instant createdAt = Instant.now();

        DeadLetterEvent entity = DeadLetterEvent.builder()
                .id(id)
                .originalTopic("kaipay.payment.requests")
                .originalPartition(2)
                .originalOffset(1024L)
                .eventId(eventId)
                .paymentId(paymentId)
                .exceptionClass("com.lky.kaipay.common.exception.GatewayTimeoutException")
                .failureMessage("Connection timed out after 3 retries")
                .retryCount(3)
                .payload("{\"paymentId\":\"" + paymentId + "\",\"amountCents\":5000}")
                .headers(Map.of("correlationId", "corr-999", "retryAttempt", 3))
                .createdAt(createdAt)
                .build();

        DeadLetterEventResponse dto = DeadLetterEventResponse.fromEntity(entity);

        assertThat(dto.getId()).isEqualTo(id);
        assertThat(dto.getOriginalTopic()).isEqualTo("kaipay.payment.requests");
        assertThat(dto.getOriginalPartition()).isEqualTo(2);
        assertThat(dto.getOriginalOffset()).isEqualTo(1024L);
        assertThat(dto.getEventId()).isEqualTo(eventId);
        assertThat(dto.getPaymentId()).isEqualTo(paymentId);
        assertThat(dto.getExceptionClass()).isEqualTo("com.lky.kaipay.common.exception.GatewayTimeoutException");
        assertThat(dto.getFailureMessage()).isEqualTo("Connection timed out after 3 retries");
        assertThat(dto.getRetryCount()).isEqualTo(3);
        assertThat(dto.getPayload()).isEqualTo("{\"paymentId\":\"" + paymentId + "\",\"amountCents\":5000}");
        assertThat(dto.getHeaders()).containsEntry("correlationId", "corr-999");
        assertThat(dto.getHeaders()).containsEntry("retryAttempt", 3);
        assertThat(dto.getCreatedAt()).isEqualTo(createdAt);
    }
}
