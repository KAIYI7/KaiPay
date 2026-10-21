package com.lky.kaipay.webhook.delivery;

/** Safe, closed diagnostic vocabulary. Never store receiver or exception text. */
public enum WebhookResult {
    STARTED, ACCEPTED, HTTP_RETRYABLE, HTTP_TERMINAL, IO_ERROR, TIMEOUT,
    TLS_ERROR, CONFIGURATION, UNSAFE_DESTINATION, UNKNOWN, CANCELLED, EXPIRED, EXHAUSTED;

    public boolean retryable() {
        return this == HTTP_RETRYABLE || this == IO_ERROR || this == TIMEOUT || this == UNKNOWN;
    }
    public static WebhookResult http(int status) {
        if (status >= 200 && status < 300) return ACCEPTED;
        return status == 408 || status == 425 || status == 429 || status >= 500 && status <= 599
                ? HTTP_RETRYABLE : HTTP_TERMINAL;
    }
}
