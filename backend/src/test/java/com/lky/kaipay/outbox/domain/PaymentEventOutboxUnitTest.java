package com.lky.kaipay.outbox.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PaymentEventOutbox Domain Unit Tests")
class PaymentEventOutboxUnitTest {

    @Test
    @DisplayName("Outbox entity creates with PENDING status and default values")
    void defaultValues() {
        PaymentEventOutbox outbox = PaymentEventOutbox.builder()
                .aggregateType("PAYMENT")
                .aggregateId(UUID.randomUUID().toString())
                .eventType("PAYMENT_INITIATED")
                .payload("{\"paymentId\":\"123\"}")
                .headers(Map.of("correlationId", "corr-1"))
                .build();

        assertThat(outbox.getStatus()).isEqualTo(PaymentEventOutboxStatus.PENDING);
        assertThat(outbox.getRetryCount()).isEqualTo(0);
        assertThat(outbox.getLastError()).isNull();
        assertThat(outbox.getPublishedAt()).isNull();
        assertThat(outbox.getHeaders()).containsEntry("correlationId", "corr-1");
    }

    @Test
    @DisplayName("markPublished updates status to PUBLISHED and sets publishedAt timestamp")
    void markPublished() {
        Instant before = Instant.now().minusSeconds(1);

        PaymentEventOutbox outbox = PaymentEventOutbox.builder()
                .aggregateType("PAYMENT")
                .aggregateId(UUID.randomUUID().toString())
                .eventType("PAYMENT_INITIATED")
                .payload("{\"paymentId\":\"123\"}")
                .build();

        outbox.markPublished();

        assertThat(outbox.getStatus()).isEqualTo(PaymentEventOutboxStatus.PUBLISHED);
        assertThat(outbox.getPublishedAt()).isNotNull();
        assertThat(outbox.getPublishedAt()).isAfterOrEqualTo(before);
    }

    @Test
    @DisplayName("recordError increments retryCount and sets lastError")
    void recordError() {
        PaymentEventOutbox outbox = PaymentEventOutbox.builder()
                .aggregateType("PAYMENT")
                .aggregateId(UUID.randomUUID().toString())
                .eventType("PAYMENT_INITIATED")
                .payload("{\"paymentId\":\"123\"}")
                .build();

        outbox.recordError("Kafka broker unreachable");
        assertThat(outbox.getRetryCount()).isEqualTo(1);
        assertThat(outbox.getLastError()).isEqualTo("Kafka broker unreachable");

        outbox.recordError("Schema registry timeout");
        assertThat(outbox.getRetryCount()).isEqualTo(2);
        assertThat(outbox.getLastError()).isEqualTo("Schema registry timeout");
    }
}
