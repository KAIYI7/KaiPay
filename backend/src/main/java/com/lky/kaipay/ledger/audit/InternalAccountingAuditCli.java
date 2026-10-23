package com.lky.kaipay.ledger.audit;

import java.io.PrintStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLTimeoutException;
import java.time.Instant;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Explicit operator command; not an application endpoint or startup task. */
public final class InternalAccountingAuditCli {
    private InternalAccountingAuditCli() {}

    public static void main(String[] args) {
        int status = execute(args, System.getenv(), System.out, System.err);
        if (status != 0) System.exit(status);
    }

    static int execute(String[] args, Map<String, String> environment, PrintStream out, PrintStream err) {
        try {
            long started = System.nanoTime();
            if (args.length != 3) throw new IllegalArgumentException("Expected merchant and interval");
            var request = new AccountingAuditRequest(UUID.fromString(args[0]), Instant.parse(args[1]), Instant.parse(args[2]));
            String result;
            try (Connection c = open(environment, started)) {
                result = new InternalAccountingAudit().run(c, request, required(environment, "AUDIT_BUILD_REVISION"), started);
            }
            // Only emit a complete, bounded result after the read-only transaction/connection closes.
            InternalAccountingAudit.check(started);
            out.println(result);
            return 0;
        } catch (Exception failure) {
            err.println("Internal accounting audit did not return a complete report. No financial action was taken.");
            return 1;
        }
    }

    static Connection open(Map<String, String> environment, long started) throws Exception {
        String url = required(environment, "DB_URL");
        if (!url.startsWith("jdbc:postgresql:")
                || url.matches("(?is).*[?&](connectTimeout|socketTimeout|cancelSignalTimeout|loginTimeout)=.*")) {
            throw new IllegalArgumentException("Unsupported accounting audit connection options");
        }
        var properties = new Properties();
        properties.setProperty("user", required(environment, "DB_USERNAME"));
        properties.setProperty("password", required(environment, "DB_PASSWORD"));
        properties.setProperty("connectTimeout", "2");
        properties.setProperty("socketTimeout", "3");
        properties.setProperty("cancelSignalTimeout", "1");
        var expired = new AtomicBoolean();
        var acquired = new AtomicReference<Connection>();
        var future = new FutureTask<Connection>(() -> {
            Connection c = DriverManager.getConnection(url, properties);
            acquired.set(c);
            if (expired.get()) {
                try { c.abort(Runnable::run); } finally { acquired.set(null); }
                throw new SQLTimeoutException("Accounting audit connection deadline");
            }
            return c;
        });
        Thread worker = new Thread(future, "internal-accounting-connect");
        worker.setDaemon(true);
        worker.start();
        try {
            long remaining = InternalAccountingAudit.DEADLINE_NANOS - (System.nanoTime() - started);
            if (remaining <= 0) throw new TimeoutException();
            Connection c = future.get(remaining, TimeUnit.NANOSECONDS);
            acquired.set(null);
            return c;
        } catch (TimeoutException | InterruptedException failure) {
            expired.set(true);
            Connection c = acquired.getAndSet(null);
            if (c != null) c.abort(Runnable::run);
            future.cancel(true);
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new SQLTimeoutException("Accounting audit connection deadline");
        } catch (ExecutionException failure) {
            throw new IllegalStateException("Accounting audit connection unavailable");
        }
    }

    private static String required(Map<String, String> environment, String key) {
        String value = environment.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing accounting audit environment");
        return value;
    }
}
