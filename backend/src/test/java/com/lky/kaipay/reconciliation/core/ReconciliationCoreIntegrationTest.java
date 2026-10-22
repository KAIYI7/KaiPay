package com.lky.kaipay.reconciliation.core;

import com.fasterxml.jackson.databind.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;

class ReconciliationCoreIntegrationTest {
    static final PostgreSQLContainer DB=new PostgreSQLContainer("postgres:16-alpine");
    static final UUID A=id(1,1),B=id(1,2),CUSTOMER=id(2,1);
    static final Instant NOW=Instant.parse("2026-10-22T04:00:00Z"),CREATED=Instant.parse("2026-10-22T03:00:00Z");
    static final ObjectMapper JSON=new ObjectMapper();
    static UUID id(int prefix,int n){return UUID.fromString(String.format("%d0000000-0000-0000-0000-%012d",prefix,n));}
    static Connection connection()throws SQLException{return DriverManager.getConnection(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword());}
    @BeforeAll static void start(){DB.start();Flyway.configure().dataSource(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword()).load().migrate();}
    @AfterAll static void stop(){DB.stop();}
    @BeforeEach void fixtures()throws Exception{
        // These diagnostic tables deliberately have no merchant/payment FK and do not cascade.
        execute("TRUNCATE payment_events_outbox,consumed_events,dead_letter_events");
        execute("TRUNCATE merchants CASCADE");
        execute("INSERT INTO merchants(id,name,api_key_hash) VALUES (?,'Reconciliation synthetic A','synthetic-a'),(?,'Reconciliation synthetic B','synthetic-b')",A,B);
        execute("INSERT INTO customers(id,merchant_id,email,full_name) VALUES (?,?,'fixture@example.test','Synthetic')",CUSTOMER,A);
    }
    static void execute(String sql,Object...args)throws SQLException{try(var c=connection();var p=c.prepareStatement(sql)){for(int i=0;i<args.length;i++)p.setObject(i+1,args[i]);p.executeUpdate();}}
    static Object scalar(String sql,Object...args)throws SQLException{try(var c=connection();var p=c.prepareStatement(sql)){for(int i=0;i<args.length;i++)p.setObject(i+1,args[i]);try(var r=p.executeQuery()){r.next();return r.getObject(1);}}}
    static RunRequest request(int n){return new RunRequest(id(5,n),A,RunRequest.SCOPE,RunRequest.RULESET,Instant.parse("2026-10-21T00:00:00Z"),Instant.parse("2026-10-22T03:50:00Z"),"synthetic_operator","VERIFICATION");}
    static UUID fixture(int n,String status,String disposition,String currency,long amount)throws SQLException{
        UUID payment=id(3,n);
        String reference="APPROVED".equals(disposition)?"AUTH-FIXTURE-01":null;
        String code="DECLINED".equals(disposition)?"INSUFFICIENT_FUNDS":"REJECTED_NO_ACCEPTANCE".equals(disposition)?"SIMULATED_NO_ACCEPTANCE":null;
        String message=code==null?null:"Synthetic retained message";
        execute("INSERT INTO payments(id,merchant_id,customer_id,amount_cents,currency,status,idempotency_key,gateway_reference,failure_code,failure_message,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                payment,A,CUSTOMER,amount,currency,status,payment.toString(),reference,code,message,Timestamp.from(CREATED),Timestamp.from(CREATED));
        if(disposition!=null){
            boolean unresolved="UNRESOLVED".equals(disposition),declined="DECLINED".equals(disposition);
            execute("INSERT INTO simulated_authorization_operations(payment_id,namespace,contract_version,merchant_id,amount_cents,currency,disposition,evaluation_count,authorization_code,decline_code,decline_message,failure_code,failure_message,result_at,created_at,updated_at) VALUES (?,'kaipay-sim-auth-v1',1,?,?,?,?,2,?,?,?,?,?,?,?,?)",
                    payment,A,amount,currency,disposition,reference,declined?code:null,declined?message:null,declined?null:code,declined?null:message,unresolved?null:Timestamp.from(NOW.minusSeconds(1200)),Timestamp.from(CREATED.plusSeconds(60)),Timestamp.from(NOW.minusSeconds(1200)));
        }
        return payment;
    }
    static ReconciliationRunner.Result run(int n)throws Exception{try(var c=connection()){return new ReconciliationRunner(new ReconciliationRunner.Hooks(){},NOW).run(c,request(n),"synthetic-group","synthetic-build");}}
    static JsonNode observation(int run,int payment)throws Exception{return JSON.readTree((String)scalar("SELECT observation::text FROM reconciliation_observations WHERE run_id=? AND payment_id=?",id(5,run),id(3,payment)));}
    static Map<String,List<String>> sources()throws Exception{
        var result=new TreeMap<String,List<String>>();
        try(var c=connection();var tables=c.prepareStatement("SELECT tablename FROM pg_tables WHERE schemaname='public' AND tablename NOT LIKE 'reconciliation_%' AND tablename<>'flyway_schema_history' ORDER BY tablename");var r=tables.executeQuery()){
            while(r.next()){
                String table=r.getString(1);var rows=new ArrayList<String>();
                try(var p=c.prepareStatement("SELECT to_jsonb(t)::text FROM \""+table+"\" t ORDER BY to_jsonb(t)::text");var values=p.executeQuery()){while(values.next())rows.add(values.getString(1));}result.put(table,rows);
            }
        }return result;
    }
    @Test void cleanLifecycleAndHistoricalEvidenceReadOnly()throws Exception{
        fixture(1,"AUTHORIZED","APPROVED","MYR",5000);fixture(2,"DECLINED","DECLINED","MYR",999900);fixture(3,"FAILED","REJECTED_NO_ACCEPTANCE","MYR",666600);
        int n=4;for(String state:List.of("CAPTURED","REFUND_PENDING","PARTIALLY_REFUNDED","REFUNDED"))fixture(n++,state,"APPROVED","MYR",5000);
        fixture(8,"AUTHORIZED","APPROVED","USD",5000);fixture(9,"PROCESSING","UNRESOLVED","USD",777700);
        var before=sources();var result=run(1);assertThat(JSON.readTree(result.reportJson()).path("cohortCount").asInt()).isEqualTo(9);
        for(int i=1;i<=8;i++)assertThat(observation(1,i).path("primaryClass").asText()).isEqualTo("MATCHED");
        assertThat(observation(1,8).path("reasonCodes").toString()).contains("HISTORICAL_NON_MYR");
        assertThat(observation(1,9).path("reasonCodes").toString()).contains("RECOVERY_BLOCKED_NON_MYR");assertThat(sources()).isEqualTo(before);
    }
    @Test void fieldMismatchReasonsAreExactAndNeverExposeRawData()throws Exception{
        var p=fixture(1,"AUTHORIZED","APPROVED","MYR",5000);
        execute("UPDATE payments SET amount_cents=5001,currency='USD',gateway_reference='PRIVATE_REFERENCE',failure_code='PRIVATE_CODE',failure_message='PRIVATE_MESSAGE' WHERE id=?",p);
        var before=sources();run(1);var o=observation(1,1);assertThat(o.path("primaryClass").asText()).isEqualTo("REQUEST_MISMATCH");
        assertThat(o.path("reasonCodes").toString()).contains("AMOUNT_DIFFERS","CURRENCY_DIFFERS","REFERENCE_DIFFERS","CODE_DIFFERS","MESSAGE_DIFFERS");
        assertThat(o.toString()).doesNotContain("PRIVATE_","AUTH-FIXTURE-01","Synthetic retained message");assertThat(sources()).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(strings={"AMOUNT","CURRENCY","REFERENCE","CODE","MESSAGE"})
    void eachIndependentRequestOrFinalFieldMismatchIsDetected(String field)throws Exception{
        var payment=fixture(1,"AUTHORIZED","APPROVED","MYR",5000);
        String assignment=switch(field){
            case "AMOUNT"->"amount_cents=5001";case "CURRENCY"->"currency='USD'";
            case "REFERENCE"->"gateway_reference='PRIVATE_REFERENCE'";case "CODE"->"failure_code='PRIVATE_CODE'";
            default->"failure_message='PRIVATE_MESSAGE'";
        };
        execute("UPDATE payments SET "+assignment+" WHERE id=?",payment);var before=sources();run(1);
        var o=observation(1,1);assertThat(o.path("primaryClass").asText()).isEqualTo(Set.of("AMOUNT","CURRENCY").contains(field)?"REQUEST_MISMATCH":"AUTHORIZATION_MISMATCH");
        assertThat(o.path("reasonCodes").toString()).isEqualTo("[\""+field+"_DIFFERS\"]");
        assertThat(o.toString()).doesNotContain("PRIVATE_","AUTH-FIXTURE-01");assertThat(sources()).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(strings={"FAILED","DECLINED"}) void approvedWrongFinalStateIsConflict(String state)throws Exception{
        fixture(1,state,"APPROVED","MYR",5000);run(1);assertThat(observation(1,1).path("primaryClass").asText()).isEqualTo("AUTHORIZATION_MISMATCH");
    }
    @Test void nullAndEmptyMessagesAreDifferentAndFailureReferenceMustBeAbsent()throws Exception{
        UUID p=fixture(1,"DECLINED","DECLINED","MYR",999900);execute("UPDATE payments SET gateway_reference='',failure_message='' WHERE id=?",p);run(1);
        assertThat(observation(1,1).path("reasonCodes").toString()).contains("REFERENCE_DIFFERS","MESSAGE_DIFFERS");
    }
    @Test void pendingLegacyAndNoAcceptanceRemainDistinct()throws Exception{
        fixture(1,"CREATED",null,"MYR",5000);fixture(2,"PROCESSING",null,"MYR",5000);fixture(3,"AUTHORIZED",null,"MYR",5000);
        fixture(4,"PROCESSING","UNRESOLVED","MYR",777700);fixture(5,"PROCESSING","UNRESOLVED","MYR",5000);execute("UPDATE simulated_authorization_operations SET evaluation_count=0 WHERE payment_id=?",id(3,5));
        fixture(6,"AUTHORIZED","UNRESOLVED","MYR",777700);fixture(7,"CREATED","APPROVED","MYR",5000);
        var before=sources();run(1);String[] expected={"QUEUED","AMBIGUOUS_PROCESSING","EVIDENCE_UNAVAILABLE","UNRESOLVED","INTENT_PENDING","AUTHORIZATION_MISMATCH","INTENT_STATE_CONFLICT"};
        for(int i=0;i<expected.length;i++)assertThat(observation(1,i+1).path("primaryClass").asText()).isEqualTo(expected[i]);assertThat(sources()).isEqualTo(before);
    }
    @Test void finalizationGraceAndClockWarningsDoNotInventFailures()throws Exception{
        for(int i=1;i<=4;i++)fixture(i,"PROCESSING","APPROVED","MYR",5000);
        execute("UPDATE simulated_authorization_operations SET result_at=? WHERE payment_id=?",Timestamp.from(NOW.minusSeconds(60)),id(3,1));
        execute("UPDATE simulated_authorization_operations SET result_at=? WHERE payment_id=?",Timestamp.from(NOW.minusSeconds(300)),id(3,2));
        execute("UPDATE simulated_authorization_operations SET result_at=? WHERE payment_id=?",Timestamp.from(NOW.plusSeconds(60)),id(3,3));
        execute("UPDATE simulated_authorization_operations SET result_at=?,updated_at=? WHERE payment_id=?",Timestamp.from(CREATED.minusSeconds(60)),Timestamp.from(CREATED.minusSeconds(60)),id(3,4));
        fixture(5,"AUTHORIZED","APPROVED","MYR",5000);execute("UPDATE simulated_authorization_operations SET updated_at=? WHERE payment_id=?",Timestamp.from(CREATED.minusSeconds(60)),id(3,5));run(1);
        assertThat(observation(1,1).path("primaryClass").asText()).isEqualTo("PENDING_FINALIZATION");assertThat(observation(1,2).path("primaryClass").asText()).isEqualTo("FINALIZATION_OUTSTANDING");
        for(int i=3;i<=4;i++){assertThat(observation(1,i).path("primaryClass").asText()).isEqualTo("PENDING_FINALIZATION");assertThat(observation(1,i).path("reasonCodes").toString()).contains("TIMING_UNCERTAIN");}
        assertThat(observation(1,5).path("primaryClass").asText()).isEqualTo("MATCHED");assertThat(observation(1,5).path("severity").asText()).isEqualTo("WARNING");
    }
    @Test void foreignOperationRedactionIncludesAdminStoredEvidence()throws Exception{
        fixture(1,"AUTHORIZED","APPROVED","MYR",5000);execute("UPDATE simulated_authorization_operations SET merchant_id=?,amount_cents=123456,currency='USD' WHERE payment_id=?",B,id(3,1));run(1);
        var o=observation(1,1);assertThat(o.path("primaryClass").asText()).isEqualTo("OWNERSHIP_CONFLICT");
        assertThat(o.toString()).doesNotContain(B.toString(),"123456","USD","APPROVED","AUTH-FIXTURE-01");
        assertThat(o.path("evidence").has("operationAmountCents")).isFalse();
        try(var c=connection()){
            var q=request(2);var other=new RunRequest(q.runId(),B,q.scope(),q.ruleset(),q.fromInclusive(),q.cutoffExclusive(),q.actor(),q.reason());
            assertThat(JSON.readTree(new ReconciliationRunner(new ReconciliationRunner.Hooks(){},NOW).run(c,other,"group","build").reportJson()).path("cohortCount").asInt()).isZero();
        }
    }
    @Test void replayReturnsExactStoredJsonAcrossSourceBuildAndGroupChanges()throws Exception{
        fixture(1,"PROCESSING","UNRESOLVED","MYR",777700);var first=run(1);
        execute("UPDATE payments SET amount_cents=6000 WHERE id=?",id(3,1));var before=sources();
        try(var c=connection()){
            var replay=new ReconciliationRunner().run(c,request(1),null,null);assertThat(replay.replayed()).isTrue();assertThat(replay.reportJson()).isEqualTo(first.reportJson());
        }
        var q=request(1);try(var c=connection()){
            var conflict=new RunRequest(q.runId(),q.merchantId(),q.scope(),q.ruleset(),q.fromInclusive(),q.cutoffExclusive(),"different_actor",q.reason());
            assertThatThrownBy(()->new ReconciliationRunner().run(c,conflict,"group","build")).isInstanceOf(IllegalArgumentException.class);
        }
        run(2);assertThat(observation(2,1).path("primaryClass").asText()).isEqualTo("REQUEST_MISMATCH");assertThat(observation(1,1).path("primaryClass").asText()).isEqualTo("UNRESOLVED");assertThat(sources()).isEqualTo(before);
    }
    @Test void snapshotRetainsCommittedCohortAndOutcomesAcrossConcurrentFinalization()throws Exception{
        fixture(1,"PROCESSING","UNRESOLVED","MYR",777700);
        var snapshot=new CountDownLatch(1);var released=new CountDownLatch(1);
        var runner=new ReconciliationRunner(new ReconciliationRunner.Hooks(){public void afterCohort()throws Exception{snapshot.countDown();if(!released.await(8,TimeUnit.SECONDS))throw new IllegalStateException();}},NOW);
        try(var workers=Executors.newSingleThreadExecutor()){
            var running=workers.submit(()->{try(var c=connection()){return runner.run(c,request(1),"group","build");}});
            assertThat(snapshot.await(5,TimeUnit.SECONDS)).isTrue();
            try{
                // These commits complete while report reads its old snapshot: it holds no payment locks.
                execute("UPDATE simulated_authorization_operations SET disposition='APPROVED',authorization_code='AUTH-LATE',result_at=?,updated_at=? WHERE payment_id=?",Timestamp.from(NOW.minusSeconds(30)),Timestamp.from(NOW.minusSeconds(30)),id(3,1));
                execute("UPDATE payments SET status='AUTHORIZED',gateway_reference='AUTH-LATE' WHERE id=?",id(3,1));fixture(2,"AUTHORIZED","APPROVED","MYR",5000);
            }finally{released.countDown();}
            running.get(8,TimeUnit.SECONDS);
        }
        assertThat(observation(1,1).path("primaryClass").asText()).isEqualTo("UNRESOLVED");assertThat(((Number)scalar("SELECT cohort_count FROM reconciliation_runs WHERE id=?",id(5,1))).intValue()).isEqualTo(1);
        run(2);assertThat(observation(2,1).path("primaryClass").asText()).isEqualTo("MATCHED");assertThat(observation(2,2).path("primaryClass").asText()).isEqualTo("MATCHED");
    }
    @Test void creationBoundariesAndLateCommitAreNotClosedPeriodCompleteness()throws Exception{
        fixture(1,"CREATED",null,"MYR",5000);fixture(2,"CREATED",null,"MYR",5000);
        execute("UPDATE payments SET created_at=? WHERE id=?",Timestamp.from(request(1).fromInclusive()),id(3,1));
        execute("UPDATE payments SET created_at=? WHERE id=?",Timestamp.from(request(1).cutoffExclusive()),id(3,2));run(1);
        assertThat(((Number)scalar("SELECT cohort_count FROM reconciliation_runs WHERE id=?",id(5,1))).intValue()).isEqualTo(1);
        assertThat(((Number)scalar("SELECT count(*) FROM reconciliation_observations WHERE run_id=? AND payment_id=?",id(5,1),id(3,2))).intValue()).isZero();
    }
    @Test void paymentInsertedBeforeSnapshotButCommittedLaterAppearsOnlyInNewRun()throws Exception{
        fixture(1,"AUTHORIZED","APPROVED","MYR",5000);
        var snap=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var late=connection();var worker=Executors.newSingleThreadExecutor()){
            late.setAutoCommit(false);
            try(var p=late.prepareStatement("INSERT INTO payments(id,merchant_id,customer_id,amount_cents,currency,status,idempotency_key,created_at,updated_at) VALUES (?,?,?,5000,'MYR','CREATED','late-transaction',?,?)")){
                p.setObject(1,id(3,2));p.setObject(2,A);p.setObject(3,CUSTOMER);p.setTimestamp(4,Timestamp.from(CREATED));p.setTimestamp(5,Timestamp.from(CREATED));p.executeUpdate();
            }
            var runner=new ReconciliationRunner(new ReconciliationRunner.Hooks(){public void afterCohort()throws Exception{snap.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException();}},NOW);
            var result=worker.submit(()->{try(var c=connection()){return runner.run(c,request(1),"group","build");}});
            try{assertThat(snap.await(5,TimeUnit.SECONDS)).isTrue();late.commit();}finally{release.countDown();}
            result.get(8,TimeUnit.SECONDS);
        }
        assertThat(((Number)scalar("SELECT cohort_count FROM reconciliation_runs WHERE id=?",id(5,1))).intValue()).isEqualTo(1);
        run(2);assertThat(observation(2,2).path("primaryClass").asText()).isEqualTo("QUEUED");
    }
    @Test void everySourceIsUnchangedOnFailureAndRestrictedRoleCanOnlyInsertAudit()throws Exception{
        fixture(1,"AUTHORIZED","APPROVED","MYR",5000);var before=sources();
        try(var c=connection()){
            var failing=new ReconciliationRunner(new ReconciliationRunner.Hooks(){public void beforeCommit(){throw new IllegalStateException("synthetic failure");}},NOW);
            assertThatThrownBy(()->failing.run(c,request(1),"group","build")).isInstanceOf(IllegalStateException.class);
        }
        assertThat(((Number)scalar("SELECT count(*) FROM reconciliation_runs")).intValue()).isZero();assertThat(sources()).isEqualTo(before);
        execute("DO $$ BEGIN IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='recon_select_operator') THEN CREATE ROLE recon_select_operator LOGIN PASSWORD 'synthetic-only'; END IF; END $$");
        execute("GRANT USAGE ON SCHEMA public TO recon_select_operator");execute("GRANT SELECT ON ALL TABLES IN SCHEMA public TO recon_select_operator");
        execute("GRANT INSERT ON reconciliation_runs,reconciliation_observations TO recon_select_operator");
        try(var c=DriverManager.getConnection(DB.getJdbcUrl(),"recon_select_operator","synthetic-only")){
            new ReconciliationRunner(new ReconciliationRunner.Hooks(){},NOW).run(c,request(2),"group","build");
        }
        for(String table:List.of("payments","simulated_authorization_operations","refunds","journals","ledger_entries","reconciliation_runs","reconciliation_observations")){
            try(var c=DriverManager.getConnection(DB.getJdbcUrl(),"recon_select_operator","synthetic-only");var p=c.prepareStatement("DELETE FROM "+table)){
                assertThatThrownBy(p::executeUpdate).isInstanceOf(SQLException.class).satisfies(e->assertThat(((SQLException)e).getSQLState()).isEqualTo("42501"));
            }
        }
        assertThat(sources()).isEqualTo(before);
    }
    @Test void cohortAndTimeLimitsRejectWithoutPartialReports()throws Exception{
        execute("INSERT INTO payments(id,merchant_id,customer_id,amount_cents,currency,status,idempotency_key,created_at) SELECT md5('reconciliation-'||n)::uuid,?,?,5000,'MYR','CREATED','recon-'||n,? FROM generate_series(1,1000) n",A,CUSTOMER,Timestamp.from(CREATED));
        try(var c=connection();var p=c.prepareStatement("EXPLAIN (ANALYZE,BUFFERS,FORMAT JSON) SELECT id FROM payments WHERE merchant_id=? AND created_at>=? AND created_at<? ORDER BY created_at DESC,id DESC LIMIT 1001")){
            p.setObject(1,A);p.setTimestamp(2,Timestamp.from(request(1).fromInclusive()));p.setTimestamp(3,Timestamp.from(request(1).cutoffExclusive()));
            try(var r=p.executeQuery()){
                r.next();var plan=JSON.readTree(r.getString(1)).get(0);assertThat(plan.path("Plan").path("Actual Rows").asInt()).isEqualTo(1000);
                System.out.println("Reconciliation synthetic cohort EXPLAIN: root="+plan.path("Plan").path("Node Type").asText()+", rows="+plan.path("Plan").path("Actual Rows").asInt()+", executionMs="+plan.path("Execution Time").asDouble()+", sharedHitBlocks="+plan.path("Plan").path("Shared Hit Blocks").asInt());
            }
        }
        run(1);assertThat(((Number)scalar("SELECT cohort_count FROM reconciliation_runs WHERE id=?",id(5,1))).intValue()).isEqualTo(1000);
        fixture(1001,"CREATED",null,"MYR",5000);var before=sources();assertThatThrownBy(()->run(2)).isInstanceOf(IllegalArgumentException.class);assertThat(sources()).isEqualTo(before);
        assertThat(((Number)scalar("SELECT count(*) FROM reconciliation_runs WHERE id=?",id(5,2))).intValue()).isZero();
        var q=request(3);var wide=new RunRequest(q.runId(),A,q.scope(),q.ruleset(),q.fromInclusive().minusSeconds(604800),q.cutoffExclusive(),q.actor(),q.reason());
        try(var c=connection()){assertThatThrownBy(()->new ReconciliationRunner().run(c,wide,"group","build")).isInstanceOf(IllegalArgumentException.class);}
    }
    @Test void eventAndMarkerIdentitiesAreBoundedValidatedAndNotFinancialTruth()throws Exception{
        fixture(1,"AUTHORIZED","APPROVED","MYR",5000);fixture(2,"AUTHORIZED","APPROVED","MYR",5000);
        for(int n=1;n<=6;n++){
            var event=new LinkedHashMap<String,Object>();event.put("eventId",id(4,n).toString());event.put("eventType","PaymentInitiatedEvent");event.put("aggregateType","PAYMENT");event.put("aggregateId",id(3,1).toString());event.put("merchantId",n==2?B.toString():A.toString());event.put("version",1);event.put("payload",Map.of("paymentId",id(3,1).toString(),"merchantId",A.toString(),"private","PRIVATE_TOKEN"));
            execute("INSERT INTO payment_events_outbox(id,aggregate_type,aggregate_id,event_type,payload,created_at) VALUES (?,'PAYMENT',?,'PaymentInitiatedEvent',?::jsonb,?)",id(6,n),id(3,1).toString(),JSON.writeValueAsString(event),Timestamp.from(CREATED.plusSeconds(n)));
        }
        execute("INSERT INTO consumed_events(event_id,consumer_group,payment_id,event_type,status) VALUES (?,'synthetic-group',?,'PaymentInitiatedEvent','AUTHORIZED'),(?,'synthetic-group',?,'PaymentInitiatedEvent','AUTHORIZED'),(?,'other-group',?,'PaymentInitiatedEvent','AUTHORIZED')",id(4,1),id(3,1),id(4,3),id(3,2),id(4,4),id(3,1));
        var before=sources();run(1);var o=observation(1,1);assertThat(o.path("primaryClass").asText()).isEqualTo("MATCHED");
        assertThat(o.path("evidence").path("contextIncomplete").asBoolean()).isTrue();assertThat(o.path("evidence").path("eventIds").size()).isLessThanOrEqualTo(4);
        assertThat(o.path("evidence").path("markerEventIds").toString()).isEqualTo("[\""+id(4,1)+"\"]");assertThat(o.toString()).doesNotContain("PRIVATE_TOKEN",B.toString(),id(3,2).toString());assertThat(sources()).isEqualTo(before);
    }
    @Test void repeatedEnvelopeIdentityWithDifferentPrivateBodiesIsIncompleteContextNotDuplicateAcceptance()throws Exception{
        fixture(1,"AUTHORIZED","APPROVED","MYR",5000);
        for(int i=1;i<=2;i++){
            var event=Map.of("eventId",id(4,1).toString(),"eventType","PaymentInitiatedEvent","aggregateType","PAYMENT","aggregateId",id(3,1).toString(),"merchantId",A.toString(),"version",1,
                    "payload",Map.of("paymentId",id(3,1).toString(),"merchantId",A.toString(),"private","PRIVATE_BODY_"+i));
            execute("INSERT INTO payment_events_outbox(id,aggregate_type,aggregate_id,event_type,payload,created_at) VALUES (?,'PAYMENT',?,'PaymentInitiatedEvent',?::jsonb,?)",id(6,i),id(3,1).toString(),JSON.writeValueAsString(event),Timestamp.from(CREATED.plusSeconds(i)));
        }
        var before=sources();run(1);var o=observation(1,1);
        assertThat(o.path("primaryClass").asText()).isEqualTo("MATCHED");assertThat(o.path("evidence").path("contextIncomplete").asBoolean()).isTrue();
        assertThat(o.path("evidence").path("eventIds").toString()).isEqualTo("[\""+id(4,1)+"\"]");assertThat(o.toString()).doesNotContain("PRIVATE_BODY_");assertThat(sources()).isEqualTo(before);
    }
    @Test void sameIdentityConcurrentWinnerAndActualProcessCrashReplayAreAtomic()throws Exception{
        fixture(1,"AUTHORIZED","APPROVED","MYR",5000);
        var gate=new CyclicBarrier(2);
        var runner=new ReconciliationRunner(new ReconciliationRunner.Hooks(){public void afterCohort()throws Exception{gate.await(5,TimeUnit.SECONDS);}},NOW);
        try(var workers=Executors.newFixedThreadPool(2)){
            var task=(Callable<ReconciliationRunner.Result>)()->{try(var c=connection()){return runner.run(c,request(1),"group","build");}};
            var first=workers.submit(task);var second=workers.submit(task);var x=first.get(10,TimeUnit.SECONDS);var y=second.get(10,TimeUnit.SECONDS);
            assertThat(x.reportJson()).isEqualTo(y.reportJson());assertThat(x.replayed()).isNotEqualTo(y.replayed());
        }
        for(String stage:List.of("BEFORE","AFTER")){
            int run=stage.equals("BEFORE")?2:3;Path output=Files.createTempFile("reconciliation-crash-",".log");
            var args=List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),ReconciliationCrashChild.class.getName(),DB.getJdbcUrl(),DB.getUsername(),DB.getPassword(),Integer.toString(run),stage);
            Process child=new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(output.toFile()).start();
            try{
                assertThat(child.waitFor(12,TimeUnit.SECONDS)).isTrue();assertThat(child.exitValue()).isEqualTo(19);
                long count=((Number)scalar("SELECT count(*) FROM reconciliation_runs WHERE id=?",id(5,run))).longValue();assertThat(count).isEqualTo(stage.equals("AFTER")?1:0);
                var recovered=run(run);assertThat(recovered.replayed()).isEqualTo(stage.equals("AFTER"));assertThat(observation(run,1).path("primaryClass").asText()).isEqualTo("MATCHED");
            }finally{child.destroyForcibly();Files.deleteIfExists(output);}
        }
    }
    @Test void blockedSourceQueryAndCumulativeDeadlineFailWithoutReports()throws Exception{
        fixture(1,"AUTHORIZED","APPROVED","MYR",5000);var before=sources();
        try(var lock=connection();var c=connection()){
            lock.setAutoCommit(false);try(var p=lock.prepareStatement("LOCK TABLE payments IN ACCESS EXCLUSIVE MODE")){p.execute();}
            long start=System.nanoTime();assertThatThrownBy(()->new ReconciliationRunner(new ReconciliationRunner.Hooks(){},NOW).run(c,request(1),"group","build")).isInstanceOf(SQLException.class);
            assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofSeconds(5));lock.rollback();
        }
        try(var c=connection()){
            assertThatThrownBy(()->new ReconciliationRunner().run(c,request(2),"group","build",System.nanoTime()-TimeUnit.SECONDS.toNanos(16))).isInstanceOf(SQLTimeoutException.class);
        }
        assertThat(((Number)scalar("SELECT count(*) FROM reconciliation_runs")).intValue()).isZero();assertThat(sources()).isEqualTo(before);
    }
    @Test void activeCumulativeDeadlineAbortsAReportPausedBetweenQueries()throws Exception{
        fixture(1,"AUTHORIZED","APPROVED","MYR",5000);var before=sources();
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var c=connection();var worker=Executors.newSingleThreadExecutor()){
            var runner=new ReconciliationRunner(new ReconciliationRunner.Hooks(){public void afterCohort()throws Exception{entered.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException();}},NOW);
            var outcome=worker.submit(()->runner.run(c,request(1),"group","build",System.nanoTime()-TimeUnit.MILLISECONDS.toNanos(14300)));
            try{
                assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();
                org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(2)).until(c::isClosed);
            }finally{release.countDown();}
            assertThatThrownBy(()->outcome.get(3,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class).hasCauseInstanceOf(SQLTimeoutException.class);
        }
        assertThat(((Number)scalar("SELECT count(*) FROM reconciliation_runs")).intValue()).isZero();assertThat(sources()).isEqualTo(before);
    }
    @Test void interruptionAfterAuditInsertionAbortsTransactionBeforeWatchdogCancellation()throws Exception{
        fixture(1,"AUTHORIZED","APPROVED","MYR",5000);var before=sources();
        try(var c=connection();var worker=Executors.newSingleThreadExecutor()){
            var runner=new ReconciliationRunner(new ReconciliationRunner.Hooks(){public void beforeCommit(){Thread.currentThread().interrupt();}},NOW);
            var outcome=worker.submit(()->{
                try{return runner.run(c,request(1),"group","build");}finally{Thread.interrupted();}
            });
            assertThatThrownBy(()->outcome.get(5,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class).hasCauseInstanceOf(SQLTimeoutException.class);
            assertThat(c.isClosed()).isTrue();
        }
        assertThat(((Number)scalar("SELECT count(*) FROM reconciliation_runs")).intValue()).isZero();assertThat(sources()).isEqualTo(before);
    }
}
