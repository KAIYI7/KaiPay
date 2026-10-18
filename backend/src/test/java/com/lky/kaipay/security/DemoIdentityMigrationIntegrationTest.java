package com.lky.kaipay.security;

import com.lky.kaipay.AbstractPostgresIntegrationTest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DemoIdentityMigrationIntegrationTest extends AbstractPostgresIntegrationTest {
    @Test void additiveV7UpgradesV6WithoutSeedingCredentialsOrChangingFinancialRows() {
        String schema = "identity_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var sql = new JdbcTemplate(source);
        try {
            Flyway.configure().dataSource(source).schemas(schema).target("6").load().migrate();
            sql.update("INSERT INTO " + schema + ".payment_events_outbox (aggregate_type,aggregate_id,event_type,payload,status,retry_count,last_error) "
                    + "VALUES ('PAYMENT','legacy','PaymentInitiatedEvent','{}'::jsonb,'PENDING',2,'synthetic legacy error')");
            var merchantRows = sql.queryForList("SELECT * FROM " + schema + ".merchants ORDER BY id");
            var outboxRows = sql.queryForList("SELECT * FROM " + schema + ".payment_events_outbox ORDER BY id");
            var flyway = Flyway.configure().dataSource(source).schemas(schema).target("7").load();
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
            flyway.validate();
            assertThat(sql.queryForList("SELECT * FROM " + schema + ".merchants ORDER BY id")).isEqualTo(merchantRows);
            assertThat(sql.queryForList("SELECT * FROM " + schema + ".payment_events_outbox ORDER BY id")).isEqualTo(outboxRows);
            assertThat(sql.queryForObject("SELECT count(*) FROM " + schema + ".demo_users", Integer.class)).isZero();
            UUID user = UUID.randomUUID();
            UUID merchant = UUID.fromString("11111111-1111-1111-1111-111111111111");
            sql.update("INSERT INTO " + schema + ".demo_users(id,username,password_hash,role) VALUES (?,?,'synthetic-hash-fixture','MERCHANT')", user, "fixture");
            sql.update("INSERT INTO " + schema + ".demo_merchant_memberships VALUES (?,?)", user, merchant);
            assertThatThrownBy(() -> sql.update("INSERT INTO " + schema + ".demo_merchant_memberships VALUES (?,?)", user, merchant))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThatThrownBy(() -> sql.update("INSERT INTO " + schema + ".demo_merchant_memberships VALUES (?,?)", user, UUID.randomUUID()))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThatThrownBy(() -> sql.update("UPDATE " + schema + ".demo_users SET role='SUPERADMIN' WHERE id=?", user))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            sql.update("DELETE FROM " + schema + ".demo_users WHERE id=?", user);
            assertThat(sql.queryForObject("SELECT count(*) FROM " + schema + ".demo_merchant_memberships", Integer.class)).isZero();
        } finally { sql.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE"); }
    }
}
