package com.lky.kaipay.ledger.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.sql.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

/** Retained-data faults are injected only into this disposable PostgreSQL fixture. */
class InternalAccountingAuditIntegrationTest {
    static final PostgreSQLContainer DB = new PostgreSQLContainer("postgres:16-alpine");
    static final ObjectMapper JSON = new ObjectMapper();
    static final UUID A = id(1, 1), B = id(1, 2), CUSTOMER_A = id(2, 1), CUSTOMER_B = id(2, 2);
    static final UUID RECEIVABLE = id(4, 1), REVENUE = id(4, 2), PAYABLE = id(4, 3), FOREIGN_ACCOUNT = id(4, 4);
    static final Instant NOW = Instant.now().minusSeconds(7200).truncatedTo(ChronoUnit.SECONDS);
    static UUID id(int prefix, int value) { return UUID.fromString(String.format("%d0000000-0000-0000-0000-%012d", prefix, value)); }
    static Connection owner() throws SQLException { return DriverManager.getConnection(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword()); }
    static Connection reader() throws SQLException { return DriverManager.getConnection(DB.getJdbcUrl(), "accounting_reader", "synthetic-reader-only"); }
    static void sql(String query, Object... values) throws SQLException {
        try (var c = owner(); var p = c.prepareStatement(query)) {
            for (int i = 0; i < values.length; i++) p.setObject(i + 1, values[i]);
            p.execute();
        }
    }
    static String scalar(String query) throws SQLException {
        try (var c = owner(); var s = c.createStatement(); var r = s.executeQuery(query)) { r.next(); return r.getString(1); }
    }
    static AccountingAuditRequest request(UUID merchant) { return new AccountingAuditRequest(merchant, NOW.minusSeconds(3600), NOW.plusSeconds(3600)); }
    static JsonNode report(UUID merchant) throws Exception {
        try (var c = reader()) { return JSON.readTree(new InternalAccountingAudit().run(c, request(merchant), "synthetic-build")); }
    }
    static JsonNode observation(JsonNode report, String kind, UUID id) {
        for (var observation : report.path("observations")) {
            if (observation.path("kind").asText().equals(kind) && observation.path("id").asText().equals(id.toString())) return observation;
        }
        throw new AssertionError("Missing own observation " + kind + " " + id);
    }
    static Map<String, List<String>> sources() throws Exception {
        var all = new TreeMap<String, List<String>>();
        try (var c = owner(); var tables = c.createStatement(); var t = tables.executeQuery("SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename")) {
            while (t.next()) {
                String name = t.getString(1); var rows = new ArrayList<String>();
                try (var s = c.createStatement(); var r = s.executeQuery("SELECT to_jsonb(t)::text FROM \"" + name + "\" t ORDER BY to_jsonb(t)::text")) {
                    while (r.next()) rows.add(r.getString(1));
                }
                all.put(name, rows);
            }
        }
        return all;
    }
    @BeforeAll static void start() throws Exception {
        DB.start();
        Flyway.configure().dataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword()).load().migrate();
        sql("CREATE ROLE accounting_reader LOGIN PASSWORD 'synthetic-reader-only'; GRANT USAGE ON SCHEMA public TO accounting_reader; GRANT SELECT ON merchants,payments,refunds,journals,ledger_entries,accounts TO accounting_reader;");
    }
    @AfterAll static void stop() { DB.stop(); }
    @BeforeEach void fixture() throws Exception {
        sql("TRUNCATE merchants CASCADE");
        sql("INSERT INTO merchants(id,name,api_key_hash) VALUES (?,'PRIVATE_MERCHANT_A','synthetic-a'),(?,'PRIVATE_MERCHANT_B','synthetic-b')", A, B);
        sql("INSERT INTO customers(id,merchant_id,email,full_name) VALUES (?,?,'a@example.test','PRIVATE_CUSTOMER'),(?,?,'b@example.test','PRIVATE_CUSTOMER')", CUSTOMER_A, A, CUSTOMER_B, B);
        sql("INSERT INTO accounts(id,merchant_id,account_number,account_name,account_type,currency) VALUES (?,NULL,'1000-CUSTOMER-RECEIVABLE','PRIVATE_ACCOUNT','ASSET','MYR'),(?,NULL,'4000-PLATFORM-FEE-REVENUE','PRIVATE_ACCOUNT','REVENUE','MYR'),(?,?,'2000-MERCHANT-'||?||'-LIABILITY','PRIVATE_ACCOUNT','LIABILITY','MYR'),(?,?,'FOREIGN_PRIVATE_ACCOUNT','PRIVATE_ACCOUNT','LIABILITY','USD')", RECEIVABLE, REVENUE, PAYABLE, A, A.toString(), FOREIGN_ACCOUNT, B);
    }
    static UUID payment(int n, String status, long amount, String currency) throws Exception {
        UUID p = id(3, n);
        sql("INSERT INTO payments(id,merchant_id,customer_id,amount_cents,currency,status,idempotency_key,metadata,created_at,updated_at) VALUES (?,?,?,?,?,?,?,'{\"private\":\"PRIVATE_METADATA\"}',?,?)", p, A, CUSTOMER_A, amount, currency, status, "PRIVATE_KEY_" + n, Timestamp.from(NOW), Timestamp.from(NOW));
        return p;
    }
    static UUID refund(int n, UUID payment, long amount, String currency, String status) throws Exception {
        UUID r = id(5, n);
        sql("INSERT INTO refunds(id,payment_id,merchant_id,amount_cents,currency,status,reason,idempotency_key,created_at) VALUES (?,?,?,?,?,?,'PRIVATE_REFUND_REASON',?,?)", r, payment, A, amount, currency, status, "PRIVATE_REFUND_KEY_" + n, Timestamp.from(NOW));
        return r;
    }
    static UUID journal(int n, String type, String source, UUID merchant) throws Exception {
        UUID j = id(6, n);
        sql("INSERT INTO journals(id,journal_number,source_type,source_id,event_id,merchant_id,description,posted_at) VALUES (?,?,?,?,?,?,'PRIVATE_DESCRIPTION',?)", j, "PRIVATE_JOURNAL_" + n, type, source, UUID.randomUUID(), merchant, Timestamp.from(NOW));
        return j;
    }
    static void entry(UUID journal, UUID account, String direction, long amount, String currency) throws Exception {
        sql("INSERT INTO ledger_entries(journal_id,account_id,entry_type,amount_cents,currency) VALUES (?,?,?,?,?)", journal, account, direction, amount, currency);
    }
    static UUID capture(int n, UUID payment, long amount, String currency) throws Exception {
        UUID j = journal(n, "PAYMENT_CAPTURE", payment.toString(), A);
        entry(j, RECEIVABLE, "DEBIT", amount, currency);
        entry(j, PAYABLE, "CREDIT", amount, currency);
        return j;
    }
    static UUID refundJournal(int n, UUID refund, long amount, String currency) throws Exception {
        UUID j = journal(n, "PAYMENT_REFUND", refund.toString(), A);
        entry(j, PAYABLE, "DEBIT", amount, currency);
        entry(j, RECEIVABLE, "CREDIT", amount, currency);
        return j;
    }

    @Test void actualFeeShapedCaptureAndCompletedPartialThenFullRefundAreStructurallyConsistent() throws Exception {
        var p = payment(1, "REFUNDED", 5000, "MYR");
        var c = journal(1, "PAYMENT_CAPTURE", p.toString(), A);
        entry(c, RECEIVABLE, "DEBIT", 5000, "MYR"); entry(c, PAYABLE, "CREDIT", 4825, "MYR"); entry(c, REVENUE, "CREDIT", 175, "MYR");
        var first = refund(1, p, 2000, "MYR", "COMPLETED");
        var r1 = journal(2, "PAYMENT_REFUND", first.toString(), A);
        entry(r1, PAYABLE, "DEBIT", 1942, "MYR"); entry(r1, REVENUE, "DEBIT", 58, "MYR"); entry(r1, RECEIVABLE, "CREDIT", 2000, "MYR");
        var second = refund(2, p, 3000, "MYR", "COMPLETED");
        var r2 = journal(3, "PAYMENT_REFUND", second.toString(), A);
        entry(r2, PAYABLE, "DEBIT", 2913, "MYR"); entry(r2, REVENUE, "DEBIT", 87, "MYR"); entry(r2, RECEIVABLE, "CREDIT", 3000, "MYR");
        var before = sources(); var report = report(A);
        assertThat(report.path("hasInconsistencies").asBoolean()).isFalse();
        assertThat(report.path("classificationCounts").path("CONSISTENT").asInt()).isEqualTo(6);
        assertThat(sources()).isEqualTo(before);
        assertThat(report.toString()).doesNotContain("PRIVATE_", "example.test", "description", "idempotency", "eventId", "accountNumber");
    }
    @Test void zeroOmittedLinesAndHistoricalUnitsDoNotInventFeePolicy() throws Exception {
        var p = payment(1, "REFUNDED", 1, "USD");
        sql("UPDATE accounts SET currency='USD' WHERE id IN (?, ?, ?)", RECEIVABLE, REVENUE, PAYABLE);
        var c = journal(1, "PAYMENT_CAPTURE", p.toString(), A);
        entry(c, RECEIVABLE, "DEBIT", 1, "USD"); entry(c, REVENUE, "CREDIT", 1, "USD");
        var r = refund(1, p, 1, "USD", "COMPLETED"); refundJournal(2, r, 1, "USD");
        var report = report(A);
        assertThat(report.path("hasInconsistencies").asBoolean()).isFalse();
        assertThat(report.toString()).contains("USD").doesNotContain("\"currency\":\"MYR\"");
        assertThat(observation(report, "JOURNAL", c).path("evidence").path("sourceAmountMinor").asText()).isEqualTo("1");
    }
    @ParameterizedTest @ValueSource(strings = {"missing", "duplicate", "empty", "unbalanced", "gross", "direction", "entry-type", "account-owner", "account-role", "account-currency", "entry-currency", "foreign-journal", "null-journal-owner"})
    void constraintsPermitFaultsWhichAuditDetects(String fault) throws Exception {
        var p = payment(1, "CAPTURED", 5000, "MYR"); var j = capture(1, p, 5000, "MYR");
        String reason;
        switch (fault) {
            case "missing" -> { sql("DELETE FROM ledger_entries WHERE journal_id=?", j); sql("DELETE FROM journals WHERE id=?", j); reason="MISSING_CAPTURE_JOURNAL"; }
            case "duplicate" -> { capture(2, p, 5000, "MYR"); reason="DUPLICATE_CAPTURE_JOURNAL"; }
            case "empty" -> { sql("DELETE FROM ledger_entries WHERE journal_id=?", j); reason="EMPTY_JOURNAL"; }
            case "unbalanced" -> { sql("UPDATE ledger_entries SET amount_cents=4999 WHERE journal_id=? AND entry_type='CREDIT'", j); reason="UNBALANCED_JOURNAL"; }
            case "gross" -> { sql("UPDATE ledger_entries SET amount_cents=4999 WHERE journal_id=?", j); reason="SOURCE_GROSS_MISMATCH"; }
            case "direction" -> { sql("UPDATE ledger_entries SET entry_type=CASE WHEN entry_type='DEBIT' THEN 'CREDIT' ELSE 'DEBIT' END WHERE journal_id=?", j); reason="INVALID_ACCOUNT_ROLE"; }
            case "entry-type" -> { sql("UPDATE ledger_entries SET entry_type='PRIVATE' WHERE journal_id=? AND entry_type='CREDIT'", j); reason="INVALID_ENTRY_TYPE"; }
            case "account-owner" -> { sql("UPDATE ledger_entries SET account_id=? WHERE journal_id=? AND entry_type='CREDIT'", FOREIGN_ACCOUNT, j); reason="ACCOUNT_OWNER_MISMATCH"; }
            case "account-role" -> { sql("UPDATE accounts SET account_type='EXPENSE' WHERE id=?", PAYABLE); reason="INVALID_ACCOUNT_ROLE"; }
            case "account-currency" -> { sql("UPDATE accounts SET currency='USD' WHERE id=?", PAYABLE); reason="ACCOUNT_CURRENCY_MISMATCH"; }
            case "entry-currency" -> { sql("UPDATE ledger_entries SET currency='USD' WHERE journal_id=? AND entry_type='CREDIT'", j); reason="SOURCE_CURRENCY_MISMATCH"; }
            case "foreign-journal" -> { sql("UPDATE journals SET merchant_id=? WHERE id=?", B, j); reason="JOURNAL_OWNER_MISMATCH"; }
            default -> { sql("UPDATE journals SET merchant_id=NULL WHERE id=?", j); reason="JOURNAL_OWNER_MISMATCH"; }
        }
        var before = sources(); var report=report(A);
        assertThat(report.path("hasInconsistencies").asBoolean()).isTrue();
        assertThat(report.toString()).contains(reason).doesNotContain("PRIVATE_", B.toString(), FOREIGN_ACCOUNT.toString());
        assertThat(sources()).isEqualTo(before);
    }
    @Test void journalDrivenCohortFindsOrphanMalformedAndUnsupportedSourcesWithoutEchoingThem() throws Exception {
        var orphan = journal(1,"PAYMENT_CAPTURE",UUID.randomUUID().toString(),A);
        var malformed = journal(2,"PAYMENT_REFUND","PRIVATE_INVALID_SOURCE_ID",A);
        var other = journal(3,"PRIVATE_UNSUPPORTED_SOURCE","PRIVATE_SOURCE",A);
        for (var j : List.of(orphan,malformed,other)) { entry(j,RECEIVABLE,"DEBIT",5000,"MYR"); entry(j,PAYABLE,"CREDIT",5000,"MYR"); }
        var report=report(A);
        assertThat(observation(report,"JOURNAL",orphan).path("reasonCodes").toString()).contains("MISSING_SOURCE");
        assertThat(observation(report,"JOURNAL",malformed).path("reasonCodes").toString()).contains("MISSING_SOURCE");
        assertThat(observation(report,"JOURNAL",other).path("classification").asText()).isEqualTo("NOT_ASSESSED");
        assertThat(report.toString()).doesNotContain("PRIVATE_");
    }
    @ParameterizedTest @ValueSource(strings={"over-total","state","currency","foreign-refund","foreign-parent","missing-journal","duplicate-journal","not-completed","pending-payment"})
    void completedRefundRelationshipsAreComparedWithoutChangingFinancialPolicy(String fault) throws Exception {
        var p=payment(1,"REFUNDED",5000,"MYR");capture(1,p,5000,"MYR");var r=refund(1,p,5000,"MYR","COMPLETED");var j=refundJournal(2,r,5000,"MYR");
        String reason;
        switch(fault) {
            case "over-total" -> {sql("UPDATE refunds SET amount_cents=5001 WHERE id=?",r);reason="REFUND_TOTAL_EXCEEDS_PAYMENT";}
            case "state" -> {sql("UPDATE payments SET status='CAPTURED' WHERE id=?",p);reason="PAYMENT_REFUND_STATUS_MISMATCH";}
            case "currency" -> {sql("UPDATE refunds SET currency='USD' WHERE id=?",r);reason="REFUND_CURRENCY_MISMATCH";}
            case "foreign-refund" -> {sql("UPDATE refunds SET merchant_id=? WHERE id=?",B,r);reason="REFUND_OWNER_MISMATCH";}
            case "foreign-parent" -> {sql("UPDATE payments SET merchant_id=?,customer_id=? WHERE id=?",B,CUSTOMER_B,p);reason="PAYMENT_OWNER_MISMATCH";}
            case "missing-journal" -> {sql("DELETE FROM ledger_entries WHERE journal_id=?",j);sql("DELETE FROM journals WHERE id=?",j);reason="MISSING_REFUND_JOURNAL";}
            case "duplicate-journal" -> {refundJournal(3,r,5000,"MYR");reason="DUPLICATE_REFUND_JOURNAL";}
            case "not-completed" -> {sql("UPDATE refunds SET status='FAILED' WHERE id=?",r);reason="UNEXPECTED_REFUND_JOURNAL";}
            default -> {sql("UPDATE payments SET status='REFUND_PENDING' WHERE id=?",p);reason="PAYMENT_REFUND_PENDING";}
        }
        var before=sources();var report=report(A);
        assertThat(report.toString()).contains(reason).doesNotContain("PRIVATE_",B.toString(),FOREIGN_ACCOUNT.toString());
        assertThat(sources()).isEqualTo(before);
        if(fault.equals("pending-payment")) assertThat(observation(report,"PAYMENT",p).path("classification").asText()).isEqualTo("NOT_ASSESSED");
    }
    @Test void closureIncludesOldParentsAndRelatedRowsOutsideSeedWindow() throws Exception {
        var p=payment(1,"REFUNDED",5000,"MYR");capture(1,p,5000,"MYR");var r=refund(1,p,5000,"MYR","COMPLETED");refundJournal(2,r,5000,"MYR");
        sql("UPDATE payments SET created_at=?",Timestamp.from(NOW.minusSeconds(86400)));
        sql("UPDATE journals SET posted_at=?",Timestamp.from(NOW.minusSeconds(86400)));
        var report=report(A);assertThat(report.path("coverage").path("paymentCount").asInt()).isEqualTo(1);
        assertThat(report.path("coverage").path("journalCount").asInt()).isEqualTo(2);
        assertThat(report.path("hasInconsistencies").asBoolean()).isFalse();
        sql("UPDATE refunds SET created_at=?",Timestamp.from(NOW.minusSeconds(86400)));
        assertThat(report(A).path("observations")).isEmpty();
    }
    @Test void independentlyScopedReportsNeverContainOtherMerchantValues() throws Exception {
        var p=payment(1,"CAPTURED",5000,"MYR");capture(1,p,5000,"MYR");
        var report=report(B);assertThat(report.path("observations")).isEmpty();assertThat(report.toString()).doesNotContain(p.toString(),A.toString());
        sql("UPDATE journals SET source_id=?,source_type='PAYMENT_REFUND' WHERE merchant_id=?",id(5,99).toString(),A);
        assertThat(report(B).path("observations")).isEmpty();
        try(var c=reader()) {
            assertThatThrownBy(()->new InternalAccountingAudit().run(c,request(UUID.randomUUID()),"build")).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void numericSumsNeverWrapBigintAndFeeAllocationIsExplicitlyOutsideScope() throws Exception {
        var p=payment(1,"CAPTURED",Long.MAX_VALUE,"MYR");var j=capture(1,p,Long.MAX_VALUE,"MYR");
        entry(j,RECEIVABLE,"DEBIT",Long.MAX_VALUE,"MYR");entry(j,PAYABLE,"CREDIT",Long.MAX_VALUE,"MYR");
        var report=report(A);var obs=observation(report,"JOURNAL",j);
        assertThat(obs.path("reasonCodes").toString()).contains("SOURCE_GROSS_MISMATCH").doesNotContain("UNBALANCED_JOURNAL");
        assertThat(obs.path("evidence").path("receivableAmountMinor").asText()).isEqualTo("18446744073709551614");
    }
    @ParameterizedTest @ValueSource(strings={"payments","refunds","journals","entries","closure-refunds","closure-journals"})
    void everyFanoutLimitRejectsWholeReport(String area) throws Exception {
        var p=payment(1,"CAPTURED",5000,"MYR");var j=capture(1,p,5000,"MYR");
        switch(area) {
            case "payments" -> sql("INSERT INTO payments(id,merchant_id,customer_id,amount_cents,currency,status,idempotency_key,created_at) SELECT gen_random_uuid(),?,?,5000,'MYR','AUTHORIZED',g::text,? FROM generate_series(1,1001)g",A,CUSTOMER_A,Timestamp.from(NOW));
            case "refunds","closure-refunds" -> sql("INSERT INTO refunds(payment_id,merchant_id,amount_cents,currency,status,idempotency_key,created_at) SELECT ?,?,1,'MYR','COMPLETED',g::text,? FROM generate_series(1,1001)g",p,A,Timestamp.from(area.equals("refunds")?NOW:NOW.minusSeconds(86400)));
            case "journals","closure-journals" -> sql("INSERT INTO journals(journal_number,source_type,source_id,merchant_id,description,posted_at) SELECT g::text,'PAYMENT_CAPTURE',?,?,'PRIVATE',? FROM generate_series(1,1001)g",p.toString(),A,Timestamp.from(area.equals("journals")?NOW:NOW.minusSeconds(86400)));
            default -> sql("INSERT INTO ledger_entries(journal_id,account_id,entry_type,amount_cents,currency) SELECT ?,?,'DEBIT',1,'MYR' FROM generate_series(1,10001)",j,RECEIVABLE);
        }
        var before=sources();try(var c=reader()) {
            assertThatThrownBy(()->new InternalAccountingAudit().run(c,request(A),"build")).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(sources()).isEqualTo(before);
    }
    @Test void stableSnapshotDoesNotMixConcurrentlyChangedSources() throws Exception {
        var p=payment(1,"CAPTURED",5000,"MYR");capture(1,p,5000,"MYR");
        var snapshot=new CountDownLatch(1);var release=new CountDownLatch(1);
        var audit=new InternalAccountingAudit(new InternalAccountingAudit.Hooks(){@Override public void afterSnapshot()throws Exception{snapshot.countDown();assertThat(release.await(5,TimeUnit.SECONDS)).isTrue();}});
        try(var pool=Executors.newSingleThreadExecutor()) {
            var future=pool.submit(()->{try(var c=reader()){return JSON.readTree(audit.run(c,request(A),"build"));}});
            assertThat(snapshot.await(5,TimeUnit.SECONDS)).isTrue();
            try{sql("UPDATE payments SET amount_cents=6000 WHERE id=?",p);}finally{release.countDown();}
            assertThat(future.get(5,TimeUnit.SECONDS).path("hasInconsistencies").asBoolean()).isFalse();
        }
        assertThat(report(A).path("hasInconsistencies").asBoolean()).isTrue();
    }
    @Test void deadlineActivelyAbortsConnectionAndNeverReturnsPartialReport() throws Exception {
        var audit=new InternalAccountingAudit(new InternalAccountingAudit.Hooks(){@Override public void afterSnapshot()throws Exception{Thread.sleep(900);}});
        try(var c=reader()) {
            long start=System.nanoTime()-TimeUnit.MILLISECONDS.toNanos(14500);
            assertThatThrownBy(()->audit.run(c,request(A),"build",start)).isInstanceOf(SQLTimeoutException.class);
            assertThat(c.isClosed()).isTrue();
        }
    }
    @Test void readerLacksSourceWritePrivilegesAndAuditUsesReadOnlyConnection() throws Exception {
        var before=sources();
        var audit=new InternalAccountingAudit(new InternalAccountingAudit.Hooks(){@Override public void afterSnapshot()throws Exception{
            try(var c=reader();var s=c.createStatement()){assertThatThrownBy(()->s.executeUpdate("UPDATE payments SET amount_cents=1 WHERE false")).isInstanceOf(SQLException.class).extracting(e->((SQLException)e).getSQLState()).isEqualTo("42501");}
        }});
        try(var c=owner()){audit.run(c,request(A),"build");assertThat(c.isReadOnly()).isTrue();}
        assertThat(sources()).isEqualTo(before);
    }
    @Test void ownerCannotWriteInsideActualAuditSnapshot() throws Exception {
        var before=sources();
        try(var c=owner()) {
            var audit=new InternalAccountingAudit(new InternalAccountingAudit.Hooks(){@Override public void afterSnapshot()throws Exception{
                try(var s=c.createStatement()){s.executeUpdate("UPDATE payments SET amount_cents=1 WHERE false");}
            }});
            assertThatThrownBy(()->audit.run(c,request(A),"build")).isInstanceOf(SQLException.class)
                    .extracting(e->((SQLException)e).getSQLState()).isEqualTo("25006");
        }
        assertThat(sources()).isEqualTo(before);
    }
    @Test void ownedJournalsReferencingActualForeignSourcesRedactAllForeignScalars() throws Exception {
        var p=payment(1,"FAILED",87654321,"USD");
        sql("UPDATE payments SET merchant_id=?,customer_id=? WHERE id=?",B,CUSTOMER_B,p);
        var r=refund(1,p,76543210,"USD","FAILED");sql("UPDATE refunds SET merchant_id=? WHERE id=?",B,r);
        var capture=journal(1,"PAYMENT_CAPTURE",p.toString(),A);
        var refund=journal(2,"PAYMENT_REFUND",r.toString(),A);
        for(var j:List.of(capture,refund)){entry(j,RECEIVABLE,"DEBIT",5000,"MYR");entry(j,PAYABLE,"CREDIT",5000,"MYR");}
        var before=sources();var report=report(A);
        for(var j:List.of(capture,refund)) {
            var o=observation(report,"JOURNAL",j);
            assertThat(o.path("reasonCodes").toString()).contains("SOURCE_OWNER_MISMATCH");
            assertThat(o.path("evidence").path("sourceAmountMinor").isNull()).isTrue();
        }
        assertThat(report.toString()).doesNotContain(p.toString(),r.toString(),B.toString(),"87654321","76543210","USD","PRIVATE_");
        assertThat(sources()).isEqualTo(before);
    }
    @Test void lockTimeoutBoundsActualBlockedReadWithoutPartialResult() throws Exception {
        var p=payment(1,"CAPTURED",5000,"MYR");capture(1,p,5000,"MYR");var before=sources();
        try(var locker=owner();var statement=locker.createStatement()) {
            locker.setAutoCommit(false);statement.execute("LOCK TABLE ledger_entries IN ACCESS EXCLUSIVE MODE");
            long started=System.nanoTime();
            try(var c=reader()) {
                assertThatThrownBy(()->new InternalAccountingAudit().run(c,request(A),"build"))
                        .isInstanceOf(SQLException.class).extracting(e->((SQLException)e).getSQLState()).isEqualTo("55P03");
            }
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)).isLessThan(4000);
            locker.rollback();
        }
        assertThat(sources()).isEqualTo(before);
    }
    @Test void statementTimeoutCancelsActualServerDelayInsideSnapshot() throws Exception {
        try(var c=owner()) {
            var audit=new InternalAccountingAudit(new InternalAccountingAudit.Hooks(){@Override public void afterSnapshot()throws Exception{
                try(var s=c.createStatement()){s.execute("SELECT pg_sleep(5)");}
            }});
            long start=System.nanoTime();
            assertThatThrownBy(()->audit.run(c,request(A),"build")).isInstanceOf(SQLException.class)
                    .extracting(e->((SQLException)e).getSQLState()).isEqualTo("57014");
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)).isLessThan(4500);
        }
    }
    @Test void supportedMaximumCohortReturnsAllRecordsAndRecordsRepresentativePlan() throws Exception {
        sql("INSERT INTO payments(id,merchant_id,customer_id,amount_cents,currency,status,idempotency_key,created_at) SELECT gen_random_uuid(),?,?,5000,'MYR','AUTHORIZED',g::text,? FROM generate_series(1,1000)g",A,CUSTOMER_A,Timestamp.from(NOW));
        var report=report(A);
        assertThat(report.path("coverage").path("paymentCount").asInt()).isEqualTo(1000);
        assertThat(report.path("observations").size()).isEqualTo(1000);
        assertThat(report.path("classificationCounts").path("NOT_APPLICABLE").asInt()).isEqualTo(1000);
        String query;
        try(var stream=InternalAccountingAudit.class.getResourceAsStream("/audit/internal-accounting.sql")){query=new String(stream.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}
        try(var c=reader();var s=c.prepareStatement("EXPLAIN (ANALYZE,BUFFERS,FORMAT JSON) "+query)) {
            s.setObject(1,A);s.setTimestamp(2,Timestamp.from(request(A).fromInclusive()));s.setTimestamp(3,Timestamp.from(request(A).cutoffExclusive()));
            try(var rows=s.executeQuery()){rows.next();var plan=JSON.readTree(rows.getString(1));
                System.out.println("ACCOUNTING_AUDIT_1000_ROOT_PLAN executionMs="+plan.get(0).path("Execution Time")+" sharedHitBlocks="+plan.get(0).path("Plan").path("Shared Hit Blocks"));
            }
        }
    }
    @Test void interruptedRunAbortsAndPreservesInterruptFlag() throws Exception {
        var before=sources();
        var audit=new InternalAccountingAudit(new InternalAccountingAudit.Hooks(){@Override public void afterSnapshot(){Thread.currentThread().interrupt();}});
        try(var c=reader()) {
            try {assertThatThrownBy(()->audit.run(c,request(A),"build")).isInstanceOf(SQLTimeoutException.class);assertThat(Thread.currentThread().isInterrupted()).isTrue();assertThat(c.isClosed()).isTrue();}
            finally {Thread.interrupted();}
        }
        assertThat(sources()).isEqualTo(before);
    }
    @Test void cliReturnsOnlyCompleteJsonAndSanitizesInvalidOrFailedRequests() throws Exception {
        var environment=new HashMap<String,String>();environment.put("DB_URL",DB.getJdbcUrl());environment.put("DB_USERNAME","accounting_reader");environment.put("DB_PASSWORD","synthetic-reader-only");environment.put("AUDIT_BUILD_REVISION","synthetic-build");
        var out=new ByteArrayOutputStream();var err=new ByteArrayOutputStream();
        var q=request(A);String[] args={A.toString(),q.fromInclusive().toString(),q.cutoffExclusive().toString()};
        assertThat(InternalAccountingAuditCli.execute(args,environment,new PrintStream(out),new PrintStream(err))).isZero();
        assertThat(JSON.readTree(out.toString()).path("scope").asText()).isEqualTo("INTERNAL_ACCOUNTING");assertThat(err.toString()).isEmpty();
        out.reset();environment.put("DB_PASSWORD","PRIVATE_PASSWORD_SENTINEL");
        assertThat(InternalAccountingAuditCli.execute(args,environment,new PrintStream(out),new PrintStream(err))).isEqualTo(1);
        assertThat(out.toString()).isEmpty();assertThat(err.toString()).doesNotContain("PRIVATE_",DB.getJdbcUrl(),"SQLException");
        assertThat(InternalAccountingAuditCli.execute(new String[]{"PRIVATE_INPUT"},environment,new PrintStream(out),new PrintStream(err))).isEqualTo(1);
    }
    @Test void requestBoundsFreshConnectionFutureCutoffAndTimeoutOptionsFailClosed() throws Exception {
        assertThatThrownBy(()->new AccountingAuditRequest(A,NOW,NOW.plusSeconds(604801))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new AccountingAuditRequest(A,NOW,NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new AccountingAuditRequest(A,NOW.plusNanos(1),NOW.plusSeconds(1))).isInstanceOf(IllegalArgumentException.class);
        try(var c=reader()){c.setAutoCommit(false);assertThatThrownBy(()->new InternalAccountingAudit().run(c,request(A),"build")).isInstanceOf(IllegalArgumentException.class);}
        try(var c=reader()){var future=Instant.now().plusSeconds(60);assertThatThrownBy(()->new InternalAccountingAudit().run(c,new AccountingAuditRequest(A,future,future.plusSeconds(60)),"build")).isInstanceOf(IllegalArgumentException.class);}
        assertThatThrownBy(()->InternalAccountingAuditCli.open(Map.of("DB_URL",DB.getJdbcUrl()+"?socketTimeout=100"),System.nanoTime())).isInstanceOf(IllegalArgumentException.class);
    }
}
