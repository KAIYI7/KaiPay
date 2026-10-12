package com.lky.kaipay.common.observability;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CorrelationContextUnitTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "0", "request.A_0-9", "abcD", "abcd1234"})
    void acceptsOpaqueSafeIdentifiers(String value) {
        assertThat(CorrelationContext.isValid(value)).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"has space", "tab\there", "line\r\nbreak", "é", ".prefix", "_prefix", "-prefix", "one,two"})
    void rejectsUnsafeIdentifiers(String value) {
        assertThat(CorrelationContext.isValid(value)).isFalse();
    }

    @Test
    void boundsLengthAndRestoresNestedContextWithoutClearingUnrelatedMdc() {
        assertThat(CorrelationContext.isValid("a".repeat(64))).isTrue();
        assertThat(CorrelationContext.isValid("a".repeat(65))).isFalse();
        MDC.put("user", "retained");
        try (var outer = CorrelationContext.open("outer")) {
            try (var inner = CorrelationContext.open("inner")) {
                assertThat(MDC.get("traceId")).isEqualTo("inner");
                assertThat(MDC.get("user")).isEqualTo("retained");
            }
            assertThat(MDC.get("traceId")).isEqualTo("outer");
        }
        assertThat(MDC.get("traceId")).isNull();
        assertThat(MDC.get("user")).isEqualTo("retained");
        assertThatThrownBy(() -> CorrelationContext.open("bad\nvalue")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void outboxMetadataUsesValidCurrentContextOrItsOwnStableEventId() {
        UUID merchant = UUID.randomUUID();
        UUID event = UUID.randomUUID();
        assertThat(CorrelationContext.outboxHeaders(merchant, event))
                .containsEntry("merchantId", merchant.toString()).containsEntry("correlationId", event.toString());
        MDC.put("traceId", "unsafe value");
        assertThat(CorrelationContext.outboxHeaders(merchant, event)).containsEntry("correlationId", event.toString());
        MDC.put("traceId", "request-123");
        assertThat(CorrelationContext.outboxHeaders(merchant, event)).containsEntry("correlationId", "request-123");
    }

    @Test
    void kafkaLastHeaderWinsAndDuplicatesAreNormalized() {
        ConsumerRecord<String, String> record = record(42);
        record.headers().add("X-Correlation-Id", "first".getBytes(StandardCharsets.US_ASCII));
        record.headers().add("X-Correlation-Id", "last".getBytes(StandardCharsets.US_ASCII));
        assertThat(CorrelationContext.normalizeKafka(record)).isEqualTo("last");
        assertThat(record.headers().headers("X-Correlation-Id")).hasSize(1);
    }

    @Test
    void missingOrInvalidKafkaMetadataIsDeterministicAndSurvivesForwarding() {
        String fallback = CorrelationContext.normalizeKafka(record(42));
        assertThat(UUID.fromString(fallback)).isNotNull();
        for (byte[] malformed : new byte[][] {null, new byte[0], new byte[] {(byte) 0xff},
                "bad value".getBytes(StandardCharsets.US_ASCII), "a".repeat(65).getBytes(StandardCharsets.US_ASCII)}) {
            ConsumerRecord<String, String> original = record(42);
            original.headers().add("X-Correlation-Id", "valid-first".getBytes(StandardCharsets.US_ASCII));
            original.headers().add("X-Correlation-Id", malformed);
            assertThat(CorrelationContext.normalizeKafka(original)).isEqualTo(fallback);
            ConsumerRecord<String, String> retry = new ConsumerRecord<>("requests-retry", 1, 999, "key", "{}");
            original.headers().forEach(header -> retry.headers().add(header));
            assertThat(CorrelationContext.normalizeKafka(retry)).isEqualTo(fallback);
        }
        assertThat(CorrelationContext.normalizeKafka(record(43))).isNotEqualTo(fallback);
    }

    private ConsumerRecord<String, String> record(long offset) {
        return new ConsumerRecord<>("requests", 1, offset, "key", "{}");
    }
}
