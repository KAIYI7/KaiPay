package com.lky.kaipay.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lky.kaipay.AbstractPostgresIntegrationTest;
import com.lky.kaipay.consumer.repository.ConsumedEventRepository;
import com.lky.kaipay.payment.domain.PaymentStatus;
import com.lky.kaipay.payment.service.AuthorizationDelivery;
import com.lky.kaipay.payment.service.AuthorizationRecoveryService;
import com.lky.kaipay.payment.service.acquirer.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

class AuthorizationRecoveryIntegrationTest extends AbstractPostgresIntegrationTest {
    @Autowired AuthorizationRecoveryService recovery;
    @Autowired MockBankAcquirerClient simulator;
    @Autowired SimulatedAuthorizationOperationRepository operations;
    @Autowired ConsumedEventRepository markers;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired com.lky.kaipay.payment.consumer.PaymentProcessingConsumer consumer;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    UUID merchant, customer;
    List<UUID> payments = new ArrayList<>();
    static final String GROUP = "recovery-contract-test";

    @BeforeEach void fixture() {
        merchant = UUID.randomUUID(); customer = UUID.randomUUID();
        jdbc.update("INSERT INTO merchants(id,name,api_key_hash,status) VALUES (?,'Recovery fixture',?,'ACTIVE')", merchant, merchant.toString());
        jdbc.update("INSERT INTO customers(id,merchant_id,email,full_name) VALUES (?,?,'recovery@example.test','Recovery')", customer, merchant);
    }

    @AfterEach void cleanup() {
        for (UUID payment : payments) {
            jdbc.update("DELETE FROM consumed_events WHERE payment_id=?", payment);
            jdbc.update("DELETE FROM simulated_authorization_operations WHERE payment_id=?", payment);
            jdbc.update("DELETE FROM payments WHERE id=?", payment);
        }
        jdbc.update("DELETE FROM customers WHERE id=?", customer);
        jdbc.update("DELETE FROM merchants WHERE id=?", merchant);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "0", "-1", "1.5", "\"5000\"", "9223372036854775808", "true"})
    void invalidMoneyNeverPreparesAnOperation(String amountJson) throws Exception {
        UUID payment = payment(5000, "MYR", "CREATED");
        ObjectNode root = event(payment, UUID.randomUUID(), 5000, "MYR");
        ((ObjectNode) root.get("payload")).set("amountCents", mapper.readTree(amountJson));
        assertThatThrownBy(() -> AuthorizationDelivery.fromJson(root)).isInstanceOf(IllegalArgumentException.class);
        assertThat(operations.findById(payment)).isEmpty();
        assertThat(status(payment)).isEqualTo("CREATED");
    }

    @Test void strictIdentityVersionAndOptionalMetadataCannotHideBehindConsumedMarker() {
        UUID payment = payment(5000, "MYR", "CREATED");
        UUID eventId = UUID.randomUUID();
        var root = event(payment, eventId, 5000, "MYR");
        var delivery = AuthorizationDelivery.fromJson(root);
        var request = recovery.prepareAuthorization(delivery, GROUP).orElseThrow();
        var accepted = simulator.authorize(request);
        recovery.completeAuthorization(delivery, GROUP, accepted);
        var changed = root.deepCopy(); ((ObjectNode) changed.get("payload")).put("amountCents", 5001);
        assertThatThrownBy(() -> recovery.prepareAuthorization(AuthorizationDelivery.fromJson(changed), GROUP))
                .isInstanceOf(IllegalStateException.class);
        var tenantConflict = root.deepCopy(); tenantConflict.put("merchantId", UUID.randomUUID().toString());
        assertThatThrownBy(() -> recovery.prepareAuthorization(AuthorizationDelivery.fromJson(tenantConflict), GROUP))
                .isInstanceOf(IllegalStateException.class);
        var customerConflict = root.deepCopy(); ((ObjectNode) customerConflict.get("payload")).put("customerId", UUID.randomUUID().toString());
        assertThatThrownBy(() -> recovery.prepareAuthorization(AuthorizationDelivery.fromJson(customerConflict), GROUP))
                .isInstanceOf(IllegalStateException.class);
        var keyConflict = root.deepCopy(); ((ObjectNode) keyConflict.get("payload")).put("idempotencyKey", "different");
        assertThatThrownBy(() -> recovery.prepareAuthorization(AuthorizationDelivery.fromJson(keyConflict), GROUP))
                .isInstanceOf(IllegalStateException.class);
        for (String field : List.of("eventId", "version", "aggregateId")) {
            var malformed = root.deepCopy(); malformed.put(field, "invalid");
            assertThatThrownBy(() -> AuthorizationDelivery.fromJson(malformed)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(simulator.inquire(request)).contains(accepted);
        assertThat(simulator.getEvaluationCount(payment)).isEqualTo(1);
        assertThat(markers.findByPaymentIdOrderByProcessedAtAsc(payment)).hasSize(1);
    }

    @Test void stableLegacyIdentityAndUsdMeaningValidateFinalEvidenceAcrossGroups() {
        UUID payment = payment(5000, "USD", "AUTHORIZED");
        jdbc.update("UPDATE payments SET gateway_reference='AUTH-LEGACY' WHERE id=?", payment);
        var legacy = (ObjectNode) event(payment, UUID.randomUUID(), 5000, "USD").get("payload");
        legacy.remove("currency");
        var first = AuthorizationDelivery.fromJson(legacy);
        var second = AuthorizationDelivery.fromJson(legacy.deepCopy());
        assertThat(first.currency()).isEqualTo("USD");
        assertThat(first.eventId()).isEqualTo(second.eventId());
        assertThat(recovery.prepareAuthorization(first, GROUP)).isEmpty();
        assertThat(recovery.prepareAuthorization(second, GROUP)).isEmpty();
        assertThat(recovery.prepareAuthorization(second, GROUP + "-other")).isEmpty();
        assertThat(markers.findByPaymentIdOrderByProcessedAtAsc(payment)).hasSize(2);
        assertThat(operations.findById(payment)).isEmpty();
        UUID myr = payment(5000, "MYR", "CREATED");
        legacy.put("paymentId", myr.toString());
        assertThatThrownBy(() -> recovery.prepareAuthorization(AuthorizationDelivery.fromJson(legacy), GROUP))
                .isInstanceOf(IllegalStateException.class);
        assertThat(operations.findById(myr)).isEmpty();
    }

    @ParameterizedTest @EnumSource(PaymentStatus.class)
    void allLegacyStatesHaveExplicitEligibilityWithoutFabricatedOutcomes(PaymentStatus state) {
        UUID payment = payment(5000, "MYR", state.name());
        jdbc.update("UPDATE payments SET gateway_reference='AUTH-LEGACY',failure_code='LEGACY',failure_message='Legacy evidence' WHERE id=?", payment);
        var delivery = AuthorizationDelivery.fromJson(event(payment, UUID.randomUUID(), 5000, "MYR"));
        if (state == PaymentStatus.CREATED) {
            assertThat(recovery.prepareAuthorization(delivery, GROUP)).isPresent();
            assertThat(status(payment)).isEqualTo("PROCESSING");
            assertThat(operations.findById(payment)).get().extracting(SimulatedAuthorizationOperation::getEvaluationCount).isEqualTo(0L);
        } else if (state == PaymentStatus.PROCESSING) {
            assertThatThrownBy(() -> recovery.prepareAuthorization(delivery, GROUP)).isInstanceOf(IllegalStateException.class);
            assertThat(operations.findById(payment)).isEmpty();
            assertThat(markers.findByPaymentIdOrderByProcessedAtAsc(payment)).isEmpty();
        } else {
            assertThat(recovery.prepareAuthorization(delivery, GROUP)).isEmpty();
            assertThat(status(payment)).isEqualTo(state.name());
            assertThat(operations.findById(payment)).isEmpty();
            assertThat(markers.findByPaymentIdOrderByProcessedAtAsc(payment)).singleElement()
                    .extracting(m -> m.getStatus()).isEqualTo(state == PaymentStatus.FAILED ? "FAILED" : state == PaymentStatus.DECLINED ? "DECLINED" : "AUTHORIZED");
        }
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = {"AUTHORIZED", "CAPTURED", "REFUND_PENDING", "PARTIALLY_REFUNDED", "REFUNDED", "FAILED"})
    void lateApprovalNeverReversesLaterStateAndContradictionsQuarantine(PaymentStatus state) {
        UUID payment = payment(5000, "MYR", "CREATED");
        var delivery = AuthorizationDelivery.fromJson(event(payment, UUID.randomUUID(), 5000, "MYR"));
        var request = recovery.prepareAuthorization(delivery, GROUP).orElseThrow();
        var accepted = simulator.authorize(request); // Delayed response after independently committed acceptance.
        jdbc.update("UPDATE payments SET status=?,gateway_reference=?,failure_code=? WHERE id=?", state.name(),
                state == PaymentStatus.FAILED ? null : accepted.getAuthorizationCode(),
                state == PaymentStatus.FAILED ? "OPERATOR_FAILURE" : null, payment);
        long journals = count("journals"), entries = count("ledger_entries"), refunds = count("refunds"), outbox = count("payment_events_outbox");
        if (state == PaymentStatus.FAILED) {
            assertThatThrownBy(() -> recovery.completeAuthorization(delivery, GROUP, accepted)).isInstanceOf(IllegalStateException.class);
            assertThat(markers.findByPaymentIdOrderByProcessedAtAsc(payment)).isEmpty();
        } else {
            recovery.completeAuthorization(delivery, GROUP, accepted);
            assertThat(recovery.prepareAuthorization(delivery, GROUP)).isEmpty();
            assertThat(markers.findByPaymentIdOrderByProcessedAtAsc(payment)).singleElement().extracting(m -> m.getStatus()).isEqualTo("AUTHORIZED");
        }
        assertThat(status(payment)).isEqualTo(state.name());
        assertThat(simulator.inquire(request)).contains(accepted);
        assertThat(simulator.getEvaluationCount(payment)).isEqualTo(1);
        assertThat(List.of(count("journals"), count("ledger_entries"), count("refunds"), count("payment_events_outbox")))
                .containsExactly(journals, entries, refunds, outbox);
    }

    @Test void contradictoryCallerResultNeverBecomesPaymentAuthority() {
        UUID payment = payment(5000, "MYR", "CREATED");
        var delivery = AuthorizationDelivery.fromJson(event(payment, UUID.randomUUID(), 5000, "MYR"));
        var request = recovery.prepareAuthorization(delivery, GROUP).orElseThrow();
        var accepted = simulator.authorize(request);
        assertThatThrownBy(() -> recovery.completeAuthorization(delivery, GROUP, AcquirerAuthorizationResult.approved("DIFFERENT")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(status(payment)).isEqualTo("PROCESSING");
        assertThat(markers.findByPaymentIdOrderByProcessedAtAsc(payment)).isEmpty();
        recovery.completeAuthorization(delivery, GROUP, accepted);
        assertThat(status(payment)).isEqualTo("AUTHORIZED");
    }

    @Test void consumerRejectsAmbientTransactionBeforePreparationOrGatewayCall() throws Exception {
        UUID payment = payment(5000, "MYR", "CREATED");
        String payload = mapper.writeValueAsString(event(payment, UUID.randomUUID(), 5000, "MYR"));
        var record = new org.apache.kafka.clients.consumer.ConsumerRecord<String, String>("kaipay.payment.requests", 0, 0L, payment.toString(), payload);
        var tx = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> tx.execute(status -> { consumer.processPaymentRequest(record, null); return null; }))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        assertThat(status(payment)).isEqualTo("CREATED");
        assertThat(operations.findById(payment)).isEmpty();
        assertThat(markers.findByPaymentIdOrderByProcessedAtAsc(payment)).isEmpty();
    }

    @Test void legacyFinalWithoutRequiredEvidenceAndExplicitUnsupportedVersionQuarantine() {
        for (String state : List.of("AUTHORIZED", "DECLINED", "FAILED")) {
            UUID payment = payment(5000, "MYR", state);
            var root = event(payment, UUID.randomUUID(), 5000, "MYR");
            var delivery = AuthorizationDelivery.fromJson(root);
            assertThatThrownBy(() -> recovery.prepareAuthorization(delivery, GROUP)).isInstanceOf(IllegalStateException.class);
            assertThat(operations.findById(payment)).isEmpty();
            assertThat(markers.findByPaymentIdOrderByProcessedAtAsc(payment)).isEmpty();
            root.put("version", 2);
            assertThatThrownBy(() -> AuthorizationDelivery.fromJson(root)).isInstanceOf(IllegalArgumentException.class);
            root.put("version", 1).put("eventType", "OtherEvent");
            assertThatThrownBy(() -> AuthorizationDelivery.fromJson(root)).isInstanceOf(IllegalArgumentException.class);
            root.put("eventType", AuthorizationDelivery.EVENT_TYPE);
            ((ObjectNode) root.get("payload")).put("customerId", "1-1-1-1-1");
            assertThatThrownBy(() -> AuthorizationDelivery.fromJson(root)).isInstanceOf(IllegalArgumentException.class);
            assertThat(status(payment)).isEqualTo(state);
        }
    }

    @Test void simultaneousCrossPaymentEventCollisionPreservesBothProviderOutcomesButOnlyOneMarker() throws Exception {
        UUID a = payment(5000, "MYR", "CREATED"), b = payment(5000, "MYR", "CREATED"), sharedEvent = UUID.randomUUID();
        var da = AuthorizationDelivery.fromJson(event(a, sharedEvent, 5000, "MYR"));
        var db = AuthorizationDelivery.fromJson(event(b, sharedEvent, 5000, "MYR"));
        var ra = recovery.prepareAuthorization(da, GROUP).orElseThrow();
        var rb = recovery.prepareAuthorization(db, GROUP).orElseThrow();
        var acceptedA = simulator.authorize(ra); var acceptedB = simulator.authorize(rb);
        var gate = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<Throwable> fa = pool.submit(() -> completeAfterGate(gate, da, acceptedA));
            Future<Throwable> fb = pool.submit(() -> completeAfterGate(gate, db, acceptedB));
            var errors = Arrays.asList(fa.get(15, TimeUnit.SECONDS), fb.get(15, TimeUnit.SECONDS));
            assertThat(errors.stream().filter(Objects::isNull)).hasSize(1);
            assertThat(errors.stream().filter(Objects::nonNull)).singleElement().isInstanceOf(IllegalStateException.class);
        }
        assertThat(List.of(status(a), status(b))).containsExactlyInAnyOrder("AUTHORIZED", "PROCESSING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM consumed_events WHERE event_id=? AND consumer_group=?", Long.class, sharedEvent, GROUP)).isEqualTo(1);
        assertThat(simulator.inquire(ra)).contains(acceptedA); assertThat(simulator.inquire(rb)).contains(acceptedB);
        UUID loser = "PROCESSING".equals(status(a)) ? a : b;
        assertThat(jdbc.queryForObject("SELECT gateway_reference FROM payments WHERE id=?", String.class, loser)).isNull();
    }

    @ParameterizedTest @CsvSource({"true,true", "true,false", "false,true", "false,false"})
    void overlappingProcessingDeliveriesConvergeAcrossEventAndGroupIdentities(boolean sameEvent, boolean sameGroup) throws Exception {
        UUID payment = payment(5000, "MYR", "CREATED"), eventId = UUID.randomUUID();
        var a = AuthorizationDelivery.fromJson(event(payment, eventId, 5000, "MYR"));
        var b = AuthorizationDelivery.fromJson(event(payment, sameEvent ? eventId : UUID.randomUUID(), 5000, "MYR"));
        String groupB = sameGroup ? GROUP : GROUP + "-other";
        var requestA = recovery.prepareAuthorization(a, GROUP).orElseThrow();
        var requestB = recovery.prepareAuthorization(b, groupB).orElseThrow();
        assertThat(status(payment)).isEqualTo("PROCESSING");
        var afterAcceptance = new CyclicBarrier(2);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> {
                var accepted = simulator.authorize(requestA);
                afterAcceptance.await(10, TimeUnit.SECONDS);
                recovery.completeAuthorization(a, GROUP, accepted);
                return accepted;
            });
            var second = workers.submit(() -> {
                var accepted = simulator.authorize(requestB);
                afterAcceptance.await(10, TimeUnit.SECONDS);
                recovery.completeAuthorization(b, groupB, accepted);
                return accepted;
            });
            var resultA = first.get(15, TimeUnit.SECONDS);
            var resultB = second.get(15, TimeUnit.SECONDS);
            assertThat(resultA).isEqualTo(resultB);
            assertThat(simulator.inquire(requestA)).contains(resultA);
        }
        assertThat(status(payment)).isEqualTo("AUTHORIZED");
        assertThat(simulator.getEvaluationCount(payment)).isEqualTo(1);
        assertThat(markers.findByPaymentIdOrderByProcessedAtAsc(payment)).hasSize(sameEvent && sameGroup ? 1 : 2);
    }

    @ParameterizedTest @ValueSource(strings = {"CAPTURED", "REFUNDED", "PROCESSING", "OTHER"})
    void unknownHistoricalMarkerStatusesAreQuarantined(String markerStatus) {
        UUID payment = payment(5000, "MYR", "AUTHORIZED"), id = UUID.randomUUID();
        jdbc.update("UPDATE payments SET gateway_reference='AUTH-LEGACY' WHERE id=?", payment);
        jdbc.update("INSERT INTO consumed_events(event_id,consumer_group,payment_id,event_type,status) VALUES (?,?,?,?,?)",
                id, GROUP, payment, AuthorizationDelivery.EVENT_TYPE, markerStatus);
        var delivery = AuthorizationDelivery.fromJson(event(payment, id, 5000, "MYR"));
        assertThatThrownBy(() -> recovery.prepareAuthorization(delivery, GROUP)).isInstanceOf(IllegalStateException.class);
        assertThat(operations.findById(payment)).isEmpty();
        assertThat(status(payment)).isEqualTo("AUTHORIZED");
        assertThat(markers.findByPaymentIdOrderByProcessedAtAsc(payment)).singleElement().extracting(m -> m.getStatus()).isEqualTo(markerStatus);
    }

    @Test void preexistingSuccessfulMarkerForCreatedOrOtherPaymentCannotSuppressValidation() {
        UUID a = payment(5000, "MYR", "CREATED"), b = payment(5000, "MYR", "CREATED"), id = UUID.randomUUID();
        jdbc.update("INSERT INTO consumed_events(event_id,consumer_group,payment_id,event_type,status) VALUES (?,?,?,?,?)",
                id, GROUP, a, AuthorizationDelivery.EVENT_TYPE, "AUTHORIZED");
        for (UUID payment : List.of(a, b)) {
            var delivery = AuthorizationDelivery.fromJson(event(payment, id, 5000, "MYR"));
            assertThatThrownBy(() -> recovery.prepareAuthorization(delivery, GROUP)).isInstanceOf(IllegalStateException.class);
            assertThat(operations.findById(payment)).isEmpty();
            assertThat(status(payment)).isEqualTo("CREATED");
        }
    }

    private Throwable completeAfterGate(CyclicBarrier gate, AuthorizationDelivery d, AcquirerAuthorizationResult result) throws Exception {
        gate.await(10, TimeUnit.SECONDS);
        try { recovery.completeAuthorization(d, GROUP, result); return null; }
        catch (RuntimeException error) { return error; } // Every observed result is asserted by the caller.
    }
    private UUID payment(long amount, String currency, String status) {
        UUID id = UUID.randomUUID(); payments.add(id);
        jdbc.update("INSERT INTO payments(id,merchant_id,customer_id,amount_cents,currency,status,idempotency_key) VALUES (?,?,?,?,?,?,?)", id, merchant, customer, amount, currency, status, id.toString());
        return id;
    }
    private ObjectNode event(UUID payment, UUID eventId, long amount, String currency) {
        ObjectNode root = mapper.createObjectNode().put("eventId", eventId.toString()).put("eventType", AuthorizationDelivery.EVENT_TYPE).put("version", 1);
        root.set("payload", mapper.createObjectNode().put("paymentId", payment.toString()).put("amountCents", amount).put("currency", currency));
        return root;
    }
    private String status(UUID id) { return jdbc.queryForObject("SELECT status FROM payments WHERE id=?", String.class, id); }
    private long count(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class); }
}
