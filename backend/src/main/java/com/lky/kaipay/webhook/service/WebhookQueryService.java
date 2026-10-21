package com.lky.kaipay.webhook.service;

import com.lky.kaipay.common.api.BoundedPageRequest;
import com.lky.kaipay.common.exception.EntityNotFoundException;
import com.lky.kaipay.common.exception.InvalidRequestException;
import com.lky.kaipay.security.ResourceAccess;
import com.lky.kaipay.webhook.api.WebhookViews.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class WebhookQueryService {
    private static final Set<String> STATES = Set.of("PENDING", "IN_FLIGHT", "DELIVERED", "TERMINAL", "EXHAUSTED", "CANCELLED");
    private static final String COLUMNS = """
            d.id,d.event_id,d.event_type,d.event_version,d.payment_id,d.refund_id,d.state,d.attempts,
            d.next_attempt_at,d.occurred_at,d.created_at,d.expires_at,d.delivered_at,d.last_result,
            d.endpoint_id,e.key_id
            """;
    private final ResourceAccess access;
    private final JdbcTemplate jdbc;

    public WebhookQueryService(ResourceAccess access, JdbcTemplate jdbc) { this.access = access; this.jdbc = jdbc; }

    public EndpointView endpoint(UUID merchant) {
        access.merchant(merchant);
        return jdbc.query("SELECT id,key_id FROM merchant_webhook_endpoints WHERE merchant_id=? AND state='ACTIVE'",
                (rs, row) -> new EndpointView(true, rs.getObject("id", UUID.class), rs.getObject("key_id", UUID.class)),
                merchant).stream().findFirst().orElse(new EndpointView(false, null, null));
    }

    public Page<DeliveryView> deliveries(UUID merchant, int page, int size, UUID paymentId, String state) {
        access.merchant(merchant);
        var pageable = BoundedPageRequest.of(page, size);
        if (state != null && !STATES.contains(state)) throw new InvalidRequestException("Unsupported webhook delivery state");
        String filter = " WHERE d.merchant_id=?";
        var args = new ArrayList<Object>(); args.add(merchant);
        if (paymentId != null) { filter += " AND d.payment_id=?"; args.add(paymentId); }
        if (state != null) { filter += " AND d.state=?"; args.add(state); }
        Long total = jdbc.queryForObject("SELECT count(*) FROM merchant_webhook_deliveries d" + filter, Long.class, args.toArray());
        args.add(size); args.add(pageable.getOffset());
        var rows = jdbc.query("SELECT " + COLUMNS + " FROM merchant_webhook_deliveries d JOIN merchant_webhook_endpoints e "
                        + "ON e.id=d.endpoint_id AND e.merchant_id=d.merchant_id" + filter
                        + " ORDER BY d.created_at DESC,d.id DESC LIMIT ? OFFSET ?", this::delivery, args.toArray());
        return new PageImpl<>(rows, pageable, total == null ? 0 : total);
    }

    public DeliveryDetail detail(UUID merchant, UUID id) {
        access.merchant(merchant);
        var result = jdbc.query("SELECT " + COLUMNS + " FROM merchant_webhook_deliveries d JOIN merchant_webhook_endpoints e "
                        + "ON e.id=d.endpoint_id AND e.merchant_id=d.merchant_id WHERE d.id=? AND d.merchant_id=?",
                this::delivery, id, merchant).stream().findFirst()
                .orElseThrow(() -> new EntityNotFoundException("Webhook delivery not found"));
        var attempts = jdbc.query("""
                SELECT a.ordinal,a.started_at,a.completed_at,a.deadline,a.result,a.http_status,a.duration_ms,a.key_id
                FROM merchant_webhook_attempts a JOIN merchant_webhook_deliveries d ON d.id=a.delivery_id
                WHERE d.id=? AND d.merchant_id=? ORDER BY a.ordinal LIMIT 8
                """, (rs, row) -> new AttemptView(rs.getInt("ordinal"), instant(rs, "started_at"),
                instant(rs, "completed_at"), instant(rs, "deadline"), safeResult(rs.getString("result")),
                rs.getObject("http_status", Integer.class), rs.getObject("duration_ms", Long.class),
                rs.getObject("key_id", UUID.class)), id, merchant);
        return new DeliveryDetail(result, attempts);
    }

    private DeliveryView delivery(ResultSet rs, int row) throws SQLException {
        return new DeliveryView(rs.getObject("id", UUID.class), rs.getObject("event_id", UUID.class),
                rs.getString("event_type"), rs.getInt("event_version"), rs.getObject("payment_id", UUID.class),
                rs.getObject("refund_id", UUID.class), rs.getString("state"), rs.getInt("attempts"),
                instant(rs, "next_attempt_at"), instant(rs, "occurred_at"), instant(rs, "created_at"),
                instant(rs, "expires_at"), instant(rs, "delivered_at"), safeResult(rs.getString("last_result")),
                rs.getObject("endpoint_id", UUID.class), rs.getObject("key_id", UUID.class));
    }

    private static Instant instant(ResultSet rs, String name) throws SQLException {
        Timestamp value = rs.getTimestamp(name); return value == null ? null : value.toInstant();
    }

    private static String safeResult(String value) {
        if (value == null) return null;
        return Set.of("STARTED", "UNKNOWN", "ACCEPTED", "HTTP_RETRYABLE", "HTTP_TERMINAL", "IO_ERROR", "TIMEOUT",
                "TLS_ERROR", "CONFIGURATION", "UNSAFE_DESTINATION", "CANCELLED", "EXPIRED", "EXHAUSTED")
                .contains(value) ? value : "UNKNOWN";
    }
}
