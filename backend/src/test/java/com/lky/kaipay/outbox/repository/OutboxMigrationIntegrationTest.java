package com.lky.kaipay.outbox.repository;

import com.lky.kaipay.AbstractPostgresIntegrationTest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxMigrationIntegrationTest extends AbstractPostgresIntegrationTest {
    @Test
    void upgradeFromV5RetainsPendingPublishedAndRetryHistory() {
        String schema = "outbox_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var sql = new JdbcTemplate(source);
        try {
            Flyway.configure().dataSource(source).schemas(schema).target("5").load().migrate();
            sql.update("INSERT INTO " + schema + ".payment_events_outbox " +
                    "(aggregate_type, aggregate_id, event_type, payload, status, retry_count, last_error, published_at) " +
                    "VALUES ('PAYMENT', 'legacy', 'PaymentInitiatedEvent', '{}'::jsonb, 'PENDING', 7, 'old error', NULL), " +
                    "('PAYMENT', 'published', 'PaymentCapturedEvent', '{}'::jsonb, 'PUBLISHED', 0, NULL, NOW())");
            Flyway.configure().dataSource(source).schemas(schema).target("6").load().migrate();
            var rows = sql.queryForList("SELECT * FROM " + schema + ".payment_events_outbox ORDER BY aggregate_id");
            assertThat(rows).hasSize(2);
            assertThat(rows.get(0)).containsEntry("status", "PENDING").containsEntry("retry_count", 7)
                    .containsEntry("last_error", "old error").containsEntry("next_attempt_at", null)
                    .containsEntry("last_attempt_at", null).containsEntry("quarantined_at", null);
            assertThat(rows.get(1)).containsEntry("status", "PUBLISHED");
            assertThat(rows.get(1).get("published_at")).isNotNull();
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM " + schema + ".payment_events_outbox " +
                    "WHERE status='PENDING' AND COALESCE(next_attempt_at, created_at) <= NOW()", Integer.class)).isEqualTo(1);
        } finally {
            // Only this test's generated schema is removed; the shared integration schema is untouched.
            sql.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }
}
