package com.lky.kaipay.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lky.kaipay.AbstractPostgresIntegrationTest;
import com.lky.kaipay.payment.service.AuthorizationDelivery;
import com.lky.kaipay.payment.service.AuthorizationRecoveryService;
import com.lky.kaipay.payment.service.PaymentService;
import com.lky.kaipay.payment.service.acquirer.MockBankAcquirerClient;
import com.lky.kaipay.refund.api.dto.CreateRefundRequest;
import com.lky.kaipay.refund.service.RefundService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

class WebhookIntentIntegrationTest extends AbstractPostgresIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired PaymentService paymentService;
    @Autowired RefundService refundService;
    @Autowired AuthorizationRecoveryService recovery;
    @Autowired MockBankAcquirerClient simulator;
    @Autowired com.lky.kaipay.payment.service.acquirer.SimulatedAuthorizationOperationRepository operations;
    @Autowired WebhookIntentService intents;
    UUID merchant, customer;

    @BeforeEach void fixture() {
        merchant = UUID.randomUUID(); customer = UUID.randomUUID();
        jdbc.update("INSERT INTO merchants(id,name,api_key_hash,webhook_url,status) VALUES (?,'Webhook fixture',?,'https://inert.example.test/webhook','ACTIVE')", merchant, merchant.toString());
        jdbc.update("INSERT INTO customers(id,merchant_id,email,full_name) VALUES (?,?,'fixture@example.test','Webhook fixture')", customer, merchant);
    }

    @AfterEach void cleanup() {
        jdbc.execute("DROP TRIGGER IF EXISTS webhook_intent_test_failure ON merchant_webhook_deliveries");
        jdbc.execute("DROP TRIGGER IF EXISTS webhook_marker_test_failure ON consumed_events");
        jdbc.execute("DROP FUNCTION IF EXISTS webhook_fixture_reject()");
        jdbc.update("DELETE FROM merchant_webhook_attempts WHERE delivery_id IN (SELECT id FROM merchant_webhook_deliveries WHERE merchant_id=?)", merchant);
        jdbc.update("DELETE FROM merchant_webhook_deliveries WHERE merchant_id=?", merchant);
        jdbc.update("DELETE FROM merchant_webhook_endpoints WHERE merchant_id=?", merchant);
        jdbc.update("DELETE FROM ledger_entries WHERE journal_id IN (SELECT id FROM journals WHERE merchant_id=?)", merchant);
        jdbc.update("DELETE FROM journals WHERE merchant_id=?", merchant);
        jdbc.update("DELETE FROM accounts WHERE merchant_id=?", merchant);
        jdbc.update("DELETE FROM payment_events_outbox WHERE aggregate_id IN (SELECT id::text FROM payments WHERE merchant_id=? UNION SELECT id::text FROM refunds WHERE merchant_id=?)", merchant, merchant);
        jdbc.update("DELETE FROM refunds WHERE merchant_id=?", merchant);
        jdbc.update("DELETE FROM consumed_events WHERE payment_id IN (SELECT id FROM payments WHERE merchant_id=?)", merchant);
        jdbc.update("DELETE FROM simulated_authorization_operations WHERE merchant_id=?", merchant);
        jdbc.update("DELETE FROM payments WHERE merchant_id=?", merchant);
        jdbc.update("DELETE FROM customers WHERE id=?", customer);
        jdbc.update("DELETE FROM merchants WHERE id=?", merchant);
    }

    @ParameterizedTest @CsvSource({"5000,AUTHORIZED,payment.authorized", "999900,DECLINED,payment.declined", "666600,FAILED,payment.failed"})
    void terminalAuthorizationIsAtomicStableAndDoesNotExposePrivateEvidence(long amount, String status, String type) throws Exception {
        enroll(); UUID payment = payment(amount, "MYR", "CREATED"), event = UUID.randomUUID();
        var delivery = delivery(payment, event, amount, "MYR");
        var request = recovery.prepareAuthorization(delivery, "webhook-intent").orElseThrow();
        assertThat(count("merchant_webhook_deliveries")).isZero();
        var result = simulator.authorize(request);
        recovery.completeAuthorization(delivery, "webhook-intent", result);
        byte[] original = payload(payment, type);
        UUID externalId = jdbc.queryForObject("SELECT event_id FROM merchant_webhook_deliveries WHERE merchant_id=?", UUID.class, merchant);
        assertThat(externalId).isNotEqualTo(event);
        assertThat(jdbc.queryForObject("SELECT source_event_id FROM merchant_webhook_deliveries WHERE merchant_id=?", UUID.class, merchant)).isEqualTo(event);
        assertThat(status(payment)).isEqualTo(status);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM consumed_events WHERE payment_id=?", Long.class, payment)).isEqualTo(1);
        JsonNode body = mapper.readTree(original);
        assertThat(body.path("id").asText()).isEqualTo(externalId.toString());
        assertThat(body.path("merchant_id").asText()).isEqualTo(merchant.toString());
        assertThat(body.path("data").path("status").asText()).isEqualTo(status);
        Set<String> fields = new HashSet<>(); body.path("data").fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("payment_id", "amount_cents", "currency", "status");
        assertThat(new String(original, StandardCharsets.UTF_8)).doesNotContain("idempotency", "gateway", "failure", "customer", "private_fixture");
        recovery.completeAuthorization(delivery, "webhook-intent", result);
        assertThat(recovery.prepareAuthorization(delivery, "another-group")).isEmpty();
        assertThat(count("merchant_webhook_deliveries")).isEqualTo(1);
        assertThat(payload(payment, type)).isEqualTo(original);
        assertThat(jdbc.queryForObject("SELECT event_id FROM merchant_webhook_deliveries WHERE merchant_id=?", UUID.class, merchant)).isEqualTo(externalId);
    }

    @Test void captureAndEachPartialRefundReuseOutboxIdsAndDoNotDuplicateOnReplay() throws Exception {
        enroll(); UUID payment = payment(5000, "MYR", "AUTHORIZED");
        paymentService.capturePayment(merchant, payment, null);
        paymentService.capturePayment(merchant, payment, null);
        assertThat(count("merchant_webhook_deliveries")).isEqualTo(1);
        JsonNode captured = mapper.readTree(payload(payment, "payment.captured"));
        assertOutboxIdentity(captured, "PaymentCapturedEvent");
        var request = CreateRefundRequest.builder().amountCents(1000L).reason("private_fixture_reason").build();
        var first = refundService.createRefund(merchant, payment, "refund-one", request);
        assertThat(refundService.createRefund(merchant, payment, "refund-one", request).getId()).isEqualTo(first.getId());
        var second = refundService.createRefund(merchant, payment, "refund-two", request);
        var third = refundService.createRefund(merchant, payment, "refund-three", CreateRefundRequest.builder().amountCents(3000L).build());
        var rows = jdbc.queryForList("SELECT event_id,payload FROM merchant_webhook_deliveries WHERE merchant_id=? AND event_type='refund.completed' ORDER BY created_at,id", merchant);
        assertThat(rows).hasSize(3);
        assertThat(rows.stream().map(row -> row.get("event_id"))).doesNotHaveDuplicates();
        Set<String> refundIds = new HashSet<>();
        int partial = 0;
        for (var row : rows) {
            JsonNode body = mapper.readTree((byte[])row.get("payload"));
            assertOutboxIdentity(body, "PaymentRefundedEvent");
            refundIds.add(body.path("data").path("refund_id").asText());
            assertThat(body.path("data").path("status").asText()).isEqualTo("COMPLETED");
            if (body.path("data").path("payment_status").asText().equals("PARTIALLY_REFUNDED")) partial++;
            assertThat(new String((byte[])row.get("payload"), StandardCharsets.UTF_8)).doesNotContain("reason", "private_fixture_reason");
        }
        assertThat(refundIds).containsExactlyInAnyOrder(first.getId().toString(), second.getId().toString(), third.getId().toString());
        assertThat(partial).isEqualTo(2);
        assertThat(status(payment)).isEqualTo("REFUNDED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM journals WHERE merchant_id=?", Long.class, merchant)).isEqualTo(4);
    }

    @Test void failedCaptureIntentRollsBackMoneyLedgerAndOutbox() {
        enroll(); UUID payment = payment(5000, "MYR", "AUTHORIZED"); rejectIntent();
        assertThatThrownBy(() -> paymentService.capturePayment(merchant, payment, null)).isInstanceOf(RuntimeException.class);
        assertThat(status(payment)).isEqualTo("AUTHORIZED");
        assertThat(count("merchant_webhook_deliveries")).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM journals WHERE merchant_id=?", Long.class, merchant)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_events_outbox WHERE aggregate_id=?", Long.class, payment.toString())).isZero();
    }

    @Test void failedRefundIntentRollsBackRefundStateLedgerAndOutbox() {
        enroll(); UUID payment = payment(5000, "MYR", "AUTHORIZED");
        paymentService.capturePayment(merchant, payment, null); rejectIntent();
        assertThatThrownBy(() -> refundService.createRefund(merchant, payment, "failed-refund", CreateRefundRequest.builder().amountCents(1000L).build())).isInstanceOf(RuntimeException.class);
        assertThat(status(payment)).isEqualTo("CAPTURED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refunds WHERE merchant_id=?", Long.class, merchant)).isZero();
        assertThat(count("merchant_webhook_deliveries")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM journals WHERE merchant_id=?", Long.class, merchant)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_events_outbox WHERE aggregate_id=?", Long.class, payment.toString())).isEqualTo(1);
    }

    @ParameterizedTest @CsvSource({"INTENT", "MARKER"})
    void failedAuthorizationPersistenceRollsBackFinalStateAndMarkerThenReusesDurableResult(String failureAt) {
        enroll(); UUID payment = payment(5000, "MYR", "CREATED");
        var delivery = delivery(payment, UUID.randomUUID(), 5000, "MYR");
        var request = recovery.prepareAuthorization(delivery, "atomic").orElseThrow();
        var accepted = simulator.authorize(request);
        if (failureAt.equals("INTENT")) rejectIntent();
        else {
            rejectionFunction();
            jdbc.execute("CREATE TRIGGER webhook_marker_test_failure BEFORE INSERT ON consumed_events FOR EACH ROW WHEN (NEW.payment_id='" + payment + "'::uuid) EXECUTE FUNCTION webhook_fixture_reject()");
        }
        assertThatThrownBy(() -> recovery.completeAuthorization(delivery, "atomic", accepted)).isInstanceOf(RuntimeException.class);
        assertThat(status(payment)).isEqualTo("PROCESSING");
        assertThat(count("merchant_webhook_deliveries")).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM consumed_events WHERE payment_id=?", Long.class, payment)).isZero();
        assertThat(simulator.inquire(request)).contains(accepted);
        jdbc.execute("DROP TRIGGER IF EXISTS webhook_intent_test_failure ON merchant_webhook_deliveries");
        jdbc.execute("DROP TRIGGER IF EXISTS webhook_marker_test_failure ON consumed_events");
        recovery.completeAuthorization(delivery, "atomic", accepted);
        assertThat(status(payment)).isEqualTo("AUTHORIZED");
        assertThat(count("merchant_webhook_deliveries")).isEqualTo(1);
        assertThat(simulator.getEvaluationCount(payment)).isEqualTo(1);
    }

    @Test void enrollmentIsForwardOnlyAndLegacyUrlHasNoAuthority() {
        UUID before = payment(5000, "MYR", "AUTHORIZED");
        paymentService.capturePayment(merchant, before, null);
        assertThat(count("merchant_webhook_deliveries")).isZero();
        enroll();
        paymentService.capturePayment(merchant, before, null);
        assertThat(count("merchant_webhook_deliveries")).isZero();
        UUID after = payment(5000, "MYR", "AUTHORIZED");
        paymentService.capturePayment(merchant, after, null);
        assertThat(count("merchant_webhook_deliveries")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT webhook_url FROM merchants WHERE id=?", String.class, merchant)).isEqualTo("https://inert.example.test/webhook");
    }

    @Test void unresolvedAndHistoricalFinalEvidenceDoNotInventNotifications() {
        enroll();
        UUID unresolved = payment(777700, "MYR", "CREATED");
        var pending = delivery(unresolved, UUID.randomUUID(), 777700, "MYR");
        var request = recovery.prepareAuthorization(pending, "unresolved").orElseThrow();
        assertThatThrownBy(() -> simulator.authorize(request)).isInstanceOf(RuntimeException.class);
        assertThat(status(unresolved)).isEqualTo("PROCESSING");
        for (String currency : List.of("MYR", "USD")) {
            UUID historical = payment(5000, currency, "AUTHORIZED");
            assertThat(recovery.prepareAuthorization(delivery(historical, UUID.randomUUID(), 5000, currency), "legacy")).isEmpty();
        }
        assertThat(count("merchant_webhook_deliveries")).isZero();
    }

    @Test void retainedDefinitiveNonMyrCompletionRemainsReadableWithoutNewNotification() {
        enroll(); UUID payment = payment(5000, "USD", "PROCESSING");
        // Synthetic historical evidence: the old provider already accepted this USD authorization.
        jdbc.update("""
                INSERT INTO simulated_authorization_operations
                (payment_id,namespace,contract_version,merchant_id,amount_cents,currency,disposition,evaluation_count,authorization_code,result_at)
                VALUES (?,'kaipay-sim-auth-v1',1,?,5000,'USD','APPROVED',1,'AUTH-RETAINED-USD',NOW())
                """, payment, merchant);
        var retained = operations.findById(payment).orElseThrow().getResult();
        recovery.completeAuthorization(delivery(payment, UUID.randomUUID(), 5000, "USD"), "legacy-resolved", retained);
        assertThat(status(payment)).isEqualTo("AUTHORIZED");
        assertThat(count("merchant_webhook_deliveries")).isZero();
        assertThat(jdbc.queryForObject("SELECT currency FROM payments WHERE id=?", String.class, payment)).isEqualTo("USD");
    }

    @Test void competingCaptureRequestsCannotDuplicateIntentJournalOrOutbox() throws Exception {
        enroll(); UUID payment = payment(5000, "MYR", "AUTHORIZED");
        var gate = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> action = () -> {
                gate.await(10, TimeUnit.SECONDS);
                try { paymentService.capturePayment(merchant, payment, null); return true; }
                catch (RuntimeException expectedLoser) {
                    assertThat(expectedLoser).isInstanceOfAny(org.springframework.orm.ObjectOptimisticLockingFailureException.class,
                            org.springframework.dao.DataIntegrityViolationException.class);
                    return false;
                }
            };
            var first = pool.submit(action); var second = pool.submit(action);
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS))).contains(true);
        }
        assertThat(status(payment)).isEqualTo("CAPTURED");
        assertThat(count("merchant_webhook_deliveries")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM journals WHERE merchant_id=?", Long.class, merchant)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_events_outbox WHERE aggregate_id=?", Long.class, payment.toString())).isEqualTo(1);
    }

    @Test void competingEquivalentRefundRequestsCommitOneRefundNotification() throws Exception {
        enroll(); UUID payment = payment(5000, "MYR", "AUTHORIZED");
        paymentService.capturePayment(merchant, payment, null);
        var request = CreateRefundRequest.builder().amountCents(1000L).build();
        var gate = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<UUID> action = () -> { gate.await(10, TimeUnit.SECONDS); return refundService.createRefund(merchant, payment, "concurrent-refund", request).getId(); };
            var first = pool.submit(action); var second = pool.submit(action);
            assertThat(first.get(15, TimeUnit.SECONDS)).isEqualTo(second.get(15, TimeUnit.SECONDS));
        }
        assertThat(status(payment)).isEqualTo("PARTIALLY_REFUNDED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refunds WHERE merchant_id=?", Long.class, merchant)).isEqualTo(1);
        assertThat(count("merchant_webhook_deliveries")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM journals WHERE merchant_id=?", Long.class, merchant)).isEqualTo(2);
    }

    @Test void competingAuthorizationGroupsCommitOneNotificationAndTwoMarkers() throws Exception {
        enroll(); UUID payment = payment(5000, "MYR", "CREATED");
        var a = delivery(payment, UUID.randomUUID(), 5000, "MYR");
        var b = delivery(payment, UUID.randomUUID(), 5000, "MYR");
        var request = recovery.prepareAuthorization(a, "competitor-a").orElseThrow();
        recovery.prepareAuthorization(b, "competitor-b");
        var accepted = simulator.authorize(request);
        var gate = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { gate.await(10, TimeUnit.SECONDS); recovery.completeAuthorization(a, "competitor-a", accepted); return null; });
            var second = pool.submit(() -> { gate.await(10, TimeUnit.SECONDS); recovery.completeAuthorization(b, "competitor-b", accepted); return null; });
            first.get(15, TimeUnit.SECONDS); second.get(15, TimeUnit.SECONDS);
        }
        assertThat(count("merchant_webhook_deliveries")).isEqualTo(1);
        assertThat(status(payment)).isEqualTo("AUTHORIZED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM consumed_events WHERE payment_id=?", Long.class, payment)).isEqualTo(2);
        assertThat(simulator.getEvaluationCount(payment)).isEqualTo(1);
    }

    @Test void intentRequiresExistingTransaction() {
        assertThatThrownBy(() -> intents.authorization(null, UUID.randomUUID())).isInstanceOf(IllegalTransactionStateException.class);
    }

    private void assertOutboxIdentity(JsonNode body, String internalType) {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_events_outbox WHERE event_type=? AND payload->>'eventId'=?", Long.class, internalType, body.path("id").asText())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT event_id FROM journals WHERE event_id=?", UUID.class, UUID.fromString(body.path("id").asText()))).isEqualTo(UUID.fromString(body.path("id").asText()));
    }
    private UUID enroll() {
        UUID endpoint = UUID.randomUUID();
        jdbc.update("INSERT INTO merchant_webhook_endpoints(id,merchant_id,url,hostname,path,pins,key_id,secret_ref,state,created_by,reason_code) VALUES (?,?,'https://receiver.example.test/hook','receiver.example.test','/hook',ARRAY['8.8.8.8'],?,?,'ACTIVE','synthetic-operator','TEST')", endpoint, merchant, UUID.randomUUID(), "fixture_" + endpoint.toString().replace("-", ""));
        return endpoint;
    }
    private UUID payment(long amount, String currency, String state) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO payments(id,merchant_id,customer_id,amount_cents,currency,status,idempotency_key,gateway_reference,metadata) VALUES (?,?,?,?,?,?,?,'AUTH-LEGACY','{\"private_fixture\":\"do-not-export\"}'::jsonb)", id, merchant, customer, amount, currency, state, id.toString());
        return id;
    }
    private AuthorizationDelivery delivery(UUID payment, UUID eventId, long amount, String currency) {
        var root = mapper.createObjectNode().put("eventId", eventId.toString()).put("eventType", AuthorizationDelivery.EVENT_TYPE).put("version", 1);
        root.set("payload", mapper.createObjectNode().put("paymentId", payment.toString()).put("amountCents", amount).put("currency", currency));
        return AuthorizationDelivery.fromJson(root);
    }
    private void rejectIntent() {
        rejectionFunction();
        jdbc.execute("CREATE TRIGGER webhook_intent_test_failure BEFORE INSERT ON merchant_webhook_deliveries FOR EACH ROW WHEN (NEW.merchant_id='" + merchant + "'::uuid) EXECUTE FUNCTION webhook_fixture_reject()");
    }
    private void rejectionFunction() { jdbc.execute("CREATE FUNCTION webhook_fixture_reject() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'injected persistence failure' USING ERRCODE='23514'; END $$"); }
    private byte[] payload(UUID payment, String type) { return jdbc.queryForObject("SELECT payload FROM merchant_webhook_deliveries WHERE payment_id=? AND event_type=?", byte[].class, payment, type); }
    private String status(UUID payment) { return jdbc.queryForObject("SELECT status FROM payments WHERE id=?", String.class, payment); }
    private long count(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE merchant_id=?", Long.class, merchant); }
}
