package com.lky.kaipay.payment.service;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Validated transport metadata. Payment storage remains authoritative for authorization. */
public record AuthorizationDelivery(UUID eventId, UUID paymentId, long amountCents, String currency,
                                    UUID merchantId, UUID customerId, String idempotencyKey,
                                    UUID envelopeMerchantId, UUID aggregateId, String aggregateType) {
    public static final String EVENT_TYPE = "PaymentInitiatedEvent";

    public static AuthorizationDelivery fromJson(JsonNode root) {
        require(root != null && root.isObject());
        JsonNode payload = root.has("payload") ? root.get("payload") : root;
        require(payload != null && payload.isObject());
        if (root.has("version")) {
            JsonNode version = root.get("version");
            require(version.isIntegralNumber() && version.canConvertToInt() && version.intValue() == 1);
        }
        if (root.has("eventType")) require(EVENT_TYPE.equals(text(root, "eventType")));
        UUID payment = uuid(payload, "paymentId", true);
        UUID event = root.has("eventId") ? uuid(root, "eventId", true)
                : UUID.nameUUIDFromBytes(("kaipay:legacy-initiation:v1:" + payment).getBytes(StandardCharsets.UTF_8));
        JsonNode amount = payload.get("amountCents");
        require(amount != null && amount.isIntegralNumber() && amount.canConvertToLong() && amount.longValue() > 0);
        // Preserve the pre-MYR legacy transport meaning; never relabel an old delivery as MYR.
        // Storage/evidence validation decides eligibility; all new events include currency.
        String currency = payload.has("currency") ? text(payload, "currency") : "USD";
        require(currency.matches("[A-Z]{3}"));
        String aggregateType = optionalText(root, "aggregateType");
        if (aggregateType != null) require("PAYMENT".equals(aggregateType));
        return new AuthorizationDelivery(event, payment, amount.longValue(), currency,
                uuid(payload, "merchantId", false), uuid(payload, "customerId", false),
                optionalText(payload, "idempotencyKey"), uuid(root, "merchantId", false),
                uuid(root, "aggregateId", false), aggregateType);
    }

    private static UUID uuid(JsonNode node, String field, boolean required) {
        if (!node.has(field)) {
            require(!required);
            return null;
        }
        String value = text(node, field);
        try {
            UUID parsed = UUID.fromString(value);
            require(parsed.toString().equalsIgnoreCase(value));
            return parsed;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid authorization identity");
        }
    }

    private static String optionalText(JsonNode node, String field) {
        return node.has(field) ? text(node, field) : null;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        require(value != null && value.isTextual() && !value.textValue().isBlank());
        return value.textValue();
    }

    private static void require(boolean condition) {
        if (!condition) throw new IllegalArgumentException("Invalid authorization delivery");
    }
}
