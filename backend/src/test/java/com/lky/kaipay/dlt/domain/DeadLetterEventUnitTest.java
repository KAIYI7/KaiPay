package com.lky.kaipay.dlt.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DeadLetterEvent Domain Unit Tests")
class DeadLetterEventUnitTest {

    @Test
    @DisplayName("DeadLetterEvent builder and getters/setters work correctly")
    void testDeadLetterEventProperties() {
        UUID id = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        Instant now = Instant.now();

        DeadLetterEvent event = DeadLetterEvent.builder()
                .id(id)
                .originalTopic("kaipay.payment.requests")
                .originalPartition(2)
                .originalOffset(105L)
                .eventId(eventId)
                .paymentId(paymentId)
                .exceptionClass("com.lky.kaipay.common.exception.GatewayTimeoutException")
                .failureMessage("Gateway read timeout after 3 attempts")
                .retryCount(3)
                .payload("{\"paymentId\":\"" + paymentId + "\"}")
                .headers(Map.of("retry-attempt", "3"))
                .createdAt(now)
                .build();

        assertThat(event.getId()).isEqualTo(id);
        assertThat(event.getOriginalTopic()).isEqualTo("kaipay.payment.requests");
        assertThat(event.getOriginalPartition()).isEqualTo(2);
        assertThat(event.getOriginalOffset()).isEqualTo(105L);
        assertThat(event.getEventId()).isEqualTo(eventId);
        assertThat(event.getPaymentId()).isEqualTo(paymentId);
        assertThat(event.getExceptionClass()).isEqualTo("com.lky.kaipay.common.exception.GatewayTimeoutException");
        assertThat(event.getFailureMessage()).isEqualTo("Gateway read timeout after 3 attempts");
        assertThat(event.getRetryCount()).isEqualTo(3);
        assertThat(event.getPayload()).contains(paymentId.toString());
        assertThat(event.getHeaders()).containsEntry("retry-attempt", "3");
        assertThat(event.getCreatedAt()).isEqualTo(now);

        // Test setters
        event.setRetryCount(4);
        event.setFailureMessage("Updated message");
        assertThat(event.getRetryCount()).isEqualTo(4);
        assertThat(event.getFailureMessage()).isEqualTo("Updated message");
    }

    @Test
    @DisplayName("DeadLetterEvent builder defaults are initialized properly")
    void testDeadLetterEventDefaults() {
        DeadLetterEvent event = DeadLetterEvent.builder()
                .originalTopic("kaipay.payment.requests")
                .originalPartition(0)
                .originalOffset(0L)
                .exceptionClass("java.lang.IllegalArgumentException")
                .payload("{}")
                .build();

        assertThat(event.getRetryCount()).isEqualTo(0);
        assertThat(event.getHeaders()).isNotNull().isEmpty();
    }
}
