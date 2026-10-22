package com.lky.kaipay.reconciliation.acceptance;

import com.lky.kaipay.AbstractPostgresIntegrationTest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.*;

import static org.assertj.core.api.Assertions.*;

/** Upgrades a disposable V10 schema containing historical USD financial evidence. */
class ReconciliationHistoricalMigrationIntegrationTest extends AbstractPostgresIntegrationTest {
    @Test void v11PreservesEveryExistingTableAndAddsOnlyEmptyReportTables() {
        String schema = "recon_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var sql = new JdbcTemplate(source);
        UUID merchant = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID customer = UUID.fromString("22222222-2222-2222-2222-222222222221");
        UUID payment = UUID.randomUUID(), refund = UUID.randomUUID(), event = UUID.randomUUID();
        UUID account = UUID.randomUUID(), journal = UUID.randomUUID();
        try {
            Flyway.configure().dataSource(source).schemas(schema).target("10").load().migrate();
            sql.update("INSERT INTO " + schema + ".payments(id,merchant_id,customer_id,amount_cents,currency,status,idempotency_key,gateway_reference,metadata) "
                    + "VALUES (?,?,?,12000,'USD','REFUNDED',?,'AUTH-HISTORICAL','{\"private\":\"retained\"}'::jsonb)", payment, merchant, customer, payment.toString());
            sql.update("INSERT INTO " + schema + ".simulated_authorization_operations(payment_id,namespace,contract_version,merchant_id,amount_cents,currency,disposition,evaluation_count,authorization_code,result_at) "
                    + "VALUES (?,'kaipay-sim-auth-v1',1,?,12000,'USD','APPROVED',1,'AUTH-HISTORICAL',NOW())", payment, merchant);
            sql.update("INSERT INTO " + schema + ".refunds(id,payment_id,merchant_id,amount_cents,currency,status,idempotency_key,reason) "
                    + "VALUES (?,?,?,12000,'USD','COMPLETED',?,'private historical reason')", refund, payment, merchant, refund.toString());
            sql.update("INSERT INTO " + schema + ".accounts(id,merchant_id,account_number,account_name,account_type,currency) VALUES (?,?,?,'Historical liability','LIABILITY','USD')",
                    account, merchant, account.toString());
            sql.update("INSERT INTO " + schema + ".journals(id,journal_number,source_type,source_id,event_id,merchant_id,description) VALUES (?,?,'PAYMENT_CAPTURE',?,?,?,'Historical journal')",
                    journal, "HIST-" + journal, payment.toString(), event, merchant);
            for (String type : List.of("DEBIT", "CREDIT"))
                sql.update("INSERT INTO " + schema + ".ledger_entries(journal_id,account_id,entry_type,amount_cents,currency) VALUES (?,?,?,12000,'USD')", journal, account, type);
            sql.update("INSERT INTO " + schema + ".payment_events_outbox(aggregate_type,aggregate_id,event_type,payload,status) VALUES ('PAYMENT',?,'PaymentCapturedEvent','{\"old\":true}'::jsonb,'PUBLISHED')", payment.toString());
            sql.update("INSERT INTO " + schema + ".consumed_events(event_id,consumer_group,payment_id,event_type,status) VALUES (?,'historical-group',?,'PaymentInitiatedEvent','AUTHORIZED')", event, payment);
            sql.update("INSERT INTO " + schema + ".idempotency_records(merchant_id,idempotency_key,request_hash,response_status,response_body,expires_at) VALUES (?,?,'retained',201,'retained',NOW()+INTERVAL '1 day')", merchant, payment.toString());
            sql.update("INSERT INTO " + schema + ".dead_letter_events(original_topic,original_partition,original_offset,payment_id,exception_class,payload) VALUES ('synthetic',0,1,?,'Historical','{}'::jsonb)", payment);

            List<String> beforeTables = tables(sql, schema);
            Map<String, String> before = snapshot(sql, schema, beforeTables);
            var flyway = Flyway.configure().dataSource(source).schemas(schema).target("11").load();
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
            flyway.validate();
            assertThat(tables(sql, schema)).containsExactlyInAnyOrderElementsOf(
                    java.util.stream.Stream.concat(beforeTables.stream(), java.util.stream.Stream.of("reconciliation_runs", "reconciliation_observations")).toList());
            assertThat(snapshot(sql, schema, beforeTables)).isEqualTo(before);
            assertThat(sql.queryForObject("SELECT count(*) FROM " + schema + ".reconciliation_runs", Integer.class)).isZero();
            assertThat(sql.queryForObject("SELECT count(*) FROM " + schema + ".reconciliation_observations", Integer.class)).isZero();

            UUID run = UUID.randomUUID();
            insertRun(sql, schema, run, merchant);
            sql.update("INSERT INTO " + schema + ".reconciliation_observations(run_id,payment_id,merchant_id,primary_class,severity,reason_codes,evidence,explanation,observation) "
                    + "VALUES (?,?,?,'MATCHED','INFO','[]'::jsonb,'{}'::jsonb,'Authorization only','{}'::jsonb)", run, payment, merchant);
            assertThatThrownBy(() -> insertRun(sql, schema, run, merchant)).isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> sql.update("INSERT INTO " + schema + ".reconciliation_observations(run_id,payment_id,merchant_id,primary_class,severity,reason_codes,evidence,explanation,observation) "
                    + "VALUES (?,?,?,'MATCHED','INFO','[]'::jsonb,'{}'::jsonb,'Authorization only','{}'::jsonb)", run, UUID.randomUUID(),
                    UUID.fromString("33333333-3333-3333-3333-333333333333"))).isInstanceOf(DataIntegrityViolationException.class);
            assertThat(snapshot(sql, schema, beforeTables)).isEqualTo(before);
            assertThat(sql.queryForObject("SELECT count(*) FROM " + schema + ".reconciliation_runs", Integer.class)).isEqualTo(1);
            assertThat(sql.queryForObject("SELECT count(*) FROM " + schema + ".reconciliation_observations", Integer.class)).isEqualTo(1);
        } finally {
            sql.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    private static List<String> tables(JdbcTemplate sql, String schema) {
        return sql.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema=? AND table_type='BASE TABLE' AND table_name<>'flyway_schema_history' ORDER BY table_name", String.class, schema);
    }

    private static Map<String, String> snapshot(JdbcTemplate sql, String schema, List<String> tables) {
        var result = new TreeMap<String, String>();
        for (String table : tables)
            result.put(table, sql.queryForObject("SELECT COALESCE(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]'::jsonb)::text FROM " + schema + "." + table + " t", String.class));
        return result;
    }

    private static void insertRun(JdbcTemplate sql, String schema, UUID run, UUID merchant) {
        sql.update("INSERT INTO " + schema + ".reconciliation_runs(id,merchant_id,scope,ruleset,schema_version,build_revision,from_inclusive,cutoff_exclusive,grace_seconds,payment_limit,execution_limit_seconds,actor,reason,input_fingerprint,snapshot_observed_at,completed_at,consumer_group,cohort_count,counts,report) "
                + "VALUES (?,?,'AUTHORIZATION_V1','AUTHORIZATION_V1.0',1,'test-build',NOW()-INTERVAL '2 days',NOW()-INTERVAL '1 day',300,1000,15,'test-operator','VERIFICATION',?,NOW(),NOW(),'historical-group',1,'{\"MATCHED\":1}'::jsonb,'{}'::jsonb)",
                run, merchant, "a".repeat(64));
    }
}
