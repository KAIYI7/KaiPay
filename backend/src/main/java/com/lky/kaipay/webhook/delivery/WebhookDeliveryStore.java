package com.lky.kaipay.webhook.delivery;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/** All methods are short database transactions; never call transport from here. */
@Service
public class WebhookDeliveryStore {
    private final JdbcTemplate jdbc;
    private static final long[] DELAYS = {5, 30, 120, 600, 1800, 7200, 21600};
    public WebhookDeliveryStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public Optional<WebhookClaim> claim(Instant now) {
        // Recover one expired owner per tick, without another scheduler or resetting its budget.
        List<Map<String,Object>> abandoned = jdbc.queryForList("""
                SELECT id,current_attempt_id,attempts,expires_at FROM merchant_webhook_deliveries
                WHERE state='IN_FLIGHT' AND claim_deadline<=? ORDER BY claim_deadline,id LIMIT 1 FOR UPDATE SKIP LOCKED
                """, ts(now));
        if (!abandoned.isEmpty()) {
            var row = abandoned.getFirst();
            completeLocked((UUID)row.get("id"), (UUID)row.get("current_attempt_id"),
                    (Integer)row.get("attempts"), ((Timestamp)row.get("expires_at")).toInstant(), WebhookResult.UNKNOWN, null, now);
        }
        List<UUID> due = jdbc.query("""
                SELECT id FROM merchant_webhook_deliveries WHERE state='PENDING' AND next_attempt_at<=?
                ORDER BY next_attempt_at,id LIMIT 1 FOR UPDATE SKIP LOCKED
                """, (rs,n)->rs.getObject(1,UUID.class), ts(now));
        if (due.isEmpty()) return Optional.empty();
        UUID id = due.getFirst();
        var rows = jdbc.query("""
                SELECT d.*,e.url,e.hostname,e.path,e.pins,e.key_id,e.secret_ref,
                       (e.state='ACTIVE' AND m.status='ACTIVE') AS eligible
                FROM merchant_webhook_deliveries d JOIN merchant_webhook_endpoints e ON e.id=d.endpoint_id
                JOIN merchants m ON m.id=d.merchant_id WHERE d.id=?
                """, (rs,n)-> {
                    if (!rs.getBoolean("eligible")) return null;
                    return new WebhookClaim(id, rs.getObject("event_id",UUID.class),rs.getObject("merchant_id",UUID.class),
                            rs.getObject("endpoint_id",UUID.class),UUID.randomUUID(),rs.getInt("attempts")+1,
                            rs.getTimestamp("expires_at").toInstant(),now.plusSeconds(30),rs.getBytes("payload"),
                            rs.getString("url"),rs.getString("hostname"),rs.getString("path"),
                            Arrays.asList((String[])rs.getArray("pins").getArray()),rs.getObject("key_id",UUID.class),rs.getString("secret_ref"));
                },id);
        WebhookClaim claim = rows.getFirst();
        if (claim == null) { terminal(id,"CANCELLED",WebhookResult.CANCELLED); return Optional.empty(); }
        if (!now.isBefore(claim.expiresAt()) || claim.ordinal()>8) {
            terminal(id,"EXHAUSTED",!now.isBefore(claim.expiresAt())?WebhookResult.EXPIRED:WebhookResult.EXHAUSTED);
            return Optional.empty();
        }
        jdbc.update("""
                INSERT INTO merchant_webhook_attempts(id,delivery_id,ordinal,started_at,deadline,key_id,result)
                VALUES (?,?,?,?,?,?,'STARTED')
                """,claim.attemptId(),id,claim.ordinal(),ts(now),ts(claim.deadline()),claim.keyId());
        jdbc.update("""
                UPDATE merchant_webhook_deliveries SET state='IN_FLIGHT',attempts=?,next_attempt_at=NULL,
                current_attempt_id=?,claim_deadline=?,last_result='STARTED' WHERE id=?
                """,claim.ordinal(),claim.attemptId(),ts(claim.deadline()),id);
        return Optional.of(claim);
    }
    @Transactional(readOnly=true)
    public boolean ready(WebhookClaim c, Instant now) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM merchant_webhook_deliveries d
                JOIN merchant_webhook_endpoints e ON e.id=d.endpoint_id JOIN merchants m ON m.id=d.merchant_id
                WHERE d.id=? AND d.state='IN_FLIGHT' AND d.current_attempt_id=?
                AND d.claim_deadline>? AND d.expires_at>? AND e.state='ACTIVE' AND m.status='ACTIVE'
                AND e.key_id=? AND e.merchant_id=d.merchant_id)
                """,Boolean.class,c.id(),c.attemptId(),ts(now),ts(now),c.keyId()));
    }
    @Transactional
    public void complete(WebhookClaim c, WebhookTransport.Response response, Instant now) {
        var ids=jdbc.query("SELECT current_attempt_id FROM merchant_webhook_deliveries WHERE id=? AND state='IN_FLIGHT' AND claim_deadline>? FOR UPDATE",
                (rs,n)->rs.getObject(1,UUID.class),c.id(),ts(now));
        if(ids.isEmpty()||!c.attemptId().equals(ids.getFirst())) return;
        // Retirement/inactive merchant cancels unsent retry; in-flight remote effects cannot be recalled.
        boolean active=Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM merchant_webhook_endpoints e JOIN merchants m ON m.id=e.merchant_id
                WHERE e.id=? AND e.state='ACTIVE' AND m.status='ACTIVE')
                """,Boolean.class,c.endpointId()));
        completeLocked(c.id(),c.attemptId(),c.ordinal(),c.expiresAt(),active?response.result():WebhookResult.CANCELLED,response.status(),now);
    }
    private void completeLocked(UUID id, UUID attemptId, int ordinal, Instant expires, WebhookResult result, Integer status, Instant now) {
        jdbc.update("""
                UPDATE merchant_webhook_attempts SET result=?,http_status=?,completed_at=?,
                duration_ms=GREATEST(0,EXTRACT(EPOCH FROM (?::timestamptz-started_at))*1000)::BIGINT
                WHERE id=? AND result='STARTED'
                """,result.name(),status,ts(now),ts(now),attemptId);
        if(result==WebhookResult.ACCEPTED) {
            jdbc.update("""
                    UPDATE merchant_webhook_deliveries SET state='DELIVERED',delivered_at=?,current_attempt_id=NULL,
                    claim_deadline=NULL,last_result=? WHERE id=?
                    """,ts(now),result.name(),id);
        } else if(result==WebhookResult.CANCELLED) terminal(id,"CANCELLED",result);
        else if(result==WebhookResult.EXPIRED) terminal(id,"EXHAUSTED",result);
        else if(result.retryable() && ordinal<8 && now.isBefore(expires)) {
            Instant next=now.plusMillis((long)(DELAYS[ordinal-1]*1000*(1+ThreadLocalRandom.current().nextDouble(0,.2))));
            if(next.isAfter(expires)) next=expires;
            jdbc.update("""
                    UPDATE merchant_webhook_deliveries SET state='PENDING',next_attempt_at=?,current_attempt_id=NULL,
                    claim_deadline=NULL,last_result=? WHERE id=?
                    """,ts(next),result.name(),id);
        } else terminal(id,result.retryable()?"EXHAUSTED":"TERMINAL",result.retryable()?WebhookResult.EXHAUSTED:result);
    }
    private void terminal(UUID id,String state,WebhookResult result) {
        jdbc.update("""
                UPDATE merchant_webhook_deliveries SET state=?,next_attempt_at=NULL,current_attempt_id=NULL,
                claim_deadline=NULL,last_result=? WHERE id=?
                """,state,result.name(),id);
    }
    private static Timestamp ts(Instant i) { return Timestamp.from(i); }
}
