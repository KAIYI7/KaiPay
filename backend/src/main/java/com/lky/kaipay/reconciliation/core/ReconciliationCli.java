package com.lky.kaipay.reconciliation.core;

import java.sql.DriverManager;
import java.time.Instant;
import java.util.Properties;
import java.util.UUID;
import java.sql.Connection;
import java.sql.SQLTimeoutException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Explicit manual audit command; does not initialize the application or its schedulers. */
public final class ReconciliationCli {
    private ReconciliationCli() {}
    public static void main(String[] args){
        try{
            if(args.length!=8)throw new IllegalArgumentException("Expected eight reconciliation arguments");
            var q=new RunRequest(UUID.fromString(args[0]),UUID.fromString(args[1]),args[2],args[3],Instant.parse(args[4]),Instant.parse(args[5]),args[6],args[7]);
            long start=System.nanoTime();
            var properties=new Properties();properties.setProperty("user",required("DB_USERNAME"));properties.setProperty("password",required("DB_PASSWORD"));
            properties.setProperty("connectTimeout","2");properties.setProperty("socketTimeout","3");properties.setProperty("cancelSignalTimeout","1");
            String group=System.getenv().getOrDefault("RECON_CONSUMER_GROUP","kaipay-payment-processor-group");
            String build=System.getenv("RECON_BUILD_REVISION");
            try(var c=open(required("DB_URL"),properties,start)){
                var result=new ReconciliationRunner().run(c,q,group,build,start);
                System.out.println("Reconciliation report "+q.runId()+" confirmed"+(result.replayed()?" (retained replay).":"."));
            }
        }catch(Exception e){
            // Never render SQLException messages, row values, credentials or arbitrary arguments.
            System.err.println("Reconciliation did not return a confirmed report. Retry the same run identity to inspect committed state.");
            System.exit(1);
        }
    }
    static Connection open(String url,Properties properties,long start)throws Exception{
        // URL options override pgJDBC Properties. Refuse competing timeout definitions.
        if(url.matches("(?is).*[?&](connectTimeout|socketTimeout|cancelSignalTimeout|loginTimeout)=.*"))
            throw new IllegalArgumentException("Conflicting database timeout option");
        var expired=new AtomicBoolean();var acquired=new AtomicReference<Connection>();
        var future=new FutureTask<Connection>(()->{
            Connection c=DriverManager.getConnection(url,properties);acquired.set(c);
            if(expired.get()){try{c.abort(Runnable::run);}finally{acquired.set(null);}throw new SQLTimeoutException("Reconciliation connection deadline");}
            return c;
        });
        var thread=new Thread(future,"reconciliation-connect");thread.setDaemon(true);thread.start();
        try{
            long remaining=TimeUnit.SECONDS.toNanos(15)-(System.nanoTime()-start);
            if(remaining<=0)throw new TimeoutException();
            Connection c=future.get(remaining,TimeUnit.NANOSECONDS);acquired.set(null);return c;
        }catch(TimeoutException|InterruptedException e){
            expired.set(true);Connection c=acquired.getAndSet(null);if(c!=null)c.abort(Runnable::run);future.cancel(true);
            if(e instanceof InterruptedException)Thread.currentThread().interrupt();
            throw new SQLTimeoutException("Reconciliation connection deadline");
        }catch(ExecutionException e){throw new IllegalStateException("Reconciliation connection unavailable");}
    }
    private static String required(String name){String value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalArgumentException("Missing reconciliation environment");return value;}
}
