package com.lky.kaipay.reconciliation.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** A manual, insert-only audit transaction. SELECT is the only access to financial source tables. */
public final class ReconciliationRunner {
    public record Result(String reportJson, boolean replayed) {}
    interface Hooks {
        default void afterCohort() throws Exception {}
        default void beforeCommit() throws Exception {}
        default void afterCommit() throws Exception {}
    }
    private final Hooks hooks;
    private final Instant testObservedAt;
    public ReconciliationRunner() { this(new Hooks(){},null); }
    ReconciliationRunner(Hooks hooks,Instant testObservedAt){this.hooks=hooks;this.testObservedAt=testObservedAt;}
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final long LIMIT_NANOS=TimeUnit.SECONDS.toNanos(15);

    public Result run(Connection c,RunRequest request,String group,String build) throws Exception {
        return run(c,request,group,build,System.nanoTime());
    }
    Result run(Connection c,RunRequest request,String group,String build,long started) throws Exception {
        if(!c.getAutoCommit())throw new IllegalArgumentException("Reconciliation requires a fresh connection");
        var timer=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"reconciliation-deadline");t.setDaemon(true);return t;});
        long remaining=LIMIT_NANOS-(System.nanoTime()-started);
        if(remaining<=0){timer.shutdownNow();throw new SQLTimeoutException("Reconciliation deadline");}
        var watchdog=timer.schedule(()->{try{c.abort(Runnable::run);}catch(SQLException ignored){}},remaining,TimeUnit.NANOSECONDS);
        try {
            c.setReadOnly(false);c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);c.setAutoCommit(false);
            configure(c,started);
            Instant observed;
            try(var p=prepare(c,"SELECT clock_timestamp()",started);var r=p.executeQuery()){
                r.next();observed=testObservedAt==null?r.getTimestamp(1).toInstant():testObservedAt;
            }
            String fingerprint=request.fingerprint();
            Result replay=find(c,request,fingerprint,started);
            if(replay!=null){c.rollback();return replay;}
            request.validateNew();
            if(group==null||group.isBlank()||group.length()>100||build==null||!build.matches("[A-Za-z0-9._-]{1,100}"))
                throw new IllegalArgumentException("Invalid reconciliation runtime context");
            try(var p=prepare(c,"SELECT EXISTS(SELECT 1 FROM merchants WHERE id=?)",started)){
                p.setObject(1,request.merchantId());try(var r=p.executeQuery()){
                    r.next();if(!r.getBoolean(1))throw new IllegalArgumentException("Merchant unavailable");
                }
            }
            if(request.cutoffExclusive().isAfter(observed.minusSeconds(300)))throw new IllegalArgumentException("Cutoff is not mature");
            List<UUID> cohort=new ArrayList<>();
            try(var p=prepare(c,"SELECT id FROM payments WHERE merchant_id=? AND created_at>=? AND created_at<? ORDER BY created_at DESC,id DESC LIMIT 1001",started)){
                p.setObject(1,request.merchantId());p.setTimestamp(2,Timestamp.from(request.fromInclusive()));p.setTimestamp(3,Timestamp.from(request.cutoffExclusive()));
                try(var r=p.executeQuery()){while(r.next())cohort.add(r.getObject(1,UUID.class));}
            }
            if(cohort.size()>1000)throw new IllegalArgumentException("Reconciliation cohort limit exceeded");
            hooks.afterCohort();check(started);
            var observations=new ArrayList<AuthorizationObservation>();
            var classifier=new AuthorizationClassifier();
            for(int offset=0;offset<cohort.size();offset+=100){
                check(started);
                var batch=cohort.subList(offset,Math.min(cohort.size(),offset+100));
                var sources=readSources(c,request.merchantId(),batch,started);
                readContext(c,request.merchantId(),group,sources,started);
                for(var s:sources.values()){
                    check(started);var observation=classifier.classify(s,observed);
                    if(JSON.writeValueAsBytes(observation).length>15000)throw new IllegalArgumentException("Reconciliation evidence limit exceeded");
                    observations.add(observation);
                }
            }
            if(observations.size()!=cohort.size())throw new IllegalStateException("Incomplete reconciliation cohort");
            observations.sort(Comparator.comparing(o->o.paymentId().toString()));
            var counts=new LinkedHashMap<String,Integer>();AuthorizationObservation.CLASSES.forEach(k->counts.put(k,0));
            observations.forEach(o->counts.compute(o.primaryClass(),(k,v)->v+1));
            if(counts.values().stream().mapToInt(Integer::intValue).sum()!=cohort.size())throw new IllegalStateException("Invalid reconciliation counts");
            Instant completed;
            try(var p=prepare(c,"SELECT clock_timestamp()",started);var r=p.executeQuery()){r.next();completed=testObservedAt==null?r.getTimestamp(1).toInstant():observed;}
            var report=new LinkedHashMap<String,Object>();
            report.put("runId",request.runId().toString());report.put("merchantId",request.merchantId().toString());
            report.put("assessmentScope",request.scope());report.put("ruleset",request.ruleset());report.put("schemaVersion",1);report.put("buildRevision",build);
            report.put("fromInclusive",request.fromInclusive().toString());report.put("cutoffExclusive",request.cutoffExclusive().toString());
            report.put("graceSeconds",300);report.put("paymentLimit",1000);report.put("executionLimitSeconds",15);
            report.put("actor",request.actor());report.put("reason",request.reason());report.put("snapshotObservedAt",observed.toString());report.put("completedAt",completed.toString());
            report.put("consumerGroup",group);report.put("cohortCount",cohort.size());report.put("counts",counts);report.put("status","COMPLETE");
            String reportJson=JSON.writeValueAsString(report);
            String retainedReport=insert(c,request,fingerprint,group,build,observed,completed,counts,reportJson,observations,started);
            hooks.beforeCommit();check(started);network(c,started);c.commit();hooks.afterCommit();
            return new Result(retainedReport,false);
        } catch(Exception e){
            try{network(c,started);c.rollback();}catch(SQLException cleanupFailure){
                // Expiry/interruption may make another statement unsafe; never cancel the watchdog
                // while leaving an open transaction behind for a caller that retains the connection.
                try{c.abort(Runnable::run);}catch(SQLException ignored){}
            }
            if(e instanceof SQLException sql && "23505".equals(sql.getSQLState()) && !c.isClosed()){
                // A concurrent identity winner is visible only in a new transaction/snapshot.
                c.setAutoCommit(true);c.setAutoCommit(false);configure(c,started);
                Result winner=find(c,request,request.fingerprint(),started);c.rollback();
                if(winner!=null)return winner;
            }
            throw e;
        } finally {watchdog.cancel(false);timer.shutdownNow();}
    }
    private static void configure(Connection c,long start)throws SQLException{
        try(var p=prepare(c,"SET LOCAL statement_timeout='2s'",start)){p.execute();}
        try(var p=prepare(c,"SET LOCAL lock_timeout='500ms'",start)){p.execute();}
        try(var p=prepare(c,"SET LOCAL idle_in_transaction_session_timeout='15s'",start)){p.execute();}
    }
    private static void check(long start)throws SQLTimeoutException{
        if(Thread.currentThread().isInterrupted()||System.nanoTime()-start>=LIMIT_NANOS)throw new SQLTimeoutException("Reconciliation deadline");
    }
    private static void network(Connection c,long start)throws SQLException{
        check(start);long left=TimeUnit.NANOSECONDS.toMillis(LIMIT_NANOS-(System.nanoTime()-start));
        c.setNetworkTimeout(Runnable::run,(int)Math.max(1,Math.min(2500,left)));
    }
    private static PreparedStatement prepare(Connection c,String sql,long start)throws SQLException{
        network(c,start);return c.prepareStatement(sql);
    }
    private static Result find(Connection c,RunRequest q,String hash,long start)throws SQLException{
        try(var p=prepare(c,"SELECT merchant_id,input_fingerprint,report::text FROM reconciliation_runs WHERE id=?",start)){
            p.setObject(1,q.runId());try(var r=p.executeQuery()){
                if(!r.next())return null;
                if(!q.merchantId().equals(r.getObject(1,UUID.class))||!hash.equals(r.getString(2)))throw new IllegalArgumentException("Reconciliation identity conflicts");
                return new Result(r.getString(3),true);
            }
        }
    }
    private static LinkedHashMap<UUID,AuthorizationSource> readSources(Connection c,UUID merchant,List<UUID> ids,long start)throws SQLException{
        String own="o.merchant_id=p.merchant_id";
        String sql="""
            SELECT p.id,p.amount_cents,p.currency,p.status,p.version,p.created_at,p.updated_at,
                o.payment_id IS NOT NULL AS op_present,COALESCE(o.merchant_id=p.merchant_id,FALSE) AS owner_matches,
                p.gateway_reference IS NOT NULL AS ref_present,p.failure_code IS NOT NULL AS code_present,p.failure_message IS NOT NULL AS message_present,
                CASE WHEN o.merchant_id=p.merchant_id THEN o.namespace END AS namespace,
                CASE WHEN o.merchant_id=p.merchant_id THEN o.contract_version END AS contract_version,
                CASE WHEN o.merchant_id=p.merchant_id THEN o.amount_cents END AS op_amount,
                CASE WHEN o.merchant_id=p.merchant_id THEN o.currency END AS op_currency,
                CASE WHEN o.merchant_id=p.merchant_id THEN o.disposition END AS disposition,
                CASE WHEN o.merchant_id=p.merchant_id THEN o.evaluation_count END AS evaluations,
                CASE WHEN o.merchant_id=p.merchant_id THEN o.created_at END AS op_created,
                CASE WHEN o.merchant_id=p.merchant_id THEN o.updated_at END AS op_updated,
                CASE WHEN o.merchant_id=p.merchant_id THEN o.result_at END AS result_at,
                CASE WHEN o.merchant_id=p.merchant_id THEN p.gateway_reference IS NOT DISTINCT FROM o.authorization_code ELSE FALSE END AS ref_matches,
                CASE WHEN o.merchant_id=p.merchant_id THEN p.failure_code IS NOT DISTINCT FROM
                    CASE WHEN o.disposition='DECLINED' THEN o.decline_code ELSE o.failure_code END ELSE FALSE END AS code_matches,
                CASE WHEN o.merchant_id=p.merchant_id THEN p.failure_message IS NOT DISTINCT FROM
                    CASE WHEN o.disposition='DECLINED' THEN o.decline_message ELSE o.failure_message END ELSE FALSE END AS message_matches,
                CASE WHEN o.merchant_id=p.merchant_id THEN
                    CASE WHEN COALESCE(o.decline_code,o.failure_code) IN ('INSUFFICIENT_FUNDS','SIMULATED_NO_ACCEPTANCE')
                    THEN COALESCE(o.decline_code,o.failure_code) WHEN COALESCE(o.decline_code,o.failure_code) IS NOT NULL THEN 'OTHER' END END AS safe_code,
                CASE WHEN o.merchant_id=p.merchant_id THEN
                    o.evaluation_count>=0 AND o.amount_cents>0 AND
                    ((o.disposition='UNRESOLVED' AND o.result_at IS NULL AND o.authorization_code IS NULL AND o.decline_code IS NULL AND o.decline_message IS NULL AND o.failure_code IS NULL AND o.failure_message IS NULL)
                    OR (o.disposition='APPROVED' AND o.result_at IS NOT NULL AND o.evaluation_count>0 AND length(o.authorization_code)>0 AND o.decline_code IS NULL AND o.decline_message IS NULL AND o.failure_code IS NULL AND o.failure_message IS NULL)
                    OR (o.disposition='DECLINED' AND o.result_at IS NOT NULL AND o.evaluation_count>0 AND o.authorization_code IS NULL AND length(o.decline_code)>0 AND o.decline_message IS NOT NULL AND o.failure_code IS NULL AND o.failure_message IS NULL)
                    OR (o.disposition='REJECTED_NO_ACCEPTANCE' AND o.result_at IS NOT NULL AND o.evaluation_count>0 AND o.authorization_code IS NULL AND o.decline_code IS NULL AND o.decline_message IS NULL AND o.failure_code='SIMULATED_NO_ACCEPTANCE' AND o.failure_message IS NOT NULL))
                    ELSE FALSE END AS shape_valid
            FROM payments p LEFT JOIN simulated_authorization_operations o ON o.payment_id=p.id
            WHERE p.merchant_id=? AND p.id=ANY(?) ORDER BY p.id
            """;
        var rows=new LinkedHashMap<UUID,AuthorizationSource>();
        try(var p=prepare(c,sql,start)){
            p.setObject(1,merchant);p.setArray(2,c.createArrayOf("uuid",ids.toArray()));
            try(var r=p.executeQuery()){
                while(r.next()){
                    var s=new AuthorizationSource();s.paymentId=r.getObject("id",UUID.class);s.amount=r.getLong("amount_cents");s.currency=r.getString("currency");s.status=r.getString("status");s.version=r.getLong("version");s.createdAt=time(r,"created_at");s.updatedAt=time(r,"updated_at");
                    s.operationPresent=r.getBoolean("op_present");s.merchantMatches=r.getBoolean("owner_matches");s.namespace=r.getString("namespace");s.contractVersion=r.getObject("contract_version",Integer.class);
                    s.operationAmount=r.getObject("op_amount",Long.class);s.operationCurrency=r.getString("op_currency");s.disposition=r.getString("disposition");s.evaluationCount=r.getObject("evaluations",Long.class);
                    s.operationCreatedAt=time(r,"op_created");s.operationUpdatedAt=time(r,"op_updated");s.resultAt=time(r,"result_at");s.validShape=r.getBoolean("shape_valid");
                    s.referencePresent=r.getBoolean("ref_present");s.failureCodePresent=r.getBoolean("code_present");s.failureMessagePresent=r.getBoolean("message_present");s.referenceMatches=r.getBoolean("ref_matches");s.codeMatches=r.getBoolean("code_matches");s.messageMatches=r.getBoolean("message_matches");s.safeCode=r.getString("safe_code");rows.put(s.paymentId,s);
                }
            }
        }
        return rows;
    }
    private static Instant time(ResultSet r,String key)throws SQLException{var t=r.getTimestamp(key);return t==null?null:t.toInstant();}
    private static void readContext(Connection c,UUID merchant,String group,Map<UUID,AuthorizationSource> sources,long start)throws SQLException{
        if(sources.isEmpty())return;
        // Extract only validated scalar identity, never JSON payloads or foreign identity fields.
        String sql="""
            SELECT p.id,e.valid_event FROM payments p CROSS JOIN LATERAL (
                SELECT CASE WHEN jsonb_typeof(o.payload->'eventId')='string'
                    AND (o.payload->>'eventId') ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
                    AND o.payload->>'eventType'='PaymentInitiatedEvent' AND o.payload->>'aggregateType'='PAYMENT'
                    AND o.payload->'version'='1'::jsonb
                    AND lower(o.payload->>'aggregateId')=p.id::text
                    AND lower(o.payload->>'merchantId')=p.merchant_id::text
                    AND lower(o.payload->'payload'->>'paymentId')=p.id::text
                    AND lower(o.payload->'payload'->>'merchantId')=p.merchant_id::text
                    THEN lower(o.payload->>'eventId') END AS valid_event
                FROM payment_events_outbox o
                WHERE o.aggregate_type='PAYMENT' AND o.aggregate_id=p.id::text AND o.event_type='PaymentInitiatedEvent'
                ORDER BY o.created_at,o.id LIMIT 5
            ) e WHERE p.merchant_id=? AND p.id=ANY(?)
            """;
        var seen=new HashMap<UUID,Integer>();
        try(var p=prepare(c,sql,start)){
            p.setObject(1,merchant);p.setArray(2,c.createArrayOf("uuid",sources.keySet().toArray()));
            try(var r=p.executeQuery()){while(r.next()){
                var s=sources.get(r.getObject(1,UUID.class));int n=seen.merge(s.paymentId,1,Integer::sum);String event=r.getString(2);
                if(n>4||event==null){s.contextIncomplete=true;continue;}
                UUID id=UUID.fromString(event);
                if(!s.eventIds.contains(id))s.eventIds.add(id);
                else s.contextIncomplete=true; // Duplicate identity bodies are deliberately not exposed or trusted.
            }}
        }
        // At most 400 validated IDs in this batch; one indexed marker query, not one query per payment.
        var eventPayment=new LinkedHashMap<UUID,UUID>();
        for(var s:sources.values())for(var id:s.eventIds){UUID previous=eventPayment.putIfAbsent(id,s.paymentId);if(previous!=null&&!previous.equals(s.paymentId)){s.contextIncomplete=true;sources.get(previous).contextIncomplete=true;}}
        if(eventPayment.isEmpty())return;
        try(var p=prepare(c,"SELECT event_id,CASE WHEN payment_id=ANY(?) AND event_type='PaymentInitiatedEvent' THEN payment_id END AS own_payment FROM consumed_events WHERE consumer_group=? AND event_id=ANY(?)",start)){
            p.setArray(1,c.createArrayOf("uuid",sources.keySet().toArray()));p.setString(2,group);p.setArray(3,c.createArrayOf("uuid",eventPayment.keySet().toArray()));
            try(var r=p.executeQuery()){while(r.next()){
                UUID event=r.getObject(1,UUID.class),expected=eventPayment.get(event),actual=r.getObject(2,UUID.class);
                var s=sources.get(expected);if(expected.equals(actual))s.markerEventIds.add(event);else s.contextIncomplete=true;
            }}
        }
    }
    private static String insert(Connection c,RunRequest q,String hash,String group,String build,Instant observed,Instant completed,Map<String,Integer> counts,String report,List<AuthorizationObservation> observations,long start)throws Exception{
        String retained;
        try(var p=prepare(c,"""
            INSERT INTO reconciliation_runs(id,merchant_id,scope,ruleset,schema_version,build_revision,from_inclusive,cutoff_exclusive,
                grace_seconds,payment_limit,execution_limit_seconds,actor,reason,input_fingerprint,snapshot_observed_at,completed_at,consumer_group,cohort_count,counts,report)
            VALUES (?,?,?,?,1,?,?,?,300,1000,15,?,?,?,?,?,?,?,?::jsonb,?::jsonb) RETURNING report::text
            """,start)){
            int i=0;p.setObject(++i,q.runId());p.setObject(++i,q.merchantId());p.setString(++i,q.scope());p.setString(++i,q.ruleset());p.setString(++i,build);
            p.setTimestamp(++i,Timestamp.from(q.fromInclusive()));p.setTimestamp(++i,Timestamp.from(q.cutoffExclusive()));p.setString(++i,q.actor());p.setString(++i,q.reason());p.setString(++i,hash);
            p.setTimestamp(++i,Timestamp.from(observed));p.setTimestamp(++i,Timestamp.from(completed));p.setString(++i,group);p.setInt(++i,observations.size());p.setString(++i,JSON.writeValueAsString(counts));p.setString(++i,report);
            try(var r=p.executeQuery()){r.next();retained=r.getString(1);}
        }
        try(var p=prepare(c,"INSERT INTO reconciliation_observations(run_id,payment_id,merchant_id,primary_class,severity,reason_codes,evidence,explanation,observation) VALUES (?,?,?,?,?,?::jsonb,?::jsonb,?,?::jsonb)",start)){
            for(var o:observations){
                check(start);p.setObject(1,q.runId());p.setObject(2,o.paymentId());p.setObject(3,q.merchantId());p.setString(4,o.primaryClass());p.setString(5,o.severity());p.setString(6,JSON.writeValueAsString(o.reasonCodes()));p.setString(7,JSON.writeValueAsString(o.evidence()));p.setString(8,o.explanation());p.setString(9,JSON.writeValueAsString(o));p.addBatch();
            }
            if(!observations.isEmpty()){network(c,start);p.executeBatch();}
        }
        return retained;
    }
}
