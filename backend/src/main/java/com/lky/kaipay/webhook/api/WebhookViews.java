package com.lky.kaipay.webhook.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Explicit browser allowlist: transport configuration and event bytes never cross this boundary. */
public final class WebhookViews {
    private WebhookViews() {}
    public record EndpointView(boolean enabled, UUID endpointId, UUID keyId) {}
    public record DeliveryView(UUID id, UUID eventId, String type, int version, UUID paymentId, UUID refundId,
                               String state, int attempts, Instant nextAttemptAt, Instant occurredAt,
                               Instant createdAt, Instant expiresAt, Instant deliveredAt, String lastResult,
                               UUID endpointId, UUID keyId) {}
    public record AttemptView(int ordinal, Instant startedAt, Instant completedAt, Instant deadline,
                              String result, Integer httpStatus, Long durationMs, UUID keyId) {}
    public record DeliveryDetail(DeliveryView delivery, List<AttemptView> attempts) {}
}
