package com.lky.kaipay.dlt.api.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lky.kaipay.dlt.domain.DeadLetterEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DeadLetterEventResponseUnitTest {
    @Test
    void hostileEvidenceIsOmittedWithoutMutatingDurableRecord() throws Exception {
        String marker = "private-customer-contact-token";
        UUID id = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-10-18T00:00:00Z");
        Map<String, Object> headers = new HashMap<>(Map.of("X-Correlation-Id", "dlt-correlation",
                "exception-stacktrace", marker, "Authorization", marker, "unknown", Map.of("nested", marker)));
        DeadLetterEvent entity = DeadLetterEvent.builder().id(id).eventId(eventId).paymentId(paymentId)
                .originalTopic("kaipay.payment.requests-dlt").originalPartition(2).originalOffset(42)
                .retryCount(3).createdAt(createdAt).payload("malformed poison pill " + marker)
                .exceptionClass("apparently.valid." + marker).failureMessage("SQL: " + marker).headers(headers).build();

        DeadLetterEventResponse response = DeadLetterEventResponse.fromEntity(entity);

        assertThat(new ObjectMapper().findAndRegisterModules().writeValueAsString(response)).doesNotContain(marker);
        assertThat(response.getId()).isEqualTo(id);
        assertThat(response.getEventId()).isEqualTo(eventId);
        assertThat(response.getPaymentId()).isEqualTo(paymentId);
        assertThat(response.getOriginalTopic()).isEqualTo(entity.getOriginalTopic());
        assertThat(response.getOriginalPartition()).isEqualTo(2);
        assertThat(response.getOriginalOffset()).isEqualTo(42);
        assertThat(response.getRetryCount()).isEqualTo(3);
        assertThat(response.getCreatedAt()).isEqualTo(createdAt);
        assertThat(response.getPayload()).isEqualTo("[REDACTED]");
        assertThat(response.getFailureMessage()).isEqualTo("[REDACTED]");
        assertThat(response.getExceptionClass()).isEqualTo("[REDACTED]");
        assertThat(response.getHeaders()).containsExactly(Map.entry("X-Correlation-Id", "dlt-correlation"));
        assertThat(entity.getPayload()).contains(marker);
        assertThat(entity.getFailureMessage()).contains(marker);
        assertThat(entity.getExceptionClass()).contains(marker);
        assertThat(entity.getHeaders()).isSameAs(headers).containsKey("Authorization");
        assertThat(response.getHeaders()).isNotSameAs(headers);
    }

    @Test
    void nullEvidenceStaysNullAndOnlyValidatedTextCorrelationIsExposed() {
        DeadLetterEvent entity = DeadLetterEvent.builder().headers(Map.of("X-Correlation-Id", 123,
                "correlationId", "bad\nvalue", "traceId", "x".repeat(65))).build();
        DeadLetterEventResponse response = DeadLetterEventResponse.fromEntity(entity);
        assertThat(response.getPayload()).isNull();
        assertThat(response.getFailureMessage()).isNull();
        assertThat(response.getExceptionClass()).isNull();
        assertThat(response.getHeaders()).isEmpty();
        entity.setHeaders(null);
        assertThat(DeadLetterEventResponse.fromEntity(entity).getHeaders()).isEmpty();
    }

    @Test
    void emptyEvidenceIsStillRedactedAndLegacyCorrelationHasStableCanonicalName() {
        DeadLetterEvent entity = DeadLetterEvent.builder().payload("").failureMessage("").exceptionClass("")
                .headers(Map.of("traceId", "legacy-correlation")).build();
        DeadLetterEventResponse response = DeadLetterEventResponse.fromEntity(entity);
        assertThat(response.getPayload()).isEqualTo("[REDACTED]");
        assertThat(response.getFailureMessage()).isEqualTo("[REDACTED]");
        assertThat(response.getExceptionClass()).isEqualTo("[REDACTED]");
        assertThat(response.getHeaders()).containsExactly(Map.entry("X-Correlation-Id", "legacy-correlation"));
    }
}
