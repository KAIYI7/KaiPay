package com.lky.kaipay.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lky.kaipay.AbstractPostgresIntegrationTest;
import com.lky.kaipay.payment.api.dto.CreatePaymentRequest;
import com.lky.kaipay.payment.service.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class IntegrationPaymentIntegrationTest extends AbstractPostgresIntegrationTest {
    static final UUID INTEGRATION = UUID.randomUUID(), MERCHANT = UUID.randomUUID(), CUSTOMER = UUID.randomUUID();
    static final UUID OTHER_INTEGRATION = UUID.randomUUID(), OTHER_MERCHANT = UUID.randomUUID(), OTHER_CUSTOMER = UUID.randomUUID();
    static final byte[] SECRET = new byte[32], ROTATED = new byte[32], OTHER = new byte[32];
    static { new SecureRandom().nextBytes(SECRET); new SecureRandom().nextBytes(ROTATED); new SecureRandom().nextBytes(OTHER); }
    @Autowired TestRestTemplate http;
    @org.springframework.boot.test.web.server.LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired IntegrationProperties properties;
    @Autowired IntegrationPaymentService service;
    @Autowired PaymentService core;
    @MockitoSpyBean IntegrationPaymentStore store;
    @DynamicPropertySource static void integrationConfig(DynamicPropertyRegistry registry) {
        registry.add("kaipay.integrations.enabled", () -> true);
        bind(registry, 0, INTEGRATION, MERCHANT, CUSTOMER, "first", SECRET);
        registry.add("kaipay.integrations.bindings[0].keys[1].key-id", () -> "rotated");
        registry.add("kaipay.integrations.bindings[0].keys[1].sha256", () -> hash(ROTATED));
        bind(registry, 1, OTHER_INTEGRATION, OTHER_MERCHANT, OTHER_CUSTOMER, "other", OTHER);
    }
    static void bind(DynamicPropertyRegistry r, int index, UUID id, UUID merchant, UUID customer, String key, byte[] secret) {
        String p = "kaipay.integrations.bindings[" + index + "].";
        r.add(p + "integration-id", () -> id.toString()); r.add(p + "merchant-id", () -> merchant.toString());
        r.add(p + "customer-id", () -> customer.toString()); r.add(p + "enabled", () -> true);
        r.add(p + "keys[0].key-id", () -> key); r.add(p + "keys[0].sha256", () -> hash(secret));
    }
    static String hash(byte[] secret) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(secret)); }
        catch (Exception failure) { throw new AssertionError(failure); }
    }
    @BeforeEach void prepare() {
        reset(store);
        jdbc.execute("TRUNCATE merchants,customers,payments,idempotency_records,payment_events_outbox CASCADE");
        seed(MERCHANT, CUSTOMER); seed(OTHER_MERCHANT, OTHER_CUSTOMER);
    }
    void seed(UUID merchant, UUID customer) {
        jdbc.update("INSERT INTO merchants(id,name,api_key_hash,status) VALUES(?,'Synthetic integration',?,'ACTIVE')", merchant, UUID.randomUUID().toString());
        jdbc.update("INSERT INTO customers(id,merchant_id,email,full_name) VALUES(?,?,'actor@example.invalid','Synthetic actor')", customer, merchant);
    }
    HttpHeaders headers(String key, byte[] secret) {
        HttpHeaders h = new HttpHeaders(); h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth("kp_int_" + key + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(secret)); return h;
    }
    ResponseEntity<String> request(HttpMethod method, UUID ref, String body, HttpHeaders h) {
        return machineRequest(method, "/v1/integrations/payments/" + ref, body, h);
    }
    ResponseEntity<String> machineRequest(HttpMethod method, String path, String body, HttpHeaders h) {
        try {
            var builder = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + path))
                    .timeout(java.time.Duration.ofSeconds(15));
            h.forEach((name, values) -> values.forEach(value -> builder.header(name, value)));
            builder.method(method.name(), body == null ? java.net.http.HttpRequest.BodyPublishers.noBody()
                    : java.net.http.HttpRequest.BodyPublishers.ofString(body));
            var response = java.net.http.HttpClient.newHttpClient().send(builder.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
            return ResponseEntity.status(response.statusCode()).body(response.body());
        } catch (Exception failure) { throw new AssertionError(failure); }
    }
    ResponseEntity<String> put(UUID ref, long amount) {
        return request(HttpMethod.PUT, ref, "{\"amountCents\":" + amount + ",\"currency\":\"MYR\"}", headers("first", SECRET));
    }
    JsonNode data(ResponseEntity<String> response) throws Exception {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return mapper.readTree(response.getBody()).get("data");
    }
    IntegrationProperties.Binding binding() { return properties.bindings().getFirst(); }
    void create(UUID ref, long amount) {
        core.createPayment(MERCHANT, IntegrationPaymentStore.key(binding(), ref), CreatePaymentRequest.builder()
                .customerId(CUSTOMER).amountCents(amount).currency("MYR").metadata(Map.of()).build());
    }
    long count(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class); }

    @Test void retainedReplayRotationCacheExpiryAndCurrentStatus() throws Exception {
        UUID ref = UUID.randomUUID();
        var first = data(put(ref, 1234));
        String payment = first.get("paymentId").asText();
        jdbc.update("UPDATE payments SET status='AUTHORIZED',version=version+1,updated_at=now() WHERE id=?", UUID.fromString(payment));
        jdbc.update("UPDATE idempotency_records SET expires_at=now()-interval '1 day'");
        var replay = data(request(HttpMethod.PUT, ref, "{\"amountCents\":1234,\"currency\":\"MYR\"}", headers("rotated", ROTATED)));
        assertThat(replay.get("paymentId").asText()).isEqualTo(payment);
        assertThat(replay.get("status").asText()).isEqualTo("AUTHORIZED");
        assertThat(replay.get("version").asLong()).isEqualTo(1);
        assertThat(data(request(HttpMethod.GET, ref, null, headers("first", SECRET)))).isEqualTo(replay);
        assertThat(put(ref, 1235).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(count("payments")).isEqualTo(1); assertThat(count("payment_events_outbox")).isEqualTo(1);
        assertThat(count("idempotency_records")).isEqualTo(1);
        assertThat(count("journals")).isZero(); assertThat(count("refunds")).isZero();
    }
    @Test void tenantsSameReferenceAreIsolatedAndMachineCannotBorrowBrowserAuthority() throws Exception {
        UUID ref = UUID.randomUUID();
        String one = data(put(ref, 100)).get("paymentId").asText();
        assertThat(request(HttpMethod.GET, ref, null, headers("other", OTHER)).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        String two = data(request(HttpMethod.PUT, ref, "{\"amountCents\":100,\"currency\":\"MYR\"}", headers("other", OTHER))).get("paymentId").asText();
        assertThat(two).isNotEqualTo(one);
        for (String route : List.of("/v1/payments", "/v1/payments/" + one + "/capture", "/v1/payments/" + one + "/refunds",
                "/v1/ledger/journals", "/v1/events/dlt", "/v1/webhooks/endpoint")) {
            assertThat(machineRequest(HttpMethod.GET, route, null, headers("first", SECRET)).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }
        var browser = security().adminHeaders();
        assertThat(request(HttpMethod.GET, ref, null, browser).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        browser.setBearerAuth("kp_int_first." + Base64.getUrlEncoder().withoutPadding().encodeToString(SECRET));
        assertThat(machineRequest(HttpMethod.GET, "/v1/events/dlt", null, browser).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
    @Test void inactiveBindingAndBrowserMembershipDenyBeforeReplay() throws Exception {
        UUID ref = UUID.randomUUID(); data(put(ref, 100));
        jdbc.update("UPDATE merchants SET status='INACTIVE' WHERE id=?", MERCHANT);
        assertThat(request(HttpMethod.GET, ref, null, headers("first", SECRET)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        jdbc.update("UPDATE merchants SET status='ACTIVE' WHERE id=?", MERCHANT);
        security().merchantHeaders(MERCHANT);
        assertThat(put(ref, 100).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(count("payments")).isEqualTo(1);
    }
    @Test void strictPayloadAndCredentialBoundary() throws Exception {
        UUID ref = UUID.randomUUID();
        assertThat(request(HttpMethod.GET, ref, null, new HttpHeaders()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        var bad = headers("first", OTHER);
        assertThat(request(HttpMethod.GET, ref, null, bad).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        for (String header : List.of("Cookie", "Origin")) {
            var h = headers("first", SECRET); h.set(header, "denied");
            assertThat(request(HttpMethod.GET, ref, null, h).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        var merchantHeader = headers("first", SECRET); merchantHeader.set("X-Merchant-Id", OTHER_MERCHANT.toString());
        assertThat(request(HttpMethod.GET, ref, null, merchantHeader).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        for (String body : List.of("{}", "{\"amountCents\":0,\"currency\":\"MYR\"}", "{\"amountCents\":1.0,\"currency\":\"MYR\"}",
                "{\"amountCents\":\"1\",\"currency\":\"MYR\"}", "{\"amountCents\":1,\"amountCents\":2,\"currency\":\"MYR\"}",
                "{\"amountCents\":1,\"currency\":\"USD\"}", "{\"amountCents\":1,\"currency\":\"MYR\",\"metadata\":{}}", " ".repeat(1025)))
            assertThat(request(HttpMethod.PUT, ref, body, headers("first", SECRET)).getStatusCode()).as(body).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(count("payments")).isZero();
    }
    @Test void actualConcurrentEquivalentCreatesAllReturnSameIdentity() throws Exception {
        UUID ref = UUID.randomUUID(); var barrier = new CyclicBarrier(4); var calls = new AtomicInteger();
        doAnswer(invocation -> {
            var snapshot = (IntegrationPaymentStore.Snapshot) invocation.callRealMethod();
            if (calls.incrementAndGet() <= 4) barrier.await(10, TimeUnit.SECONDS);
            return snapshot;
        }).when(store).read(any(), eq(ref));
        try (var pool = Executors.newFixedThreadPool(4)) {
            var results = new ArrayList<Future<IntegrationPaymentService.Projection>>();
            for (int i=0;i<4;i++) results.add(pool.submit(() -> service.put(binding(), ref, 700)));
            var ids = new HashSet<UUID>();
            for (var result : results) ids.add(result.get(15, TimeUnit.SECONDS).paymentId());
            assertThat(ids).hasSize(1);
        }
        assertThat(count("payments")).isEqualTo(1); assertThat(count("payment_events_outbox")).isEqualTo(1);
        assertThat(count("idempotency_records")).isEqualTo(1);
    }
    @Test void competitorCommitBetweenPrelookupAndCoreCacheReadReturnsCurrentWinner() {
        UUID ref = UUID.randomUUID(); var calls = new AtomicInteger();
        doAnswer(invocation -> {
            var snapshot = invocation.callRealMethod();
            if (calls.incrementAndGet() == 1) {
                create(ref, 501);
                jdbc.update("UPDATE payments SET status='PROCESSING',version=version+1 WHERE idempotency_key=?", IntegrationPaymentStore.key(binding(), ref));
            }
            return snapshot;
        }).when(store).read(any(), eq(ref));
        var result = service.put(binding(), ref, 501);
        assertThat(result.status()).isEqualTo("PROCESSING"); assertThat(result.version()).isEqualTo(1);
        assertThat(count("payments")).isEqualTo(1); assertThat(count("payment_events_outbox")).isEqualTo(1);
    }
    @Test void concurrentDifferentAmountsProduceOneSuccessAndOneImmutableConflict() throws Exception {
        UUID ref = UUID.randomUUID(); var barrier = new CyclicBarrier(2); var calls = new AtomicInteger();
        doAnswer(invocation -> {
            var snapshot = invocation.callRealMethod();
            if (calls.incrementAndGet() <= 2) barrier.await(10, TimeUnit.SECONDS);
            return snapshot;
        }).when(store).read(any(), eq(ref));
        try (var pool = Executors.newFixedThreadPool(2)) {
            List<Future<Object>> results = new ArrayList<>();
            for (long amount : List.of(100L, 200L)) results.add(pool.submit(() -> {
                try { return service.put(binding(), ref, amount); }
                catch (IntegrationFailure failure) { return failure; }
            }));
            int success=0, conflict=0;
            for (var result : results) {
                Object value = result.get(15, TimeUnit.SECONDS);
                if (value instanceof IntegrationPaymentService.Projection) success++;
                else {
                    assertThat(value).isInstanceOf(IntegrationFailure.class);
                    assertThat(((IntegrationFailure) value).code).isEqualTo("IDEMPOTENCY_CONFLICT"); conflict++;
                }
            }
            assertThat(success).isEqualTo(1); assertThat(conflict).isEqualTo(1);
        }
        assertThat(count("payments")).isEqualTo(1); assertThat(count("payment_events_outbox")).isEqualTo(1);
        assertThat(count("idempotency_records")).isEqualTo(1);
    }
    @Test void acceptedCommitWithFailedProjectionRecoversSameIdentityOnReadAndRetry() throws Exception {
        UUID ref = UUID.randomUUID(); var calls = new AtomicInteger();
        doAnswer(invocation -> {
            if (calls.incrementAndGet() == 2) throw new org.springframework.dao.DataAccessResourceFailureException("fixture unavailable");
            return invocation.callRealMethod();
        }).when(store).read(any(), eq(ref));
        assertThat(put(ref, 333).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(count("payments")).isEqualTo(1);
        var read = data(request(HttpMethod.GET, ref, null, headers("first", SECRET)));
        assertThat(data(put(ref, 333)).get("paymentId").asText()).isEqualTo(read.get("paymentId").asText());
        assertThat(count("payment_events_outbox")).isEqualTo(1); assertThat(count("idempotency_records")).isEqualTo(1);
    }
    @Test void retainedTupleMismatchDoesNotReplayCachedSuccess() {
        UUID ref = UUID.randomUUID(); create(ref, 100);
        jdbc.update("UPDATE payments SET metadata='{\"unexpected\":true}'::jsonb WHERE idempotency_key=?", IntegrationPaymentStore.key(binding(), ref));
        assertThatThrownBy(() -> service.put(binding(), ref, 100)).isInstanceOf(IntegrationFailure.class).hasMessage("IDEMPOTENCY_CONFLICT");
        assertThatThrownBy(() -> service.get(binding(), ref)).isInstanceOf(IntegrationFailure.class).hasMessage("IDEMPOTENCY_CONFLICT");
        jdbc.update("UPDATE payments SET metadata='{}'::jsonb,currency='USD' WHERE idempotency_key=?", IntegrationPaymentStore.key(binding(), ref));
        assertThatThrownBy(() -> service.put(binding(), ref, 100)).isInstanceOf(IntegrationFailure.class).hasMessage("IDEMPOTENCY_CONFLICT");
        assertThat(count("payments")).isEqualTo(1); assertThat(count("payment_events_outbox")).isEqualTo(1);
    }
    @Test void realExpiredOrphanRecheckedAfterCompetitorCommitAndStableOrphanFailsClosed() {
        UUID ref = UUID.randomUUID(); orphan(ref); var calls = new AtomicInteger();
        doAnswer(invocation -> {
            var snapshot = invocation.callRealMethod();
            if (calls.incrementAndGet() == 1) create(ref, 502);
            return snapshot;
        }).when(store).read(any(), eq(ref));
        assertThat(service.put(binding(), ref, 502).amountCents()).isEqualTo(502);
        UUID stable = UUID.randomUUID(); orphan(stable);
        assertThatThrownBy(() -> service.put(binding(), stable, 1)).isInstanceOf(IntegrationFailure.class)
                .hasMessage("RECOVERY_REQUIRED");
        assertThat(count("payments")).isEqualTo(1);
    }
    void orphan(UUID reference) {
        jdbc.update("INSERT INTO idempotency_records(merchant_id,idempotency_key,request_hash,response_status,response_body,expires_at) VALUES(?,?,?,201,'{}',now()-interval '1 day')",
                MERCHANT, IntegrationPaymentStore.key(binding(), reference), "0".repeat(64));
    }
    @Test void coreCreationFailureRollsBackPaymentOutboxAndCacheAndDoesNotBecomeSuccess() throws Exception {
        jdbc.execute("ALTER TABLE payment_events_outbox ADD CONSTRAINT integration_test_reject CHECK (event_type <> 'PaymentInitiatedEvent')");
        try {
            var failed = put(UUID.randomUUID(), 100);
            assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(mapper.readTree(failed.getBody()).get("code").asText()).isEqualTo("TEMPORARILY_UNAVAILABLE");
            assertThat(count("payments")).isZero(); assertThat(count("payment_events_outbox")).isZero(); assertThat(count("idempotency_records")).isZero();
        } finally { jdbc.execute("ALTER TABLE payment_events_outbox DROP CONSTRAINT integration_test_reject"); }
    }
}
