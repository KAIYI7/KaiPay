package com.lky.kaipay.integration;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.UUID;

/** One PostgreSQL statement provides a consistent payment/cache/binding view, with no response-cache authority. */
@Repository
public class IntegrationPaymentStore {
    private final JdbcTemplate jdbc;
    public IntegrationPaymentStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public Snapshot read(IntegrationProperties.Binding binding, UUID reference) {
        String key = key(binding, reference);
        return jdbc.query(connection -> {
            var statement = connection.prepareStatement("""
                SELECT EXISTS(SELECT 1 FROM merchants m JOIN customers c ON c.merchant_id=m.id
                         WHERE m.id=? AND c.id=? AND m.status='ACTIVE'
                         AND NOT EXISTS(SELECT 1 FROM demo_merchant_memberships mm WHERE mm.merchant_id=m.id))
                         AND NOT EXISTS(SELECT 1 FROM payments old WHERE old.idempotency_key LIKE ?
                           AND (old.merchant_id<>? OR old.customer_id<>?)) AS active,
                       EXISTS(SELECT 1 FROM idempotency_records i WHERE i.merchant_id=? AND i.idempotency_key=?) AS cached,
                       p.id,p.customer_id,p.amount_cents,p.currency,p.payment_method_id,
                       p.metadata='{}'::jsonb AS empty_metadata,p.status,p.version,p.updated_at
                  FROM (SELECT 1) anchor
                  LEFT JOIN payments p ON p.merchant_id=? AND p.idempotency_key=?
                """);
            statement.setQueryTimeout(2);
            statement.setObject(1, binding.merchantId()); statement.setObject(2, binding.customerId());
            statement.setString(3, "int1:" + binding.integrationId() + ":%");
            statement.setObject(4, binding.merchantId()); statement.setObject(5, binding.customerId());
            statement.setObject(6, binding.merchantId()); statement.setString(7, key);
            statement.setObject(8, binding.merchantId()); statement.setString(9, key);
            return statement;
        }, (row, index) -> {
            UUID id = row.getObject("id", UUID.class);
            return new Snapshot(row.getBoolean("active"), row.getBoolean("cached"), id,
                    row.getObject("customer_id", UUID.class), id == null ? null : row.getLong("amount_cents"),
                    row.getString("currency"), row.getObject("payment_method_id", UUID.class),
                    row.getBoolean("empty_metadata"), row.getString("status"), id == null ? null : row.getLong("version"),
                    id == null ? null : row.getTimestamp("updated_at").toInstant());
        }).getFirst();
    }
    static String key(IntegrationProperties.Binding binding, UUID reference) {
        return "int1:" + binding.integrationId() + ":" + reference;
    }
    public record Snapshot(boolean active, boolean cached, UUID paymentId, UUID customerId, Long amount,
                           String currency, UUID methodId, boolean emptyMetadata, String status,
                           Long version, Instant updatedAt) {}
}
