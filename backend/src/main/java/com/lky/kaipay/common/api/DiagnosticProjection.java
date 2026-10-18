package com.lky.kaipay.common.api;

import com.lky.kaipay.common.observability.CorrelationContext;

import java.util.Map;

/** Browser diagnostics omit free-form evidence; persisted recovery records remain unchanged. */
public final class DiagnosticProjection {
    public static final String REDACTED = "[REDACTED]";

    private DiagnosticProjection() {
    }

    public static String redact(String value) {
        return value == null ? null : REDACTED;
    }

    public static Map<String, Object> correlationHeaders(Map<String, Object> headers) {
        if (headers == null) return Map.of();
        for (String key : new String[] {CorrelationContext.HEADER_NAME, CorrelationContext.OUTBOX_KEY,
                CorrelationContext.MDC_KEY}) {
            Object candidate = headers.get(key);
            if (candidate instanceof String value && CorrelationContext.isValid(value)) {
                return Map.of(CorrelationContext.HEADER_NAME, value);
            }
        }
        return Map.of();
    }
}
