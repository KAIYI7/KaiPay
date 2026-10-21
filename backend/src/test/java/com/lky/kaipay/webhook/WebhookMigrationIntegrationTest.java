package com.lky.kaipay.webhook;

import com.lky.kaipay.AbstractPostgresIntegrationTest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

class WebhookMigrationIntegrationTest extends AbstractPostgresIntegrationTest {
    @Test void v10PreservesHistoricalMoneyAndUrlsWhileConstrainingImmutableOwnedNotifications() {
        String schema = "webhook_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var sql = new JdbcTemplate(source);
        UUID merchant = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID otherMerchant = UUID.fromString("33333333-3333-3333-3333-333333333333");
        UUID customer = UUID.fromString("22222222-2222-2222-2222-222222222221");
        UUID payment = UUID.randomUUID(), refund = UUID.randomUUID();
        try {
            Flyway.configure().dataSource(source).schemas(schema).target("9").load().migrate();
            sql.update("INSERT INTO " + schema + ".payments(id,merchant_id,customer_id,amount_cents,currency,status,idempotency_key) VALUES (?,?,?,5000,'USD','REFUNDED',?)", payment, merchant, customer, payment.toString());
            sql.update("INSERT INTO " + schema + ".refunds(id,payment_id,merchant_id,amount_cents,currency,status,idempotency_key,reason) VALUES (?,?,?,1000,'USD','COMPLETED',?,'historical private reason')", refund, payment, merchant, refund.toString());
            sql.update("INSERT INTO " + schema + ".payment_events_outbox(aggregate_type,aggregate_id,event_type,payload,status) VALUES ('PAYMENT',?,'PaymentCapturedEvent','{\"old\":true}'::jsonb,'PUBLISHED')", payment.toString());
            sql.update("INSERT INTO " + schema + ".consumed_events(event_id,consumer_group,payment_id,event_type,status) VALUES (?,'historical',?,'PaymentInitiatedEvent','AUTHORIZED')", UUID.randomUUID(), payment);
            Map<String,List<Map<String,Object>>> before = new LinkedHashMap<>();
            for (String table : List.of("merchants", "payments", "refunds", "payment_events_outbox", "consumed_events", "accounts", "journals", "ledger_entries")) {
                before.put(table, sql.queryForList("SELECT * FROM " + schema + "." + table));
            }
            var flyway = Flyway.configure().dataSource(source).schemas(schema).target("10").load();
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
            flyway.validate();
            before.forEach((table, original) -> assertThat(sql.queryForList("SELECT * FROM " + schema + "." + table)).as(table).isEqualTo(original));
            for (String table : List.of("merchant_webhook_endpoints", "merchant_webhook_deliveries", "merchant_webhook_attempts")) {
                assertThat(sql.queryForObject("SELECT count(*) FROM " + schema + "." + table, Long.class)).isZero();
            }

            UUID endpoint = UUID.randomUUID(), key = UUID.randomUUID();
            endpoint(sql, schema, endpoint, merchant, key);
            assertThatThrownBy(() -> endpoint(sql, schema, UUID.randomUUID(), merchant, UUID.randomUUID())).isInstanceOf(DataIntegrityViolationException.class);
            // A different tenant may not reuse the same underlying secret-file alias.
            assertThatThrownBy(() -> sql.update("INSERT INTO " + schema + ".merchant_webhook_endpoints(id,merchant_id,url,hostname,path,pins,key_id,secret_ref,state,created_by,reason_code) VALUES (?,?,'https://receiver.example.test/hook','receiver.example.test','/hook',ARRAY['8.8.8.8'],?,?,'ACTIVE','synthetic-operator','TEST')",
                    UUID.randomUUID(), otherMerchant, UUID.randomUUID(), alias(endpoint))).isInstanceOf(DataIntegrityViolationException.class);
            for (String assignment : List.of("url='https://another.example.test/hook'", "hostname='another.example.test'", "path='/other'",
                    "pins=ARRAY['1.1.1.1']", "secret_ref='another_key'", "key_id=gen_random_uuid()", "merchant_id='" + otherMerchant + "'", "reason_code='CHANGED'")) {
                assertThatThrownBy(() -> sql.update("UPDATE " + schema + ".merchant_webhook_endpoints SET " + assignment + " WHERE id=?", endpoint)).as(assignment).isInstanceOf(DataIntegrityViolationException.class);
            }
            UUID otherEndpoint = UUID.randomUUID(); endpoint(sql, schema, otherEndpoint, otherMerchant, UUID.randomUUID());
            assertThatThrownBy(() -> delivery(sql, schema, UUID.randomUUID(), UUID.randomUUID(), otherMerchant, otherEndpoint, payment)).isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> delivery(sql, schema, UUID.randomUUID(), UUID.randomUUID(), merchant, otherEndpoint, payment)).isInstanceOf(DataIntegrityViolationException.class);

            UUID delivery = UUID.randomUUID(), event = UUID.randomUUID();
            delivery(sql, schema, delivery, event, merchant, endpoint, payment);
            assertThatThrownBy(() -> delivery(sql, schema, UUID.randomUUID(), UUID.randomUUID(), merchant, endpoint, payment)).isInstanceOf(DataIntegrityViolationException.class);
            byte[] retained = sql.queryForObject("SELECT payload FROM " + schema + ".merchant_webhook_deliveries WHERE id=?", byte[].class, delivery);
            for (String assignment : List.of("payload=decode('00','hex')", "event_id=gen_random_uuid()", "event_version=2", "endpoint_id='" + otherEndpoint + "'",
                    "merchant_id='" + otherMerchant + "'", "occurred_at=NOW()", "source_event_id=gen_random_uuid()", "expires_at=NOW()+INTERVAL '25 hours'",
                    "attempts=9", "state='IN_FLIGHT'", "delivered_at=NOW()", "next_attempt_at=NULL")) {
                assertThatThrownBy(() -> sql.update("UPDATE " + schema + ".merchant_webhook_deliveries SET " + assignment + " WHERE id=?", delivery)).as(assignment).isInstanceOf(DataIntegrityViolationException.class);
            }
            assertThat(sql.queryForObject("SELECT payload FROM " + schema + ".merchant_webhook_deliveries WHERE id=?", byte[].class, delivery)).isEqualTo(retained);

            UUID attempt = UUID.randomUUID();
            assertThatThrownBy(() -> attempt(sql, schema, UUID.randomUUID(), delivery, 1, UUID.randomUUID())).isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> attempt(sql, schema, UUID.randomUUID(), delivery, 9, key)).isInstanceOf(DataIntegrityViolationException.class);
            attempt(sql, schema, attempt, delivery, 1, key);
            assertThatThrownBy(() -> attempt(sql, schema, UUID.randomUUID(), delivery, 1, key)).isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> sql.update("UPDATE " + schema + ".merchant_webhook_attempts SET key_id=gen_random_uuid() WHERE id=?", attempt)).isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> sql.update("UPDATE " + schema + ".merchant_webhook_attempts SET result='ACKNOWLEDGED' WHERE id=?", attempt)).isInstanceOf(DataIntegrityViolationException.class);
            sql.update("UPDATE " + schema + ".merchant_webhook_attempts SET result='ACKNOWLEDGED',completed_at=NOW(),duration_ms=1,http_status=204 WHERE id=?", attempt);
            assertThatThrownBy(() -> sql.update("UPDATE " + schema + ".merchant_webhook_attempts SET result='UNKNOWN' WHERE id=?", attempt)).isInstanceOf(DataIntegrityViolationException.class);

            sql.update("UPDATE " + schema + ".merchant_webhook_endpoints SET state='RETIRED',retired_at=NOW() WHERE id=?", endpoint);
            assertThatThrownBy(() -> sql.update("UPDATE " + schema + ".merchant_webhook_endpoints SET state='ACTIVE',retired_at=NULL WHERE id=?", endpoint)).isInstanceOf(DataIntegrityViolationException.class);
            endpoint(sql, schema, UUID.randomUUID(), merchant, UUID.randomUUID());
            assertThat(sql.queryForObject("SELECT count(*) FROM " + schema + ".merchant_webhook_endpoints WHERE merchant_id=?", Long.class, merchant)).isEqualTo(2);
            assertThat(sql.queryForObject("SELECT webhook_url FROM " + schema + ".merchants WHERE id=?", String.class, merchant)).isEqualTo(before.get("merchants").stream().filter(m -> merchant.equals(m.get("id"))).findFirst().orElseThrow().get("webhook_url"));
        } finally {
            sql.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    private static void endpoint(JdbcTemplate sql, String schema, UUID id, UUID merchant, UUID key) {
        sql.update("INSERT INTO " + schema + ".merchant_webhook_endpoints(id,merchant_id,url,hostname,path,pins,key_id,secret_ref,state,created_by,reason_code) VALUES (?,?,'https://receiver.example.test/hook','receiver.example.test','/hook',ARRAY['8.8.8.8'],?,?,'ACTIVE','synthetic-operator','TEST')", id, merchant, key, alias(id));
    }
    private static String alias(UUID endpoint) { return "fixture_" + endpoint.toString().replace("-", ""); }
    private static void delivery(JdbcTemplate sql, String schema, UUID id, UUID event, UUID merchant, UUID endpoint, UUID payment) {
        sql.update("INSERT INTO " + schema + ".merchant_webhook_deliveries(id,event_id,merchant_id,endpoint_id,notification_key,event_type,event_version,payment_id,occurred_at,payload,next_attempt_at,expires_at) VALUES (?,?,?,?,'capture:'||?::text,'payment.captured',1,?,NOW(),?,NOW(),NOW()+INTERVAL '24 hours')", id, event, merchant, endpoint, payment, payment, "{\"synthetic\":true}".getBytes(StandardCharsets.UTF_8));
    }
    private static void attempt(JdbcTemplate sql, String schema, UUID id, UUID delivery, int ordinal, UUID key) {
        sql.update("INSERT INTO " + schema + ".merchant_webhook_attempts(id,delivery_id,ordinal,started_at,deadline,key_id,result) VALUES (?,?,?,NOW(),NOW()+INTERVAL '30 seconds',?,'STARTED')", id, delivery, ordinal, key);
    }
}
