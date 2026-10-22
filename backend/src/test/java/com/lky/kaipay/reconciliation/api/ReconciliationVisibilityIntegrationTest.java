package com.lky.kaipay.reconciliation.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lky.kaipay.AbstractPostgresIntegrationTest;
import com.lky.kaipay.merchant.domain.Merchant;
import com.lky.kaipay.merchant.repository.MerchantRepository;
import com.lky.kaipay.security.SecurityHttpFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

/** Actual HTTP sessions, PostgreSQL audit rows and scoped queries; no financial operation is invoked. */
class ReconciliationVisibilityIntegrationTest extends AbstractPostgresIntegrationTest {
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired MerchantRepository merchants;
    private Merchant a, b;
    private UUID runA, runB;
    private final UUID paymentA = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private final UUID paymentB = UUID.fromString("30000000-0000-0000-0000-000000000002");
    private SecurityHttpFixtures.Account merchantUser, adminUser;
    private static final String PRIVATE = "PRIVATE_RECONCILIATION_MARKER";
    private static final String BASE = "/v1/reconciliation/runs";
    private static final String ADMIN = "/v1/admin/reconciliation/runs";

    @BeforeEach void fixtures() throws Exception {
        a = merchants.save(Merchant.builder().name("Reconciliation A").apiKeyHash(UUID.randomUUID().toString()).build());
        b = merchants.save(Merchant.builder().name("Reconciliation B").apiKeyHash(UUID.randomUUID().toString()).build());
        runA = run(a.getId(), Instant.parse("2026-10-22T04:00:00Z"));
        runB = run(b.getId(), Instant.parse("2026-10-22T04:10:00Z"));
        observation(runA, a.getId(), paymentA, "AUTHORIZATION_MISMATCH", Map.of("paymentAmountCents", 101,
                "paymentCurrency", "MYR", "paymentStatus", "AUTHORIZED", "operationPresent", true, "merchantMatches", true,
                "referenceMatches", false, "authorizationCode", PRIVATE, "metadata", PRIVATE));
        observation(runA, a.getId(), paymentB, "MATCHED", Map.of("paymentAmountCents", 100,
                "paymentCurrency", "USD", "operationPresent", true, "merchantMatches", true, "disposition", "APPROVED"));
        observation(runB, b.getId(), UUID.randomUUID(), "OWNERSHIP_CONFLICT", Map.of("operationPresent", true,
                "merchantMatches", false, "operationAmountCents", 777, "operationCurrency", "USD", "namespace", PRIVATE));
        merchantUser = security().provision("MERCHANT", true, a.getId());
        adminUser = security().provision("ADMIN", true);
    }
    @AfterEach void cleanup() {
        for (Merchant merchant : List.of(a, b)) {
            jdbc.update("DELETE FROM reconciliation_observations WHERE merchant_id=?", merchant.getId());
            jdbc.update("DELETE FROM reconciliation_runs WHERE merchant_id=?", merchant.getId());
            merchants.deleteById(merchant.getId());
        }
    }
    @Test void authenticationRoleAndNoMutationRoutes() {
        var forged = new HttpHeaders(); forged.set("X-Merchant-Id", a.getId().toString());
        for (String path : List.of(BASE, BASE + "/" + runA, BASE + "/" + runA + "/observations", ADMIN + "?merchantId=" + a.getId()))
            assertThat(get(path, forged).getStatusCode().value()).isEqualTo(401);
        var merchant = merchantHeaders(); var admin = adminHeaders(); admin.set("X-Merchant-Id", a.getId().toString());
        var missingMerchant = new HttpHeaders(); missingMerchant.putAll(merchant); missingMerchant.remove("X-Merchant-Id");
        for (String path : List.of(BASE, BASE + "/" + runA, BASE + "/" + runA + "/observations"))
            assertThat(get(path, missingMerchant).getStatusCode().value()).isEqualTo(403);
        assertThat(get(BASE, admin).getStatusCode().value()).isEqualTo(403);
        assertThat(get(ADMIN + "?merchantId=" + a.getId(), merchant).getStatusCode().value()).isEqualTo(403);
        for (String path : List.of(BASE, BASE + "/" + runA, BASE + "/" + runA + "/repair", ADMIN)) {
            for (HttpMethod method : List.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE))
                assertThat(http.exchange(path, method, new HttpEntity<>("{}", merchant), String.class).getStatusCode().value()).isEqualTo(403);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reconciliation_runs WHERE merchant_id=?", Integer.class, a.getId())).isEqualTo(1);
    }
    @Test void merchantScopeSafeProjectionFiltersAndReportImmutability() throws Exception {
        var headers = merchantHeaders(); var before = auditRows(a.getId());
        var list = get(BASE, headers); var detail = get(BASE + "/" + runA, headers);
        var observations = get(BASE + "/" + runA + "/observations", headers);
        assertThat(list.getStatusCode().value()).isEqualTo(200);
        assertThat(data(list).path("content").size()).isEqualTo(1);
        assertThat(data(list).path("content").get(0).path("runId").asText()).isEqualTo(runA.toString());
        assertThat(data(detail).path("assessmentScope").asText()).isEqualTo("AUTHORIZATION_V1");
        var first = data(observations).path("content").get(0);
        assertThat(first.path("paymentId").asText()).isEqualTo(paymentA.toString());
        assertThat(first.path("evidence").path("referenceMatches").asBoolean(true)).isFalse();
        assertThat(first.path("reasonCodes").toString()).contains("REFERENCE_DIFFERS").doesNotContain(PRIVATE);
        assertThat(first.path("explanation").asText()).contains("durable simulator outcome", "no financial changes");
        assertThat(data(get(BASE + "/" + runA + "/observations?primaryClass=NON_MATCHED", headers)).path("totalElements").asInt()).isEqualTo(1);
        assertThat(data(get(BASE + "/" + runA + "/observations?primaryClass=MATCHED", headers)).path("content").get(0).path("evidence").path("paymentCurrency").asText()).isEqualTo("USD");
        assertThat(data(get(BASE + "/" + runA + "/observations?paymentId=" + UUID.randomUUID(), headers)).path("totalElements").asInt()).isZero();
        var foreign = get(BASE + "/" + runB, headers); var missing = get(BASE + "/" + UUID.randomUUID(), headers);
        assertThat(foreign.getStatusCode().value()).isEqualTo(404);
        assertThat(mapper.readTree(foreign.getBody()).path("message")).isEqualTo(mapper.readTree(missing.getBody()).path("message"));
        assertThat(get(BASE + "/" + runB + "/observations", headers).getStatusCode().value()).isEqualTo(404);
        headers.set("X-Merchant-Id", b.getId().toString()); assertThat(get(BASE, headers).getStatusCode().value()).isEqualTo(403);
        for (var result : List.of(list, detail, observations)) assertThat(result.getBody()).doesNotContain(PRIVATE, "authorizationCode", "metadata", "payload", "actor", "inputFingerprint");
        assertThat(auditRows(a.getId())).isEqualTo(before);
    }
    @Test void adminRequiresExplicitScopeAndUsesSameOwnershipRedaction() throws Exception {
        var headers = adminHeaders();
        for (String path : List.of(ADMIN, ADMIN + "/" + runB, ADMIN + "/" + runB + "/observations"))
            assertThat(get(path, headers).getStatusCode().value()).isEqualTo(400);
        String scope = "?merchantId=" + b.getId();
        assertThat(data(get(ADMIN + scope, headers)).path("content").get(0).path("runId").asText()).isEqualTo(runB.toString());
        var observations = get(ADMIN + "/" + runB + "/observations" + scope, headers);
        var evidence = data(observations).path("content").get(0).path("evidence");
        assertThat(evidence.path("operationPresent").asBoolean()).isTrue();
        assertThat(evidence.path("merchantMatches").asBoolean(true)).isFalse();
        assertThat(evidence.has("operationAmountCents")).isFalse();
        assertThat(evidence.has("operationCurrency")).isFalse();
        assertThat(observations.getBody()).doesNotContain(PRIVATE);
        assertThat(get(ADMIN + "/" + runB + "?merchantId=" + a.getId(), headers).getStatusCode().value()).isEqualTo(404);
        assertThat(get(ADMIN + "?merchantId=invalid", headers).getStatusCode().value()).isEqualTo(400);
        jdbc.update("UPDATE merchants SET status='SUSPENDED' WHERE id=?", b.getId());
        assertThat(get(ADMIN + scope, headers).getStatusCode().value()).isEqualTo(200);
    }
    @Test void boundsAndUnknownSortOrClassAreRejectedAndFixedOrderingIsStable() throws Exception {
        UUID newer = run(a.getId(), Instant.parse("2026-10-22T05:00:00Z")); var headers = merchantHeaders();
        assertThat(data(get(BASE + "?size=1", headers)).path("content").get(0).path("runId").asText()).isEqualTo(newer.toString());
        assertThat(data(get(BASE + "?size=1&page=1", headers)).path("content").get(0).path("runId").asText()).isEqualTo(runA.toString());
        for (String query : List.of("size=101", "size=0", "page=-1", "page=101", "sortBy=actor", "direction=asc"))
            assertThat(get(BASE + "?" + query, headers).getStatusCode().value()).isEqualTo(400);
        for (String query : List.of("primaryClass=invalid", "paymentId=invalid", "sortBy=paymentId", "page=101"))
            assertThat(get(BASE + "/" + runA + "/observations?" + query, headers).getStatusCode().value()).isEqualTo(400);
        assertThat(get(BASE + "?size=100&page=100", headers).getStatusCode().value()).isEqualTo(200);
    }
    @Test void currentMembershipAndDisabledSessionsRemainEnforced() {
        var headers = merchantHeaders();
        jdbc.update("DELETE FROM demo_merchant_memberships WHERE user_id=? AND merchant_id=?", merchantUser.id(), a.getId());
        assertThat(get(BASE, headers).getStatusCode().value()).isEqualTo(403);
        jdbc.update("INSERT INTO demo_merchant_memberships(user_id,merchant_id) VALUES (?,?)", merchantUser.id(), a.getId());
        assertThat(get(BASE + "/" + runA, headers).getStatusCode().value()).isEqualTo(200);
        jdbc.update("UPDATE demo_users SET enabled=false WHERE id=?", merchantUser.id());
        assertThat(get(BASE, headers).getStatusCode().value()).isEqualTo(401);
    }
    private UUID run(UUID merchant, Instant observed) throws Exception {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO reconciliation_runs(id,merchant_id,scope,ruleset,schema_version,build_revision,from_inclusive,cutoff_exclusive,
                grace_seconds,payment_limit,execution_limit_seconds,actor,reason,input_fingerprint,snapshot_observed_at,completed_at,
                consumer_group,cohort_count,counts,report)
            VALUES (?,?,'AUTHORIZATION_V1','AUTHORIZATION_V1.0',1,'test-revision',?::timestamptz,?::timestamptz,300,1000,15,
                'test','VERIFICATION',?, ?,?,'kaipay-payment-processor-group',2,?::jsonb,?::jsonb)
            """, id, merchant, "2026-10-21T00:00:00Z", "2026-10-22T03:50:00Z", "0".repeat(64),
                Timestamp.from(observed), Timestamp.from(observed), "{\"MATCHED\":1,\"AUTHORIZATION_MISMATCH\":1}", mapper.writeValueAsString(Map.of("payload", PRIVATE)));
        return id;
    }
    private void observation(UUID run, UUID merchant, UUID payment, String type, Map<String, ?> evidence) throws Exception {
        jdbc.update("""
            INSERT INTO reconciliation_observations(run_id,payment_id,merchant_id,primary_class,severity,reason_codes,evidence,explanation,observation)
            VALUES (?,?,?,?,'WARNING',?::jsonb,?::jsonb,?, '{}'::jsonb)
            """, run, payment, merchant, type, mapper.writeValueAsString(List.of("REFERENCE_DIFFERS", PRIVATE)), mapper.writeValueAsString(evidence), PRIVATE);
    }
    private String auditRows(UUID merchant) { return jdbc.queryForObject("SELECT string_agg(report::text,',' ORDER BY id) FROM reconciliation_runs WHERE merchant_id=?", String.class, merchant); }
    private HttpHeaders merchantHeaders() { var headers = security().login(merchantUser).headers(); headers.set("X-Merchant-Id", a.getId().toString()); return headers; }
    private HttpHeaders adminHeaders() { return security().login(adminUser).headers(); }
    private ResponseEntity<String> get(String path, HttpHeaders headers) { return http.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class); }
    private JsonNode data(ResponseEntity<String> response) throws Exception { return mapper.readTree(response.getBody()).path("data"); }
}
