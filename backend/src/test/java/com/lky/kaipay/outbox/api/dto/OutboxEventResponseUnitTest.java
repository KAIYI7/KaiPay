package com.lky.kaipay.outbox.api.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lky.kaipay.outbox.domain.PaymentEventOutbox;
import com.lky.kaipay.outbox.domain.PaymentEventOutboxStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OutboxEventResponse DTO Unit Tests")
class OutboxEventResponseUnitTest {

    @Test
    @DisplayName("fromEntity retains operational metadata while omitting browser evidence")
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
                .lastAttemptAt(publishedAt.minusSeconds(2))
                .nextAttemptAt(publishedAt.plusSeconds(2))
                .quarantinedAt(publishedAt.minusSeconds(3))
                .build();

        OutboxEventResponse dto = OutboxEventResponse.fromEntity(entity);

        assertThat(dto.getId()).isEqualTo(id);
        assertThat(dto.getAggregateType()).isEqualTo("PAYMENT");
        assertThat(dto.getAggregateId()).isEqualTo(aggregateId);
        assertThat(dto.getEventType()).isEqualTo("PaymentInitiatedEvent");
        assertThat(dto.getPayload()).isEqualTo("[REDACTED]");
        assertThat(dto.getHeaders()).containsExactly(Map.entry("X-Correlation-Id", "tr-123"));
        assertThat(dto.getStatus()).isEqualTo(PaymentEventOutboxStatus.PUBLISHED);
        assertThat(dto.getRetryCount()).isEqualTo(2);
        assertThat(dto.getLastError()).isEqualTo("[REDACTED]");
        assertThat(dto.getPublishedAt()).isEqualTo(publishedAt);
        assertThat(dto.getLastAttemptAt()).isEqualTo(publishedAt.minusSeconds(2));
        assertThat(dto.getNextAttemptAt()).isEqualTo(publishedAt.plusSeconds(2));
        assertThat(dto.getQuarantinedAt()).isEqualTo(publishedAt.minusSeconds(3));
    }

    @Test
    void hostileEvidenceNeverReachesSerializedResponseOrMutatesSource() throws Exception {
        String marker = "private-customer-contact-token";
        Map<String, Object> headers = new HashMap<>(Map.of("Authorization", marker,
                "unexpected", Map.of("nested", marker), "correlationId", "outbox-correlation"));
        PaymentEventOutbox entity = PaymentEventOutbox.builder().payload("{\"unexpected\":\"" + marker + "\"}")
                .headers(headers).lastError("SQL constraint failed: " + marker).build();

        OutboxEventResponse response = OutboxEventResponse.fromEntity(entity);

        assertThat(new ObjectMapper().findAndRegisterModules().writeValueAsString(response)).doesNotContain(marker);
        assertThat(response.getHeaders()).containsExactly(Map.entry("X-Correlation-Id", "outbox-correlation"));
        assertThat(entity.getPayload()).contains(marker);
        assertThat(entity.getLastError()).contains(marker);
        assertThat(entity.getHeaders()).isSameAs(headers).containsKey("Authorization");
        assertThat(response.getHeaders()).isNotSameAs(headers);
    }

    @Test
    void nullEvidenceStaysNullAndInvalidCorrelationIsOmitted() {
        PaymentEventOutbox entity = PaymentEventOutbox.builder().payload(null).lastError(null)
                .headers(Map.of("correlationId", "bad\r\nsecret", "traceId", "x".repeat(65))).build();
        OutboxEventResponse response = OutboxEventResponse.fromEntity(entity);
        assertThat(response.getPayload()).isNull();
        assertThat(response.getLastError()).isNull();
        assertThat(response.getHeaders()).isEmpty();
        entity.setHeaders(null);
        assertThat(OutboxEventResponse.fromEntity(entity).getHeaders()).isEmpty();
    }
}
