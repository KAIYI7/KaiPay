package com.lky.kaipay.payment.service.acquirer;

import com.lky.kaipay.AbstractPostgresIntegrationTest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.*;

import static org.assertj.core.api.Assertions.*;

class SimulatedAuthorizationMigrationIntegrationTest extends AbstractPostgresIntegrationTest {
    @Test void additiveV8PreservesLegacyStatesMarkersAndRequiresValidRetainedEvidence() {
        String schema = "sim_auth_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var sql = new JdbcTemplate(source);
        try {
            Flyway.configure().dataSource(source).schemas(schema).target("7").load().migrate();
            UUID merchant = UUID.fromString("11111111-1111-1111-1111-111111111111");
            UUID customer = UUID.fromString("22222222-2222-2222-2222-222222222221");
            Map<String, UUID> payments = new LinkedHashMap<>();
            for (String status : List.of("CREATED", "PROCESSING", "AUTHORIZED", "DECLINED", "FAILED", "CAPTURED", "REFUND_PENDING", "PARTIALLY_REFUNDED", "REFUNDED")) {
                UUID payment = UUID.randomUUID();
                payments.put(status, payment);
                sql.update("INSERT INTO " + schema + ".payments(id,merchant_id,customer_id,amount_cents,currency,status,idempotency_key,gateway_reference) VALUES (?,?,?,5000,'USD',?,?,?)",
                        payment, merchant, customer, status, payment.toString(), "legacy-reference");
                if (!status.equals("CREATED") && !status.equals("PROCESSING")) {
                    sql.update("INSERT INTO " + schema + ".consumed_events(event_id,consumer_group,payment_id,event_type,status) VALUES (?,'legacy-group',?,'PaymentInitiatedEvent',?)",
                            UUID.randomUUID(), payment, status);
                }
            }
            sql.update("INSERT INTO " + schema + ".payment_events_outbox(aggregate_type,aggregate_id,event_type,payload,status) VALUES ('PAYMENT',?,'PaymentInitiatedEvent','{}'::jsonb,'PENDING')",
                    payments.get("PROCESSING").toString());
            var legacyPayments = sql.queryForList("SELECT * FROM " + schema + ".payments ORDER BY id");
            var legacyMarkers = sql.queryForList("SELECT * FROM " + schema + ".consumed_events ORDER BY event_id");
            var legacyOutbox = sql.queryForList("SELECT * FROM " + schema + ".payment_events_outbox ORDER BY id");
            var flyway = Flyway.configure().dataSource(source).schemas(schema).load();
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
            flyway.validate();
            assertThat(sql.queryForList("SELECT * FROM " + schema + ".payments ORDER BY id")).isEqualTo(legacyPayments);
            assertThat(sql.queryForList("SELECT * FROM " + schema + ".consumed_events ORDER BY event_id")).isEqualTo(legacyMarkers);
            assertThat(sql.queryForList("SELECT * FROM " + schema + ".payment_events_outbox ORDER BY id")).isEqualTo(legacyOutbox);
            assertThat(sql.queryForObject("SELECT count(*) FROM " + schema + ".simulated_authorization_operations", Long.class)).isZero();

            String insert = "INSERT INTO " + schema + ".simulated_authorization_operations(payment_id,namespace,contract_version,merchant_id,amount_cents,currency,disposition,evaluation_count) VALUES (?,'kaipay-sim-auth-v1',1,?,5000,'USD','UNRESOLVED',0)";
            UUID payment = payments.get("CREATED");
            sql.update(insert, payment, merchant);
            assertThatThrownBy(() -> sql.update(insert, payment, merchant)).isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> sql.update(insert, UUID.randomUUID(), merchant)).isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> sql.update("DELETE FROM " + schema + ".payments WHERE id=?", payment)).isInstanceOf(DataIntegrityViolationException.class);
            for (String invalidAssignment : List.of(
                    "namespace='new-identity'", "contract_version=2", "merchant_id='00000000-0000-0000-0000-000000000001'",
                    "amount_cents=0", "currency='usd'", "evaluation_count=-1", "disposition='UNKNOWN'",
                    "authorization_code='invented'", "result_at=NOW()", "decline_code='invented'", "failure_code='invented'",
                    "disposition='APPROVED'", "disposition='DECLINED'", "disposition='REJECTED_NO_ACCEPTANCE'",
                    "disposition='REJECTED_NO_ACCEPTANCE',evaluation_count=1,result_at=NOW(),failure_message='fixed'")) {
                assertThatThrownBy(() -> sql.update("UPDATE " + schema + ".simulated_authorization_operations SET " + invalidAssignment + " WHERE payment_id=?", payment))
                        .as(invalidAssignment).isInstanceOf(DataIntegrityViolationException.class);
            }
            sql.update("UPDATE " + schema + ".simulated_authorization_operations SET disposition='APPROVED',evaluation_count=1,authorization_code='AUTH-RETAINED',result_at=NOW() WHERE payment_id=?", payment);
            assertThat(sql.queryForObject("SELECT authorization_code FROM " + schema + ".simulated_authorization_operations WHERE payment_id=?", String.class, payment)).isEqualTo("AUTH-RETAINED");
            assertThatThrownBy(() -> sql.update("UPDATE " + schema + ".simulated_authorization_operations SET decline_code='CONTRADICTORY' WHERE payment_id=?", payment))
                    .isInstanceOf(DataIntegrityViolationException.class);
            // Explicit isolated fixture cleanup follows the retained-evidence FK order.
            sql.update("DELETE FROM " + schema + ".simulated_authorization_operations WHERE payment_id=?", payment);
            sql.update("DELETE FROM " + schema + ".payments WHERE id=?", payment);
        } finally { sql.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE"); }
    }
}
