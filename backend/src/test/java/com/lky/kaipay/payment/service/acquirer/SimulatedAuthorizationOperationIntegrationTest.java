package com.lky.kaipay.payment.service.acquirer;

import com.lky.kaipay.AbstractPostgresIntegrationTest;
import com.lky.kaipay.common.exception.GatewayTimeoutException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.*;
import com.lky.kaipay.payment.service.acquirer.SimulatorPersistenceTestConfiguration.CommitFault;
import com.lky.kaipay.payment.service.acquirer.SimulatorPersistenceTestConfiguration.CommitFaults;

/** Uses independent JPA contexts over the retained real PostgreSQL database. */
class SimulatedAuthorizationOperationIntegrationTest extends AbstractPostgresIntegrationTest {
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired ObjectMapper objectMapper;
    UUID merchant;
    UUID customer;
    AnnotationConfigApplicationContext simulatorContext;
    MockBankAcquirerClient client;
    SimulatedAuthorizationOperationRepository operations;

    @BeforeEach void createFixtures() {
        merchant = UUID.randomUUID();
        customer = UUID.randomUUID();
        jdbc.update("INSERT INTO merchants(id,name,api_key_hash,status) VALUES (?,'Simulator test',?,'ACTIVE')", merchant, merchant.toString());
        jdbc.update("INSERT INTO customers(id,merchant_id,email,full_name) VALUES (?,?,'simulator@example.test','Simulator')", customer, merchant);
        openContext();
    }

    @AfterEach void cleanup() {
        if (simulatorContext != null) simulatorContext.close();
        jdbc.update("DELETE FROM consumed_events WHERE payment_id IN (SELECT id FROM payments WHERE merchant_id=?)", merchant);
        jdbc.update("DELETE FROM simulated_authorization_operations WHERE merchant_id=?", merchant);
        jdbc.update("DELETE FROM payments WHERE merchant_id=?", merchant);
        jdbc.update("DELETE FROM customers WHERE id=?", customer);
        jdbc.update("DELETE FROM merchants WHERE id=?", merchant);
    }

    @Test void independentCompetingClientsArbitrateOneImmutableOutcome() throws Exception {
        var request = prepare(5000);
        try (var second = context()) {
            var other = second.getBean(MockBankAcquirerClient.class);
            var ready = new CountDownLatch(8);
            var start = new CountDownLatch(1);
            try (var workers = Executors.newFixedThreadPool(8)) {
                List<Future<AcquirerAuthorizationResult>> futures = new ArrayList<>();
                for (int i = 0; i < 8; i++) {
                    var caller = i % 2 == 0 ? client : other;
                    futures.add(workers.submit(() -> {
                        ready.countDown();
                        if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("start gate timed out");
                        return caller.authorize(request);
                    }));
                }
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                start.countDown();
                List<AcquirerAuthorizationResult> results = new ArrayList<>();
                for (var future : futures) results.add(future.get(20, TimeUnit.SECONDS));
                assertThat(results).hasSize(8).allMatch(results.getFirst()::equals);
                assertThat(client.getEvaluationCount(request.paymentId())).isEqualTo(1);
                assertThat(operations.findById(request.paymentId()).orElseThrow().getResult()).isEqualTo(results.getFirst());
                assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id=?", String.class, request.paymentId())).isEqualTo("PROCESSING");
                assertThat(jdbc.queryForObject("SELECT count(*) FROM consumed_events WHERE payment_id=?", Long.class, request.paymentId())).isZero();
            } finally { start.countDown(); }
        }
    }

    @Test void progressionAndAcceptedResultSurviveFreshPersistenceContexts() {
        var request = prepare(888800);
        assertThatThrownBy(() -> client.authorize(request)).isInstanceOf(GatewayTimeoutException.class);
        assertThat(client.getEvaluationCount(request.paymentId())).isEqualTo(1);
        restartContext();
        assertThat(client.inquire(request)).isEmpty();
        assertThatThrownBy(() -> client.authorize(request)).isInstanceOf(GatewayTimeoutException.class);
        assertThat(client.getEvaluationCount(request.paymentId())).isEqualTo(2);
        restartContext();
        var accepted = client.authorize(request);
        assertThat(accepted.isApproved()).isTrue();
        restartContext();
        assertThat(client.inquire(request)).contains(accepted);
        assertThat(client.authorize(request)).isEqualTo(accepted);
        assertThat(client.getEvaluationCount(request.paymentId())).isEqualTo(3);
    }

    @Test void persistentUnknownAndNoAcceptanceRemainDifferentAcrossRestart() {
        var unknown = prepare(777700);
        var rejected = prepare(666600);
        assertThatThrownBy(() -> client.authorize(unknown)).isInstanceOf(GatewayTimeoutException.class);
        var rejection = client.authorize(rejected);
        assertThat(rejection.isRejectedNoAcceptance()).isTrue();
        restartContext();
        assertThatThrownBy(() -> client.authorize(unknown)).isInstanceOf(GatewayTimeoutException.class);
        assertThat(client.getEvaluationCount(unknown.paymentId())).isEqualTo(2);
        assertThat(client.authorize(rejected)).isEqualTo(rejection);
        assertThat(client.getEvaluationCount(rejected.paymentId())).isEqualTo(1);
        assertThat(client.inquire(unknown)).isEmpty();
    }

    @Test void conflictAndMissingIntentNeverEvaluateOrFabricateOperation() {
        var request = prepare(5000);
        assertThatThrownBy(() -> client.authorize(new AuthorizationRequest(request.paymentId(), merchant, 5001, "USD")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.authorize(new AuthorizationRequest(request.paymentId(), UUID.randomUUID(), 5000, "USD")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.authorize(new AuthorizationRequest(request.paymentId(), merchant, 5000, "EUR")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(client.getEvaluationCount(request.paymentId())).isZero();
        assertThatThrownBy(() -> client.authorize(new AuthorizationRequest(UUID.randomUUID(), merchant, 5000, "USD")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(operations.findById(request.paymentId()).orElseThrow().getResult()).isNull();
    }

    @Test void newIntentCannotReplaceExistingDefinitiveEvidence() {
        var request = prepare(5000);
        var accepted = client.authorize(request);
        assertThatThrownBy(() -> operations.saveAndFlush(SimulatedAuthorizationOperation.intent(request)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(client.inquire(request)).contains(accepted);
        assertThat(client.getEvaluationCount(request.paymentId())).isEqualTo(1);
    }

    @Test void outerTransactionIsRejectedAndResponseDelayHoldsNoDatabaseLocks() throws Exception {
        var request = prepare(5000);
        var transactions = new TransactionTemplate(simulatorContext.getBean(PlatformTransactionManager.class));
        assertThatThrownBy(() -> transactions.execute(status -> client.authorize(request)))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        assertThat(client.getEvaluationCount(request.paymentId())).isZero();
        var accepted = new CountDownLatch(1);
        var releaseResponse = new CountDownLatch(1);
        try (var worker = Executors.newSingleThreadExecutor()) {
            var response = worker.submit(() -> {
                var result = client.authorize(request);
                accepted.countDown();
                if (!releaseResponse.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("response gate timed out");
                return result;
            });
            try {
                assertThat(accepted.await(10, TimeUnit.SECONDS)).isTrue();
                new TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource)).execute(status -> {
                    jdbc.queryForObject("SELECT payment_id FROM simulated_authorization_operations WHERE payment_id=? FOR UPDATE NOWAIT", UUID.class, request.paymentId());
                    jdbc.queryForObject("SELECT id FROM payments WHERE id=? FOR UPDATE NOWAIT", UUID.class, request.paymentId());
                    return null;
                });
                assertThat(client.inquire(request)).isPresent();
            } finally { releaseResponse.countDown(); }
            assertThat(response.get(10, TimeUnit.SECONDS)).isEqualTo(client.inquire(request).orElseThrow());
        }
    }

    @Test void uncertainCommitRehydratesBothCommittedAndRolledBackSimulatorTransactions() {
        var committedRequest = prepare(5000);
        var faults = simulatorContext.getBean(CommitFaults.class);
        faults.next.set(CommitFault.COMMITTED_RESPONSE_LOST);
        assertThatThrownBy(() -> client.authorize(committedRequest)).isInstanceOf(TransactionSystemException.class);
        var durable = client.inquire(committedRequest).orElseThrow();
        assertThat(client.authorize(committedRequest)).isEqualTo(durable);
        assertThat(client.getEvaluationCount(committedRequest.paymentId())).isEqualTo(1);

        var rolledBackRequest = prepare(5000);
        faults.next.set(CommitFault.ROLLED_BACK_RESPONSE_LOST);
        assertThatThrownBy(() -> client.authorize(rolledBackRequest)).isInstanceOf(TransactionSystemException.class);
        assertThat(client.inquire(rolledBackRequest)).isEmpty();
        assertThat(client.getEvaluationCount(rolledBackRequest.paymentId())).isZero();
        assertThat(client.authorize(rolledBackRequest).isApproved()).isTrue();
        assertThat(client.getEvaluationCount(rolledBackRequest.paymentId())).isEqualTo(1);
    }

    @Test void forciblyTerminatedChildJvmLeavesCommittedAcceptanceAvailableToFreshContext() throws Exception {
        var request = prepare(5000);
        UUID eventId = UUID.randomUUID();
        String originalInitiation = objectMapper.writeValueAsString(Map.of(
                "eventId", eventId, "eventType", "PaymentInitiatedEvent", "version", 1,
                "payload", Map.of("paymentId", request.paymentId(), "amountCents", request.amountCents(), "currency", request.currency())));
        Path childLog = Files.createTempFile("kaipay-simulator-child-", ".log");
        Process child = null;
        try {
            String java = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
            if (!Files.exists(Path.of(java))) java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
            child = new ProcessBuilder(java, "-cp", classpath, SimulatorChild.class.getName(),
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), request.paymentId().toString(), merchant.toString())
                    .redirectErrorStream(true).redirectOutput(childLog.toFile()).start();
            Process runningChild = child;
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(40)).until(() ->
                    Files.readAllLines(childLog).stream().anyMatch(line -> line.startsWith("S_COMMITTED|")) || !runningChild.isAlive());
            String childOutput = Files.readString(childLog);
            String evidence = childOutput.lines().filter(line -> line.startsWith("S_COMMITTED|")).findFirst()
                    .orElseThrow(() -> new AssertionError("Child failed before S commit: " + childOutput));
            assertThat(child.isAlive()).as("Child is held after committed S and before any payment finalization").isTrue();
            assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id=?", String.class, request.paymentId())).isEqualTo("PROCESSING");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM consumed_events WHERE payment_id=?", Long.class, request.paymentId())).isZero();
            child.destroyForcibly();
            assertThat(child.waitFor(10, TimeUnit.SECONDS)).isTrue();
            restartContext();
            String[] accepted = evidence.split("\\|");
            var recovered = client.inquire(request).orElseThrow();
            assertThat(recovered.getAuthorizationCode()).isEqualTo(accepted[1]);
            assertThat(recovered.getTimestamp()).isEqualTo(Instant.parse(accepted[2]));
            assertThat(client.authorize(request)).isEqualTo(recovered);
            assertThat(client.getEvaluationCount(request.paymentId())).isEqualTo(1);
            // A surviving application instance receives the original initiation through the real
            // broker after the S-owner process dies. This is not a whole-application restart.
            kafka.send("kaipay.payment.requests", request.paymentId().toString(), originalInitiation).get(10, TimeUnit.SECONDS);
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id=?", String.class, request.paymentId())).isEqualTo("AUTHORIZED");
                assertThat(jdbc.queryForObject("SELECT gateway_reference FROM payments WHERE id=?", String.class, request.paymentId()))
                        .isEqualTo(recovered.getAuthorizationCode());
                assertThat(jdbc.queryForObject("SELECT count(*) FROM consumed_events WHERE event_id=? AND payment_id=? AND status='AUTHORIZED'", Long.class,
                        eventId, request.paymentId())).isEqualTo(1);
            });
            assertThat(client.inquire(request)).contains(recovered);
            assertThat(client.getEvaluationCount(request.paymentId())).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM dead_letter_events WHERE payment_id=?", Long.class, request.paymentId())).isZero();
        } finally {
            if (child != null && child.isAlive()) { child.destroyForcibly(); child.waitFor(10, TimeUnit.SECONDS); }
            Files.deleteIfExists(childLog);
        }
    }

    /** A real separate process, stopped only after the proxied S transaction has committed. */
    public static class SimulatorChild {
        public static void main(String[] args) throws Exception {
            var source = new org.springframework.jdbc.datasource.DriverManagerDataSource(args[0], args[1], args[2]);
            // Do not invoke static methods on the outer integration-test class: initializing its
            // Testcontainers superclass would start a second, unrelated database/broker in the child.
            try (var context = new AnnotationConfigApplicationContext()) {
                context.registerBean(DataSource.class, () -> source, definition -> definition.setDestroyMethodName(""));
                context.register(SimulatorPersistenceTestConfiguration.class);
                context.refresh();
                var client = context.getBean(MockBankAcquirerClient.class);
                var result = client.authorize(new AuthorizationRequest(UUID.fromString(args[3]), UUID.fromString(args[4]), 5000, "USD"));
                System.out.println("S_COMMITTED|" + result.getAuthorizationCode() + "|" + result.getTimestamp());
                System.out.flush();
                new CountDownLatch(1).await();
            }
        }
    }

    private AuthorizationRequest prepare(long amount) {
        UUID payment = UUID.randomUUID();
        jdbc.update("INSERT INTO payments(id,merchant_id,customer_id,amount_cents,currency,status,idempotency_key) VALUES (?,?,?,?,'USD','PROCESSING',?)",
                payment, merchant, customer, amount, payment.toString());
        var request = new AuthorizationRequest(payment, merchant, amount, "USD");
        operations.saveAndFlush(SimulatedAuthorizationOperation.intent(request));
        return request;
    }

    private void restartContext() { simulatorContext.close(); openContext(); }
    private void openContext() {
        simulatorContext = context();
        client = simulatorContext.getBean(MockBankAcquirerClient.class);
        operations = simulatorContext.getBean(SimulatedAuthorizationOperationRepository.class);
    }
    private AnnotationConfigApplicationContext context() {
        return createContext(dataSource);
    }
    private static AnnotationConfigApplicationContext createContext(DataSource source) {
        var context = new AnnotationConfigApplicationContext();
        // This context borrows the application's pool; closing a simulator context must not close it.
        context.registerBean(DataSource.class, () -> source, definition -> definition.setDestroyMethodName(""));
        context.register(SimulatorPersistenceTestConfiguration.class);
        context.refresh();
        return context;
    }

}
