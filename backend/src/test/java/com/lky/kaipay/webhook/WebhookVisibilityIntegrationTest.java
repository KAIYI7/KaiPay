package com.lky.kaipay.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lky.kaipay.AbstractPostgresIntegrationTest;
import com.lky.kaipay.customer.domain.Customer;
import com.lky.kaipay.customer.repository.CustomerRepository;
import com.lky.kaipay.merchant.domain.Merchant;
import com.lky.kaipay.merchant.repository.MerchantRepository;
import com.lky.kaipay.payment.domain.Payment;
import com.lky.kaipay.payment.repository.PaymentRepository;
import com.lky.kaipay.security.SecurityHttpFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Real persisted identities, PostgreSQL tenant predicates and HTTP session checks. No outbound delivery. */
class WebhookVisibilityIntegrationTest extends AbstractPostgresIntegrationTest {
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired MerchantRepository merchants;
    @Autowired CustomerRepository customers;
    @Autowired PaymentRepository payments;
    private Merchant merchantA, merchantB;
    private Customer customerA, customerB;
    private UUID endpointA, endpointB, deliveryA, deliveryB, paymentA;
    private SecurityHttpFixtures.Account accountA;
    private static final String PRIVATE = "PRIVATE_WEBHOOK_MARKER";

    @BeforeEach void fixtures() {
        merchantA = merchants.save(Merchant.builder().name("Webhook view A").apiKeyHash(UUID.randomUUID().toString()).build());
        merchantB = merchants.save(Merchant.builder().name("Webhook view B").apiKeyHash(UUID.randomUUID().toString()).build());
        customerA = customer(merchantA); customerB = customer(merchantB);
        endpointA = endpoint(merchantA); endpointB = endpoint(merchantB);
        deliveryA = delivery(merchantA, customerA, endpointA, Instant.parse("2026-10-21T03:00:00Z"));
        paymentA = jdbc.queryForObject("SELECT payment_id FROM merchant_webhook_deliveries WHERE id=?", UUID.class, deliveryA);
        deliveryB = delivery(merchantB, customerB, endpointB, Instant.parse("2026-10-21T04:00:00Z"));
        accountA = security().provision("MERCHANT", true, merchantA.getId());
    }

    @AfterEach void cleanup() {
        for (Merchant merchant : List.of(merchantA, merchantB)) {
            UUID id = merchant.getId();
            jdbc.update("DELETE FROM merchant_webhook_attempts WHERE delivery_id IN (SELECT id FROM merchant_webhook_deliveries WHERE merchant_id=?)", id);
            jdbc.update("DELETE FROM merchant_webhook_deliveries WHERE merchant_id=?", id);
            jdbc.update("DELETE FROM merchant_webhook_endpoints WHERE merchant_id=?", id);
            jdbc.update("DELETE FROM payments WHERE merchant_id=?", id);
            jdbc.update("DELETE FROM customers WHERE merchant_id=?", id);
            jdbc.update("DELETE FROM merchants WHERE id=?", id);
        }
    }

    @Test void routesRequireMerchantSessionAndExposeNoMutationCapability() {
        var forged = new HttpHeaders(); forged.set("X-Merchant-Id", merchantA.getId().toString());
        for (String path : List.of("/v1/webhooks/endpoint", "/v1/webhooks/deliveries", "/v1/webhooks/deliveries/" + deliveryA)) {
            assertThat(get(path, forged).getStatusCode().value()).isEqualTo(401);
            var admin = security().adminHeaders(); admin.set("X-Merchant-Id", merchantA.getId().toString());
            assertThat(get(path, admin).getStatusCode().value()).isEqualTo(403);
        }
        var headers = headers();
        for (String path : List.of("/v1/webhooks/endpoint", "/v1/webhooks/deliveries/" + deliveryA + "/retry",
                "/v1/webhooks/test-send")) {
            assertThat(http.exchange(path, HttpMethod.POST, new HttpEntity<>("{}", headers), String.class)
                    .getStatusCode().value()).isEqualTo(403);
        }
    }

    @Test void listDetailAndEndpointAreTenantQualifiedAndBrowserSafe() throws Exception {
        jdbc.update("""
                UPDATE merchant_webhook_deliveries SET state='TERMINAL',attempts=1,next_attempt_at=NULL,last_result='CONFIGURATION'
                WHERE id=?
                """, deliveryA);
        UUID key = jdbc.queryForObject("SELECT key_id FROM merchant_webhook_endpoints WHERE id=?", UUID.class, endpointA);
        jdbc.update("""
                INSERT INTO merchant_webhook_attempts(id,delivery_id,ordinal,started_at,completed_at,deadline,key_id,result,http_status,duration_ms)
                VALUES (?,?,1,NOW(),NOW(),NOW()+INTERVAL '30 seconds',?,'UNSAFE_RAW_ERROR',400,12)
                """, UUID.randomUUID(), deliveryA, key);
        var headers = headers();
        var list = get("/v1/webhooks/deliveries", headers);
        assertThat(list.getStatusCode().value()).isEqualTo(200);
        var content = data(list).path("content");
        assertThat(content.size()).isEqualTo(1);
        assertThat(content.get(0).path("id").asText()).isEqualTo(deliveryA.toString());
        assertThat(content.get(0).path("lastResult").asText()).isEqualTo("CONFIGURATION");
        var detail = get("/v1/webhooks/deliveries/" + deliveryA, headers);
        assertThat(detail.getStatusCode().value()).isEqualTo(200);
        assertThat(data(detail).path("attempts").size()).isEqualTo(1);
        assertThat(data(detail).path("attempts").get(0).path("result").asText()).isEqualTo("UNKNOWN");
        var endpoint = get("/v1/webhooks/endpoint", headers);
        assertThat(data(endpoint).path("enabled").asBoolean()).isTrue();
        assertThat(data(endpoint).path("endpointId").asText()).isEqualTo(endpointA.toString());
        for (var response : List.of(list, detail, endpoint)) {
            assertThat(response.getBody()).doesNotContain(PRIVATE, "receiver.invalid", "93.184.216.34", "secret_ref", "secretRef",
                    "payload", "signature", "pins", "hostname", "https://", "UNSAFE_RAW_ERROR");
        }
        var foreign = get("/v1/webhooks/deliveries/" + deliveryB, headers);
        var missing = get("/v1/webhooks/deliveries/" + UUID.randomUUID(), headers);
        assertThat(foreign.getStatusCode().value()).isEqualTo(404);
        assertThat(dataError(foreign)).isEqualTo(dataError(missing)).isEqualTo("Webhook delivery not found");
        headers.set("X-Merchant-Id", merchantB.getId().toString());
        assertThat(get("/v1/webhooks/deliveries", headers).getStatusCode().value()).isEqualTo(403);
        assertThat(get("/v1/webhooks/endpoint", headers).getStatusCode().value()).isEqualTo(403);
    }

    @Test void pagingFiltersAndFixedOrderingAreBounded() throws Exception {
        UUID newer = delivery(merchantA, customerA, endpointA, Instant.parse("2026-10-21T05:00:00Z"));
        var headers = headers();
        var first = data(get("/v1/webhooks/deliveries?size=1", headers));
        assertThat(first.path("totalElements").asInt()).isEqualTo(2);
        assertThat(first.path("content").get(0).path("id").asText()).isEqualTo(newer.toString());
        var second = data(get("/v1/webhooks/deliveries?size=1&page=1", headers));
        assertThat(second.path("content").get(0).path("id").asText()).isEqualTo(deliveryA.toString());
        assertThat(data(get("/v1/webhooks/deliveries?paymentId=" + paymentA, headers)).path("totalElements").asInt()).isEqualTo(1);
        assertThat(data(get("/v1/webhooks/deliveries?state=DELIVERED", headers)).path("totalElements").asInt()).isZero();
        assertThat(data(get("/v1/webhooks/deliveries?paymentId=" + UUID.randomUUID(), headers)).path("totalElements").asInt()).isZero();
        for (String query : List.of("size=101", "size=0", "page=-1", "state=invalid", "paymentId=invalid")) {
            assertThat(get("/v1/webhooks/deliveries?" + query, headers).getStatusCode().value()).isEqualTo(400);
        }
        assertThat(get("/v1/webhooks/deliveries?size=100", headers).getStatusCode().value()).isEqualTo(200);
    }

    @Test void currentMembershipAndIdentityRevocationApplyToPreviouslyAuthenticatedSessions() throws Exception {
        var headers = headers();
        jdbc.update("DELETE FROM demo_merchant_memberships WHERE user_id=? AND merchant_id=?", accountA.id(), merchantA.getId());
        assertThat(get("/v1/webhooks/deliveries", headers).getStatusCode().value()).isEqualTo(403);
        jdbc.update("INSERT INTO demo_merchant_memberships(user_id,merchant_id) VALUES (?,?)", accountA.id(), merchantA.getId());
        assertThat(get("/v1/webhooks/deliveries/" + deliveryA, headers).getStatusCode().value()).isEqualTo(200);
        jdbc.update("UPDATE merchants SET status='SUSPENDED' WHERE id=?", merchantA.getId());
        assertThat(get("/v1/webhooks/endpoint", headers).getStatusCode().value()).isEqualTo(403);
        jdbc.update("UPDATE merchants SET status='ACTIVE' WHERE id=?", merchantA.getId());
        jdbc.update("UPDATE demo_users SET enabled=false WHERE id=?", accountA.id());
        assertThat(get("/v1/webhooks/deliveries", headers).getStatusCode().value()).isEqualTo(401);
    }

    @Test void retiredEndpointHasNoActiveEnrollmentAndDoesNotHideItsRetainedDelivery() throws Exception {
        jdbc.update("UPDATE merchant_webhook_endpoints SET state='RETIRED',retired_at=NOW() WHERE id=?", endpointA);
        var headers = headers();
        assertThat(data(get("/v1/webhooks/endpoint", headers)).path("enabled").asBoolean()).isFalse();
        assertThat(data(get("/v1/webhooks/deliveries", headers)).path("totalElements").asInt()).isEqualTo(1);
    }

    private Customer customer(Merchant merchant) {
        return customers.save(Customer.builder().merchant(merchant).email(UUID.randomUUID() + "@example.invalid").fullName("Synthetic webhook view").build());
    }
    private UUID endpoint(Merchant merchant) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO merchant_webhook_endpoints(id,merchant_id,url,hostname,path,pins,key_id,secret_ref,state,created_by,reason_code)
                VALUES (?,?,'https://receiver.invalid/hook','receiver.invalid','/hook',ARRAY['93.184.216.34'],?,?,'ACTIVE','test','TEST')
                """, id, merchant.getId(), UUID.randomUUID(), PRIVATE + "_" + id);
        return id;
    }
    private UUID delivery(Merchant merchant, Customer customer, UUID endpoint, Instant createdAt) {
        Payment payment = payments.save(Payment.builder().merchant(merchant).customer(customer).amountCents(12345L)
                .currency("MYR").idempotencyKey(UUID.randomUUID().toString()).build());
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO merchant_webhook_deliveries(id,event_id,merchant_id,endpoint_id,notification_key,event_type,event_version,
                    payment_id,occurred_at,created_at,payload,next_attempt_at,expires_at)
                VALUES (?,?,?,?,?,'payment.captured',1,?,?,?, ?,?,?)
                """, id, UUID.randomUUID(), merchant.getId(), endpoint, "capture:" + payment.getId(), payment.getId(),
                java.sql.Timestamp.from(createdAt), java.sql.Timestamp.from(createdAt), PRIVATE.getBytes(StandardCharsets.UTF_8),
                java.sql.Timestamp.from(createdAt), java.sql.Timestamp.from(createdAt.plusSeconds(86400)));
        return id;
    }
    private HttpHeaders headers() {
        var headers = security().login(accountA).headers(); headers.set("X-Merchant-Id", merchantA.getId().toString()); return headers;
    }
    private ResponseEntity<String> get(String path, HttpHeaders headers) {
        return http.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }
    private JsonNode data(ResponseEntity<String> response) throws Exception { return mapper.readTree(response.getBody()).path("data"); }
    private String dataError(ResponseEntity<String> response) throws Exception { return mapper.readTree(response.getBody()).path("message").asText(); }
}
