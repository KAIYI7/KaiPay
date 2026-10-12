package com.lky.kaipay.common.observability;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.MDC;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Correlation metadata is bounded, opaque context; it is never an authorization identifier. */
public final class CorrelationContext {

    public static final String HEADER_NAME = "X-Correlation-Id";
    public static final String OUTBOX_KEY = "correlationId";
    public static final String MDC_KEY = "traceId";
    private static final Pattern VALID_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    private CorrelationContext() {
    }

    public static boolean isValid(String value) {
        return value != null && value.length() <= 64 && VALID_ID.matcher(value).matches();
    }

    public static Scope open(String correlationId) {
        if (!isValid(correlationId)) {
            throw new IllegalArgumentException("Invalid correlation identifier");
        }
        return new Scope(correlationId);
    }

    public static Map<String, Object> outboxHeaders(UUID merchantId, UUID eventId) {
        String current = MDC.get(MDC_KEY);
        String effective = isValid(current) ? current : Objects.requireNonNull(eventId).toString();
        return Map.of("merchantId", merchantId.toString(), OUTBOX_KEY, effective);
    }

    /** The last Kafka value wins; malformed or missing metadata is stable for this source record. */
    public static String normalizeKafka(ConsumerRecord<?, ?> record) {
        Header header = record.headers().lastHeader(HEADER_NAME);
        String effective = header == null ? null : decode(header.value());
        if (!isValid(effective)) {
            effective = UUID.nameUUIDFromBytes(("kaipay:" + record.topic() + ":" + record.partition() + ":" + record.offset())
                    .getBytes(StandardCharsets.UTF_8)).toString();
        }
        record.headers().remove(HEADER_NAME);
        record.headers().add(HEADER_NAME, effective.getBytes(StandardCharsets.US_ASCII));
        return effective;
    }

    private static String decode(byte[] value) {
        if (value == null || value.length == 0 || value.length > 64) return null;
        for (byte character : value) {
            if (character < 0) return null;
        }
        return new String(value, StandardCharsets.US_ASCII);
    }

    public static final class Scope implements AutoCloseable {
        private final String previous;
        private boolean closed;

        private Scope(String correlationId) {
            previous = MDC.get(MDC_KEY);
            MDC.put(MDC_KEY, correlationId);
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            if (previous == null) MDC.remove(MDC_KEY);
            else MDC.put(MDC_KEY, previous);
        }
    }
}
