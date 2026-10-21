package com.lky.kaipay.webhook.delivery;

import com.lky.kaipay.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/** Actual PostgreSQL ownership/recovery tests; deterministic transports do not prove network security. */
class WebhookDeliveryIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final Instant START = Instant.parse("2026-10-22T00:00:00Z");
    private static final byte[] SECRET = "synthetic-test-secret-32-bytes!!".getBytes(StandardCharsets.US_ASCII);
    @Autowired JdbcTemplate jdbc;
    @Autowired WebhookDeliveryStore store;
    @Autowired WebhookWorker productionWorker;
    @Autowired PlatformTransactionManager transactionManager;
    UUID merchant, customer, endpoint, key;

    @BeforeEach void fixture() {
        merchant = UUID.randomUUID(); customer = UUID.randomUUID(); endpoint = UUID.randomUUID(); key = UUID.randomUUID();
        jdbc.update("INSERT INTO merchants(id,name,api_key_hash,status) VALUES (?,'Delivery test',?,'ACTIVE')", merchant, merchant.toString());
        jdbc.update("INSERT INTO customers(id,merchant_id,email,full_name) VALUES (?,?,'delivery@example.test','Synthetic')", customer, merchant);
        jdbc.update("""
                INSERT INTO merchant_webhook_endpoints(id,merchant_id,url,hostname,path,pins,key_id,secret_ref,state,created_by,reason_code)
                VALUES (?,?,'https://receiver.example.test/hook','receiver.example.test','/hook',ARRAY['8.8.8.8'],?,?,'ACTIVE','synthetic-operator','TEST')
                """, endpoint, merchant, key, "fixture_" + endpoint.toString().replace("-", ""));
    }

    @AfterEach void cleanup() {
        // Explicit fixture cleanup only; the runtime has no reset/redrive operation.
        jdbc.update("UPDATE merchant_webhook_deliveries SET state='CANCELLED',current_attempt_id=NULL,claim_deadline=NULL,next_attempt_at=NULL,delivered_at=NULL WHERE merchant_id=?", merchant);
        jdbc.update("DELETE FROM merchant_webhook_attempts WHERE delivery_id IN (SELECT id FROM merchant_webhook_deliveries WHERE merchant_id=?)", merchant);
        jdbc.update("DELETE FROM merchant_webhook_deliveries WHERE merchant_id=?", merchant);
        jdbc.update("DELETE FROM merchant_webhook_endpoints WHERE merchant_id=?", merchant);
        jdbc.update("DELETE FROM payments WHERE merchant_id=?", merchant);
        jdbc.update("DELETE FROM customers WHERE merchant_id=?", merchant);
        jdbc.update("DELETE FROM merchants WHERE id=?", merchant);
    }

    @Test void skipLockedClaimersCannotOwnTheSameRowWhileFirstTransactionRemainsOpen() throws Exception {
        UUID id = seed(START);
        var held = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                WebhookClaim claim = store.claim(START).orElseThrow();
                held.countDown();
                await(release);
                return claim;
            }));
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
            try {
                var competing = pool.submit(() -> store.claim(START));
                // Returning while the first lock is still held proves actual SKIP LOCKED behavior.
                assertThat(competing.get(5, TimeUnit.SECONDS)).isEmpty();
                assertThat(first.isDone()).isFalse();
            } finally { release.countDown(); }
            WebhookClaim claimed = first.get(10, TimeUnit.SECONDS);
            assertThat(claimed.id()).isEqualTo(id);
        }
        assertThat(attemptCount(id)).isEqualTo(1);
        assertThat(row(id).get("state")).isEqualTo("IN_FLIGHT");
        assertThat(store.claim(START)).isEmpty();
    }

    @Test void workerHasOneActiveDispatchAndNeverHoldsDatabaseTransactionAcrossTransport() throws Exception {
        UUID firstId = seed(START), secondId = seed(START);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var sends = new AtomicInteger();
        WebhookTransport transport = (claim, signature) -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(row(claim.id()).get("state")).isEqualTo("IN_FLIGHT");
            sends.incrementAndGet(); entered.countDown(); await(release);
            return new WebhookTransport.Response(WebhookResult.ACCEPTED, 204);
        };
        try (var worker = worker(transport, Clock.fixed(START, ZoneOffset.UTC)); var pool = Executors.newFixedThreadPool(2)) {
            var active = pool.submit(worker::deliverOne);
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            try { assertThat(pool.submit(worker::deliverOne).get(5, TimeUnit.SECONDS)).isFalse(); }
            finally { release.countDown(); }
            assertThat(active.get(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(sends).hasValue(1);
        assertThat(List.of(row(firstId).get("state"), row(secondId).get("state"))).containsExactlyInAnyOrder("DELIVERED", "PENDING");
        assertThat(attemptCount(firstId) + attemptCount(secondId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments WHERE merchant_id=? AND status='CAPTURED'", Long.class, merchant)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM journals WHERE merchant_id=?", Long.class, merchant)).isZero();
    }

    @Test void productionScheduledWorkerDoesNotDispatchAndRejectsAmbientTransaction() {
        UUID id = seed(START);
        productionWorker.scheduledTick();
        assertThat(attemptCount(id)).isZero();
        assertThat(row(id).get("state")).isEqualTo("PENDING");
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> productionWorker.deliverOne()))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(attemptCount(id)).isZero();
    }

    @Test void ownershipDeadlineAndEventExpiryAreCheckedBeforeDispatch() {
        UUID id = seed(START.minus(Duration.ofHours(24)).plusSeconds(2));
        WebhookClaim claim = store.claim(START).orElseThrow();
        assertThat(store.ready(claim, START)).isTrue();
        assertThat(store.ready(claim, START.plusSeconds(2))).isFalse();
        assertThat(store.ready(claim, START.plusSeconds(30))).isFalse();
        // New worker's clock advances between durable claim and ready(), simulating pre-send pause.
        UUID pending = seed(START);
        var calls = new AtomicInteger();
        try (var worker = worker((c,s) -> { calls.incrementAndGet(); return new WebhookTransport.Response(WebhookResult.ACCEPTED,204); },
                new SequenceClock(START.plusSeconds(1), START.plusSeconds(40)))) {
            assertThat(worker.deliverOne()).isTrue();
        }
        assertThat(calls).hasValue(0);
        assertThat(attemptCount(pending)).isEqualTo(1);
        assertThat(row(pending).get("state")).isEqualTo("IN_FLIGHT");
        // Late completion is fenced; the next recovery tick classifies the abandoned claim.
        assertThat(store.claim(START.plusSeconds(72))).isEmpty();
        assertThat(store.claim(START.plusSeconds(73))).isEmpty();
        assertThat(row(pending).get("state")).isEqualTo("PENDING");
    }

    @Test void expiredPendingDeliveryNeverStartsAnAttempt() {
        UUID id = seed(START.minus(Duration.ofHours(24)));
        assertThat(store.claim(START)).isEmpty();
        assertThat(attemptCount(id)).isZero();
        assertThat(row(id).get("state")).isEqualTo("EXHAUSTED");
        assertThat(row(id).get("last_result")).isEqualTo("EXPIRED");
    }

    @Test void eventExpiringBetweenClaimAndDispatchIsExhaustedInsteadOfCancelled() {
        UUID id=seed(START.minus(Duration.ofHours(24)).plusSeconds(1));
        var sends=new AtomicInteger();
        try(var worker=worker((claim,signature)->{
            sends.incrementAndGet();return new WebhookTransport.Response(WebhookResult.ACCEPTED,204);
        },new SequenceClock(START,START.plusSeconds(2)))) {
            worker.deliverOne();
        }
        assertThat(sends).hasValue(0);
        assertThat(attemptCount(id)).isEqualTo(1);
        assertThat(row(id).get("state")).isEqualTo("EXHAUSTED");
        assertThat(row(id).get("last_result")).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("SELECT result FROM merchant_webhook_attempts WHERE delivery_id=?",String.class,id)).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("SELECT deadline FROM merchant_webhook_attempts WHERE delivery_id=?",Timestamp.class,id).toInstant()).isAfter(START.plusSeconds(2));
    }

    @Test void closingAfterClaimBeforeSendLeavesRecoverableUnknownAttempt() throws Exception {
        UUID id=seed(START);
        var clock=new PreSendClock(START);
        var sends=new AtomicInteger();
        var worker=worker((claim,signature)->{
            sends.incrementAndGet();return new WebhookTransport.Response(WebhookResult.ACCEPTED,204);
        },clock);
        UUID originalEvent;
        byte[] originalBody;
        try(var pool=Executors.newSingleThreadExecutor()) {
            var executing=pool.submit(worker::deliverOne);
            assertThat(clock.claimed.await(10,TimeUnit.SECONDS)).isTrue();
            try {
                assertThat(row(id).get("state")).isEqualTo("IN_FLIGHT");
                originalEvent=(UUID)row(id).get("event_id");
                originalBody=(byte[])row(id).get("payload");
                worker.close();
            } finally { clock.release.countDown(); }
            executing.get(10,TimeUnit.SECONDS);
        } finally { worker.close();clock.release.countDown(); }
        assertThat(sends).hasValue(0);
        assertThat(row(id).get("state")).isEqualTo("IN_FLIGHT");
        UUID abandoned=(UUID)row(id).get("current_attempt_id");
        assertThat(attemptResult(abandoned)).isEqualTo("STARTED");
        assertThat(store.claim(START.plusSeconds(31))).isEmpty();
        assertThat(attemptResult(abandoned)).isEqualTo("UNKNOWN");
        assertThat(row(id).get("state")).isEqualTo("PENDING");
        Instant due=due(id);
        try(var restarted=worker((claim,signature)->{
            assertThat(claim.eventId()).isEqualTo(originalEvent);
            assertThat(claim.payload()).isEqualTo(originalBody);
            return new WebhookTransport.Response(WebhookResult.ACCEPTED,204);
        },Clock.fixed(due,ZoneOffset.UTC))) {
            assertThat(restarted.deliverOne()).isTrue();
        }
        assertThat(row(id).get("state")).isEqualTo("DELIVERED");
        assertThat(attemptCount(id)).isEqualTo(2);
    }

    @Test void transportCancellationIsUnknownRetryInsteadOfPermanentBusinessCancellation() {
        UUID id=seed(START);
        try(var worker=worker((claim,signature)->new WebhookTransport.Response(WebhookResult.CANCELLED,null),Clock.fixed(START,ZoneOffset.UTC))) {
            assertThat(worker.deliverOne()).isTrue();
        }
        assertThat(row(id).get("state")).isEqualTo("PENDING");
        assertThat(row(id).get("last_result")).isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT result FROM merchant_webhook_attempts WHERE delivery_id=?",String.class,id)).isEqualTo("UNKNOWN");
        assertThat(due(id)).isBetween(START.plusSeconds(5),START.plusSeconds(6));
    }

    @Test void crashAfterClaimConsumesBudgetAndRestartsWithoutChangingIdentityOrPayload() {
        UUID id = seed(START);
        WebhookClaim original = store.claim(START).orElseThrow();
        assertThat(store.claim(START.plusSeconds(29))).isEmpty();
        Instant recovery = START.plusSeconds(31);
        assertThat(store.claim(recovery)).isEmpty();
        assertThat(attemptResult(original.attemptId())).isEqualTo("UNKNOWN");
        Instant due = due(id);
        assertThat(due).isBetween(recovery.plusSeconds(5), recovery.plusSeconds(6));
        WebhookClaim next = store.claim(due).orElseThrow();
        assertThat(next.ordinal()).isEqualTo(2);
        assertThat(next.attemptId()).isNotEqualTo(original.attemptId());
        assertThat(next.eventId()).isEqualTo(original.eventId());
        assertThat(next.id()).isEqualTo(original.id());
        assertThat(next.payload()).isEqualTo(original.payload());
        store.complete(next, new WebhookTransport.Response(WebhookResult.ACCEPTED,204), due.plusMillis(1));
        assertThat(row(id).get("state")).isEqualTo("DELIVERED");
        assertThat(attemptCount(id)).isEqualTo(2);
    }

    @Test void acceptanceThenWorkerLossRetriesSameEventAndOnlyOneSimulatedReceiverEffect() {
        UUID id = seed(START);
        Set<UUID> receiverDedup = new HashSet<>();
        var received = new ArrayList<WebhookClaim>();
        var effects = new AtomicInteger();
        WebhookTransport acceptedThenCrash = (claim, signature) -> {
            received.add(claim);
            if (receiverDedup.add(claim.eventId())) effects.incrementAndGet();
            throw new SimulatedWorkerLoss();
        };
        try (var worker = worker(acceptedThenCrash, Clock.fixed(START, ZoneOffset.UTC))) {
            assertThatThrownBy(worker::deliverOne).isInstanceOf(SimulatedWorkerLoss.class);
        }
        assertThat(row(id).get("state")).isEqualTo("IN_FLIGHT");
        assertThat(effects).hasValue(1);
        assertThat(store.claim(START.plusSeconds(31))).isEmpty();
        Instant retry = due(id);
        WebhookTransport duplicate = (claim, signature) -> {
            received.add(claim);
            if (receiverDedup.add(claim.eventId())) effects.incrementAndGet();
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new WebhookTransport.Response(WebhookResult.ACCEPTED, 204);
        };
        try (var restartedWorker = worker(duplicate, Clock.fixed(retry, ZoneOffset.UTC))) {
            assertThat(restartedWorker.deliverOne()).isTrue();
        }
        assertThat(received).hasSize(2);
        assertThat(received.get(0).payload()).isEqualTo(received.get(1).payload());
        assertThat(received.get(0).eventId()).isEqualTo(received.get(1).eventId());
        assertThat(effects).hasValue(1);
        assertThat(row(id).get("state")).isEqualTo("DELIVERED");
        assertThat(attemptCount(id)).isEqualTo(2);
    }

    @Test void staleCompletionCannotOverwriteNewOwnerOrSuccess() {
        UUID id = seed(START);
        WebhookClaim old = store.claim(START).orElseThrow();
        assertThat(store.claim(START.plusSeconds(31))).isEmpty();
        Instant retry = due(id);
        WebhookClaim current = store.claim(retry).orElseThrow();
        store.complete(old,new WebhookTransport.Response(WebhookResult.ACCEPTED,204),retry.plusMillis(1));
        assertThat(row(id).get("state")).isEqualTo("IN_FLIGHT");
        assertThat(row(id).get("current_attempt_id")).isEqualTo(current.attemptId());
        store.complete(current,new WebhookTransport.Response(WebhookResult.ACCEPTED,204),retry.plusMillis(2));
        store.complete(old,new WebhookTransport.Response(WebhookResult.HTTP_TERMINAL,400),retry.plusMillis(3));
        assertThat(row(id).get("state")).isEqualTo("DELIVERED");
        assertThat(attemptResult(old.attemptId())).isEqualTo("UNKNOWN");
        assertThat(attemptResult(current.attemptId())).isEqualTo("ACCEPTED");
    }

    @Test void eightAttemptsUsePersistedJitteredBackoffAndNeverResetOnWorkerRecreation() {
        UUID id = seed(START);
        long[] delays = {5,30,120,600,1800,7200,21600};
        Instant now = START;
        UUID event = null; byte[] bytes = null;
        for (int ordinal=1; ordinal<=8; ordinal++) {
            WebhookClaim claim = store.claim(now).orElseThrow();
            if (event == null) { event=claim.eventId(); bytes=claim.payload(); }
            assertThat(claim.ordinal()).isEqualTo(ordinal);
            assertThat(claim.eventId()).isEqualTo(event);
            assertThat(claim.payload()).isEqualTo(bytes);
            store.complete(claim,new WebhookTransport.Response(WebhookResult.HTTP_RETRYABLE,503),now.plusMillis(10));
            if (ordinal<8) {
                Instant due=due(id), completion=now.plusMillis(10);
                assertThat(due).isBetween(completion.plusSeconds(delays[ordinal-1]), completion.plusMillis(delays[ordinal-1]*1200));
                // Recreating the worker does not reroll scheduling or reset attempts.
                try (var ignored=worker((c,s)->new WebhookTransport.Response(WebhookResult.ACCEPTED,204),Clock.fixed(now,ZoneOffset.UTC))) {
                    assertThat(due(id)).isEqualTo(due);
                }
                assertThat(store.claim(due.minusMillis(1))).isEmpty();
                now=due;
            }
        }
        assertThat(attemptCount(id)).isEqualTo(8);
        assertThat(row(id).get("attempts")).isEqualTo(8);
        assertThat(row(id).get("state")).isEqualTo("EXHAUSTED");
        assertThat(row(id).get("last_result")).isEqualTo("EXHAUSTED");
        assertThat(store.claim(now.plusSeconds(3600))).isEmpty();
    }

    @ParameterizedTest @CsvSource({"200,DELIVERED,ACCEPTED", "202,DELIVERED,ACCEPTED", "204,DELIVERED,ACCEPTED", "299,DELIVERED,ACCEPTED",
            "302,TERMINAL,HTTP_TERMINAL", "400,TERMINAL,HTTP_TERMINAL", "401,TERMINAL,HTTP_TERMINAL", "404,TERMINAL,HTTP_TERMINAL",
            "408,PENDING,HTTP_RETRYABLE", "425,PENDING,HTTP_RETRYABLE", "429,PENDING,HTTP_RETRYABLE", "500,PENDING,HTTP_RETRYABLE", "503,PENDING,HTTP_RETRYABLE", "599,PENDING,HTTP_RETRYABLE"})
    void persistedHttpCategoriesMatchContract(int status, String state, String result) {
        UUID id = seed(START);
        var claim = store.claim(START).orElseThrow();
        store.complete(claim,new WebhookTransport.Response(WebhookResult.http(status),status),START.plusMillis(2));
        assertThat(row(id).get("state")).isEqualTo(state);
        assertThat(row(id).get("last_result")).isEqualTo(result);
        assertThat(attemptResult(claim.attemptId())).isEqualTo(result);
        assertThat(jdbc.queryForObject("SELECT http_status FROM merchant_webhook_attempts WHERE id=?",Integer.class,claim.attemptId())).isEqualTo(status);
    }

    @ParameterizedTest @EnumSource(value=WebhookResult.class,names={"TLS_ERROR","CONFIGURATION","UNSAFE_DESTINATION"})
    void unsafeConfigurationCannotRetryThroughInsecureFallback(WebhookResult result) {
        UUID id = seed(START);
        var claim = store.claim(START).orElseThrow();
        store.complete(claim,new WebhookTransport.Response(result,null),START.plusMillis(1));
        assertThat(row(id).get("state")).isEqualTo("TERMINAL");
        assertThat(row(id).get("last_result")).isEqualTo(result.name());
        assertThat(store.claim(START.plusSeconds(3600))).isEmpty();
    }

    @ParameterizedTest @CsvSource({"RETIRED", "INACTIVE"})
    void disabledEndpointOrMerchantCancelsPendingAndInFlightWithoutRetargeting(String reason) {
        UUID pending = seed(START), active = seed(START);
        WebhookClaim claim=store.claim(START).orElseThrow();
        UUID unsent=claim.id().equals(pending)?active:pending;
        if (reason.equals("RETIRED")) jdbc.update("UPDATE merchant_webhook_endpoints SET state='RETIRED',retired_at=NOW() WHERE id=?",endpoint);
        else jdbc.update("UPDATE merchants SET status='INACTIVE' WHERE id=?",merchant);
        assertThat(store.ready(claim,START.plusMillis(1))).isFalse();
        store.complete(claim,new WebhookTransport.Response(WebhookResult.ACCEPTED,204),START.plusMillis(2));
        assertThat(row(claim.id()).get("state")).isEqualTo("CANCELLED");
        assertThat(store.claim(START.plusMillis(3))).isEmpty();
        assertThat(row(unsent).get("state")).isEqualTo("CANCELLED");
        assertThat(attemptCount(unsent)).isZero();
        assertThat(row(claim.id()).get("endpoint_id")).isEqualTo(endpoint);
    }

    @Test void missingOrWrongTenantSecretIsTerminalAndTransportIsNeverCalled() {
        UUID id=seed(START); var sends=new AtomicInteger();
        try (var wrong=new WebhookSecrets(key,UUID.randomUUID(),endpoint,SECRET);
             var worker=new WebhookWorker(store,wrong,(c,s)->{sends.incrementAndGet();return new WebhookTransport.Response(WebhookResult.ACCEPTED,204);},Clock.fixed(START,ZoneOffset.UTC),true)) {
            assertThat(worker.deliverOne()).isTrue();
        }
        assertThat(sends).hasValue(0);
        assertThat(row(id).get("state")).isEqualTo("TERMINAL");
        assertThat(row(id).get("last_result")).isEqualTo("CONFIGURATION");
    }

    @Test void shutdownClosesTransportAndPreventsNewClaims() {
        UUID id=seed(START); var closed=new AtomicInteger();
        WebhookTransport transport=new WebhookTransport() {
            public Response send(WebhookClaim c,String s){throw new AssertionError("Closed worker sent HTTP");}
            public void close(){closed.incrementAndGet();}
        };
        var worker=worker(transport,Clock.fixed(START,ZoneOffset.UTC));
        worker.close();
        assertThat(worker.deliverOne()).isFalse();
        assertThat(closed).hasValue(1);
        assertThat(attemptCount(id)).isZero();
    }

    private WebhookWorker worker(WebhookTransport transport,Clock clock) {
        return new WebhookWorker(store,new WebhookSecrets(key,merchant,endpoint,SECRET),transport,clock,true);
    }
    private UUID seed(Instant occurred) {
        UUID payment=UUID.randomUUID(), id=UUID.randomUUID(), event=UUID.randomUUID();
        jdbc.update("INSERT INTO payments(id,merchant_id,customer_id,amount_cents,currency,status,idempotency_key) VALUES (?,?,?,12345,'MYR','CAPTURED',?)",payment,merchant,customer,payment.toString());
        byte[] bytes=("{\"id\":\""+event+"\",\"type\":\"payment.captured\",\"version\":1,\"merchant_id\":\""+merchant+"\",\"data\":{\"payment_id\":\""+payment+"\",\"amount_cents\":12345,\"currency\":\"MYR\",\"status\":\"CAPTURED\"}}").getBytes(StandardCharsets.UTF_8);
        jdbc.update("""
                INSERT INTO merchant_webhook_deliveries(id,event_id,merchant_id,endpoint_id,notification_key,event_type,event_version,payment_id,occurred_at,payload,next_attempt_at,expires_at)
                VALUES (?,?,?,?,'capture:'||?::text,'payment.captured',1,?,?,?,?,?)
                """,id,event,merchant,endpoint,payment,payment,Timestamp.from(occurred),bytes,Timestamp.from(occurred),Timestamp.from(occurred.plus(Duration.ofHours(24))));
        return id;
    }
    private Map<String,Object> row(UUID id){return jdbc.queryForMap("SELECT * FROM merchant_webhook_deliveries WHERE id=?",id);}
    private Instant due(UUID id){return ((Timestamp)row(id).get("next_attempt_at")).toInstant();}
    private long attemptCount(UUID id){return jdbc.queryForObject("SELECT count(*) FROM merchant_webhook_attempts WHERE delivery_id=?",Long.class,id);}
    private String attemptResult(UUID id){return jdbc.queryForObject("SELECT result FROM merchant_webhook_attempts WHERE id=?",String.class,id);}
    private static void await(CountDownLatch latch){try{if(!latch.await(10,TimeUnit.SECONDS))throw new AssertionError("Latch timed out");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}
    private static final class SimulatedWorkerLoss extends Error {}
    private static final class SequenceClock extends Clock {
        private final Instant first,later; private final AtomicInteger calls=new AtomicInteger();
        SequenceClock(Instant first,Instant later){this.first=first;this.later=later;}
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){if(!zone.equals(ZoneOffset.UTC))throw new IllegalArgumentException();return this;}
        public Instant instant(){return calls.getAndIncrement()==0?first:later;}
    }
    private static final class PreSendClock extends Clock {
        private final Instant now; private final AtomicInteger calls=new AtomicInteger();
        private final CountDownLatch claimed=new CountDownLatch(1),release=new CountDownLatch(1);
        PreSendClock(Instant now){this.now=now;}
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){if(!zone.equals(ZoneOffset.UTC))throw new IllegalArgumentException();return this;}
        public Instant instant(){if(calls.incrementAndGet()==2){claimed.countDown();await(release);}return now;}
    }
}
