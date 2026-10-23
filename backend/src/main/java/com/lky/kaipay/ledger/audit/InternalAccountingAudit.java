package com.lky.kaipay.ledger.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Standalone, transient SELECT-only diagnostics. Never invokes financial services or Spring. */
public final class InternalAccountingAudit {
    static final long DEADLINE_NANOS = TimeUnit.SECONDS.toNanos(15);
    private static final int MAX_JSON_BYTES = 4 * 1024 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SQL = loadSql();
    interface Hooks {
        default void afterSnapshot() throws Exception {}
        default void afterQuery() throws Exception {}
    }
    private final Hooks hooks;

    public InternalAccountingAudit() { this(new Hooks() {}); }
    InternalAccountingAudit(Hooks hooks) { this.hooks = hooks; }

    public String run(Connection connection, AccountingAuditRequest request, String buildRevision) throws Exception {
        return run(connection, request, buildRevision, System.nanoTime());
    }

    String run(Connection c, AccountingAuditRequest request, String build, long started) throws Exception {
        if (!c.getAutoCommit()) throw new IllegalArgumentException("A fresh operator connection is required");
        if (build == null || !build.matches("[A-Za-z0-9._-]{1,100}")) {
            throw new IllegalArgumentException("Invalid accounting audit build context");
        }
        check(started);
        var timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "internal-accounting-deadline");
            thread.setDaemon(true);
            return thread;
        });
        var watchdog = timer.schedule(() -> abort(c),
                Math.max(1, DEADLINE_NANOS - (System.nanoTime() - started)), TimeUnit.NANOSECONDS);
        try {
            c.setReadOnly(true);
            c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            c.setAutoCommit(false);
            for (String setting : new String[]{"SET LOCAL statement_timeout='2s'",
                    "SET LOCAL lock_timeout='500ms'", "SET LOCAL idle_in_transaction_session_timeout='15s'",
                    "SET LOCAL search_path=pg_catalog,public"}) {
                try (var p = prepare(c, setting, started)) { p.execute(); }
            }
            Instant observed;
            try (var p = prepare(c, "SELECT clock_timestamp(),current_setting('transaction_read_only'),"
                    + "current_setting('transaction_isolation')", started); var r = p.executeQuery()) {
                r.next();
                observed = r.getTimestamp(1).toInstant();
                if (!"on".equals(r.getString(2)) || !"repeatable read".equals(r.getString(3))) {
                    throw new IllegalStateException("Read-only snapshot unavailable");
                }
            }
            if (request.cutoffExclusive().isAfter(observed)) {
                throw new IllegalArgumentException("Accounting audit cutoff is in the future");
            }
            hooks.afterSnapshot();
            check(started);
            String raw;
            try (var p = prepare(c, SQL, started)) {
                p.setObject(1, request.merchantId());
                p.setTimestamp(2, Timestamp.from(request.fromInclusive()));
                p.setTimestamp(3, Timestamp.from(request.cutoffExclusive()));
                try (var r = p.executeQuery()) {
                    if (!r.next()) throw new IllegalStateException("Accounting audit result unavailable");
                    raw = r.getString("report_json");
                    if (r.next()) throw new IllegalStateException("Unexpected accounting audit result");
                }
            }
            hooks.afterQuery();
            check(started);
            if (raw == null || raw.getBytes(StandardCharsets.UTF_8).length > MAX_JSON_BYTES) {
                throw new IllegalArgumentException("Accounting audit output exceeds its bound");
            }
            var parsed = JSON.readTree(raw);
            if (!(parsed instanceof ObjectNode report) || !report.path("merchantExists").asBoolean()
                    || report.path("limitExceeded").asBoolean(true) || !report.path("cutoffValid").asBoolean()
                    || !"COMPLETE".equals(report.path("status").asText())) {
                throw new IllegalArgumentException("Accounting audit scope unavailable or oversized");
            }
            report.remove(java.util.List.of("merchantExists", "limitExceeded", "cutoffValid"));
            report.put("snapshotObservedAt", observed.toString());
            report.put("buildRevision", build);
            report.put("executionLimitSeconds", 15);
            report.put("statementTimeoutSeconds", 2);
            report.put("assessment", "Internal structural consistency only; fee policy, provider outcomes and bank settlement are not assessed.");
            var counts = report.putObject("classificationCounts");
            for (String name : java.util.List.of("CONSISTENT", "INCONSISTENT", "NOT_ASSESSED", "NOT_APPLICABLE")) {
                counts.put(name, 0);
            }
            for (var observation : report.path("observations")) {
                String classification = observation.path("classification").asText();
                if (!counts.has(classification)) throw new IllegalStateException("Invalid audit classification");
                counts.put(classification, counts.path(classification).asInt() + 1);
            }
            report.put("hasInconsistencies", counts.path("INCONSISTENT").asInt() > 0);
            report.put("hasUnassessedRecords", counts.path("NOT_ASSESSED").asInt() > 0);
            String result = JSON.writeValueAsString(report);
            if (result.getBytes(StandardCharsets.UTF_8).length > MAX_JSON_BYTES) {
                throw new IllegalArgumentException("Accounting audit output exceeds its bound");
            }
            check(started);
            network(c, started);
            c.rollback();
            check(started);
            return result;
        } catch (Exception failure) {
            try {
                network(c, started);
                c.rollback();
            } catch (SQLException cleanupFailure) {
                abort(c);
            }
            throw failure;
        } finally {
            watchdog.cancel(false);
            timer.shutdownNow();
        }
    }

    static void check(long start) throws SQLTimeoutException {
        if (Thread.currentThread().isInterrupted() || System.nanoTime() - start >= DEADLINE_NANOS) {
            throw new SQLTimeoutException("Accounting audit deadline");
        }
    }
    private static void network(Connection c, long start) throws SQLException {
        check(start);
        long left = TimeUnit.NANOSECONDS.toMillis(DEADLINE_NANOS - (System.nanoTime() - start));
        c.setNetworkTimeout(Runnable::run, (int) Math.max(1, Math.min(2500, left)));
    }
    private static PreparedStatement prepare(Connection c, String sql, long start) throws SQLException {
        network(c, start);
        return c.prepareStatement(sql);
    }
    private static void abort(Connection c) {
        try { c.abort(Runnable::run); } catch (SQLException ignored) { /* Caller still closes its connection. */ }
    }
    private static String loadSql() {
        try (var stream = InternalAccountingAudit.class.getResourceAsStream("/audit/internal-accounting.sql")) {
            if (stream == null) throw new IllegalStateException("Accounting audit query unavailable");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) { throw new IllegalStateException("Accounting audit query unavailable"); }
    }
}
