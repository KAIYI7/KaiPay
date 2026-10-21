package com.lky.kaipay.webhook.delivery;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record WebhookClaim(UUID id, UUID eventId, UUID merchantId, UUID endpointId, UUID attemptId,
                           int ordinal, Instant expiresAt, Instant deadline, byte[] payload,
                           String url, String hostname, String path, List<String> pins, UUID keyId, String secretRef) {
    public WebhookClaim { payload = payload.clone(); pins = List.copyOf(pins); }
    @Override public byte[] payload() { return payload.clone(); }
    @Override public String toString() { return "WebhookClaim[" + id + "]"; }
}
