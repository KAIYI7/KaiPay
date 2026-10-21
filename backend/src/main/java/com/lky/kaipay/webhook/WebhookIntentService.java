package com.lky.kaipay.webhook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lky.kaipay.common.MoneyCurrency;
import com.lky.kaipay.payment.domain.Payment;
import com.lky.kaipay.refund.domain.Refund;
import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** The notification intent is part of the existing financial transaction, never a network call. */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class WebhookIntentService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final EntityManager entities;

    public WebhookIntentService(JdbcTemplate jdbc, ObjectMapper mapper, EntityManager entities) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.entities = entities;
    }

    public void authorization(Payment payment, UUID requestEventId) {
        String type = switch (payment.getStatus()) {
            case AUTHORIZED -> "payment.authorized";
            case DECLINED -> "payment.declined";
            case FAILED -> "payment.failed";
            default -> throw new IllegalArgumentException("Ineligible webhook authorization state");
        };
        enqueue(payment, null, UUID.randomUUID(), Instant.now(), type,
                "authorization:" + payment.getId(), requestEventId);
    }

    public void captured(Payment payment, UUID eventId, Instant occurredAt) {
        enqueue(payment, null, eventId, occurredAt, "payment.captured", "capture:" + payment.getId(), eventId);
    }

    public void refunded(Payment payment, Refund refund, UUID eventId, Instant occurredAt) {
        enqueue(payment, refund, eventId, occurredAt, "refund.completed", "refund:" + refund.getId(), eventId);
    }

    private void enqueue(Payment payment, Refund refund, UUID eventId, Instant occurredAt,
                         String type, String key, UUID sourceEventId) {
        if (!MoneyCurrency.MYR.equals(payment.getCurrency())) return;
        UUID merchantId = payment.getMerchant().getId();
        var endpoints = jdbc.query("SELECT id FROM merchant_webhook_endpoints WHERE merchant_id=? AND state='ACTIVE'",
                (rs, row) -> rs.getObject("id", UUID.class), merchantId);
        if (endpoints.isEmpty()) return;

        ObjectNode body = mapper.createObjectNode();
        body.put("id", eventId.toString());
        body.put("type", type);
        body.put("version", 1);
        body.put("occurred_at", occurredAt.toString());
        body.put("merchant_id", merchantId.toString());
        ObjectNode data = body.putObject("data");
        data.put("payment_id", payment.getId().toString());
        if (refund == null) {
            data.put("amount_cents", payment.getAmountCents());
            data.put("currency", payment.getCurrency());
            data.put("status", payment.getStatus().name());
        } else {
            if (!refund.getMerchant().getId().equals(merchantId) || !refund.getPayment().getId().equals(payment.getId())
                    || !refund.getCurrency().equals(payment.getCurrency())) {
                throw new IllegalStateException("Webhook refund ownership conflict");
            }
            data.put("refund_id", refund.getId().toString());
            data.put("refund_amount_cents", refund.getAmountCents());
            data.put("currency", refund.getCurrency());
            data.put("status", refund.getStatus().name());
            data.put("payment_status", payment.getStatus().name());
        }

        byte[] bytes;
        try { bytes = mapper.writeValueAsBytes(body); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("Webhook payload serialization failed"); }
        if (bytes.length > 4096) throw new IllegalStateException("Webhook payload exceeds size limit");
        // Flush new refund IDs and optimistic state/ledger/outbox writes before the JDBC FK insert.
        // JpaTransactionManager binds this connection to the same commit/rollback boundary.
        entities.flush();
        Instant persistedTime = occurredAt.truncatedTo(ChronoUnit.MICROS);
        jdbc.update("""
                INSERT INTO merchant_webhook_deliveries
                (id,event_id,merchant_id,endpoint_id,notification_key,event_type,event_version,payment_id,refund_id,
                 source_event_id,occurred_at,payload,state,attempts,next_attempt_at,expires_at)
                VALUES (?,?,?,?,?,?,1,?,?,?, ?,?,'PENDING',0,?,?)
                """, UUID.randomUUID(), eventId, merchantId, endpoints.getFirst(), key, type,
                payment.getId(), refund == null ? null : refund.getId(), sourceEventId,
                Timestamp.from(persistedTime), bytes, Timestamp.from(persistedTime),
                Timestamp.from(persistedTime.plus(24, ChronoUnit.HOURS)));
    }
}
