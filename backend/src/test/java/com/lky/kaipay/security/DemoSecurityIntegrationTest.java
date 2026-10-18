package com.lky.kaipay.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lky.kaipay.AbstractPostgresIntegrationTest;
import com.lky.kaipay.customer.domain.Customer;
import com.lky.kaipay.customer.repository.CustomerRepository;
import com.lky.kaipay.merchant.domain.Merchant;
import com.lky.kaipay.merchant.repository.MerchantRepository;
import com.lky.kaipay.payment.domain.Payment;
import com.lky.kaipay.payment.domain.PaymentStatus;
import com.lky.kaipay.payment.repository.PaymentRepository;
import com.lky.kaipay.payment.service.PaymentService;
import com.lky.kaipay.payment.service.AuthorizationDelivery;
import com.lky.kaipay.payment.service.AuthorizationRecoveryService;
import com.lky.kaipay.payment.service.acquirer.MockBankAcquirerClient;
import org.apache.catalina.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContext;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PostgreSQL-backed identities and actual HTTP session/CSRF exchanges, including all existing routes. */
class DemoSecurityIntegrationTest extends AbstractPostgresIntegrationTest {
    @Autowired private TestRestTemplate http;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private MerchantRepository merchants;
    @Autowired private CustomerRepository customers;
    @Autowired private PaymentRepository payments;
    @Autowired private PaymentService paymentService;
    @Autowired private AuthorizationRecoveryService recovery;
    @Autowired private MockBankAcquirerClient simulator;
    @Autowired private MerchantApplicationService merchantApplication;
    @Autowired private ServletWebServerApplicationContext application;
    private Merchant merchantA;
    private Merchant merchantB;
    private Customer customerA;
    private Customer customerB;
    private SecurityHttpFixtures.Account accountA;
    private SecurityHttpFixtures.Account accountB;

    @BeforeEach void provision() {
        merchantA = merchants.save(Merchant.builder().name("Security A").apiKeyHash(UUID.randomUUID().toString()).build());
        merchantB = merchants.save(Merchant.builder().name("Security B").apiKeyHash(UUID.randomUUID().toString()).build());
        customerA = customers.save(Customer.builder().merchant(merchantA).email(UUID.randomUUID()+"@example.com").fullName("Synthetic A").build());
        customerB = customers.save(Customer.builder().merchant(merchantB).email(UUID.randomUUID()+"@example.com").fullName("Synthetic B").build());
        accountA = security().provision("MERCHANT", true, merchantA.getId());
        accountB = security().provision("MERCHANT", true, merchantB.getId());
    }
    @AfterEach void cleanup() {
        for (Merchant merchant : List.of(merchantA, merchantB)) {
            UUID id = merchant.getId();
            jdbc.update("DELETE FROM ledger_entries WHERE journal_id IN (SELECT id FROM journals WHERE merchant_id=?)", id);
            jdbc.update("DELETE FROM journals WHERE merchant_id=?", id);
            jdbc.update("DELETE FROM accounts WHERE merchant_id=?", id);
            jdbc.update("DELETE FROM consumed_events WHERE payment_id IN (SELECT id FROM payments WHERE merchant_id=?)", id);
            jdbc.update("DELETE FROM dead_letter_events WHERE payment_id IN (SELECT id FROM payments WHERE merchant_id=?)", id);
            jdbc.update("DELETE FROM payment_events_outbox WHERE aggregate_id IN (SELECT id::text FROM payments WHERE merchant_id=?) OR aggregate_id IN (SELECT id::text FROM refunds WHERE merchant_id=?)", id, id);
            jdbc.update("DELETE FROM refunds WHERE merchant_id=?", id);
            jdbc.update("DELETE FROM simulated_authorization_operations WHERE merchant_id=?", id);
            jdbc.update("DELETE FROM payments WHERE merchant_id=?", id);
            jdbc.update("DELETE FROM idempotency_records WHERE merchant_id=?", id);
            jdbc.update("DELETE FROM payment_methods WHERE customer_id IN (SELECT id FROM customers WHERE merchant_id=?)", id);
            jdbc.update("DELETE FROM customers WHERE merchant_id=?", id);
            jdbc.update("DELETE FROM merchants WHERE id=?", id);
        }
    }
    @ParameterizedTest
    @ValueSource(strings = {"GET /v1/payments", "GET /v1/payments/00000000-0000-0000-0000-000000000001",
            "POST /v1/payments", "POST /v1/payments/00000000-0000-0000-0000-000000000001/capture",
            "GET /v1/payments/00000000-0000-0000-0000-000000000001/refunds", "POST /v1/payments/00000000-0000-0000-0000-000000000001/refunds",
            "GET /v1/merchants/balance", "GET /v1/ledger/balance", "GET /v1/ledger/journals",
            "GET /v1/ledger/journals/00000000-0000-0000-0000-000000000001", "GET /v1/events/dlt", "GET /v1/events/outbox"})
    void everyExistingRouteRejectsAnonymousIncludingForgedTenant(String route) {
        String[] parts = route.split(" ", 2);
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Merchant-Id", merchantA.getId().toString());
        assertThat(request(parts[1], HttpMethod.valueOf(parts[0]), null, headers).getStatusCode().value()).isEqualTo(401);
        assertThat(tenantPayments()).isZero();
    }
    @Test void provisionedLoginFixationCookieAndPrincipalDoNotLeakCredentials() throws Exception {
        var browser = new SecurityHttpFixtures.Browser();
        security().refresh(browser);
        String anonymousCookie = browser.cookie;
        var login = security().loginResponse(accountA.username(), accountA.password(), browser);
        assertThat(login.getStatusCode().value()).isEqualTo(204);
        assertThat(browser.cookie).isNotEqualTo(anonymousCookie);
        assertThat(login.getHeaders().getFirst(HttpHeaders.SET_COOKIE)).contains("HttpOnly", "SameSite=Strict");
        security().refresh(browser);
        var me = request("/v1/auth/me", HttpMethod.GET, null, browser.headers());
        assertThat(me.getStatusCode().value()).isEqualTo(200);
        JsonNode identity = data(me);
        assertThat(identity.path("id").asText()).isEqualTo(accountA.id().toString());
        assertThat(identity.path("merchants").get(0).path("id").asText()).isEqualTo(merchantA.getId().toString());
        assertThat(me.getBody()).doesNotContain(accountA.password(), "password", "credentialVersion", "passwordHash");
        var stored = (SecurityContext) session(browser).getSession().getAttribute("SPRING_SECURITY_CONTEXT");
        assertThat(((DemoPrincipal) stored.getAuthentication().getPrincipal()).password()).isNull();
        assertThat(stored.getAuthentication().getCredentials()).isNull();
        assertThat(jdbc.queryForObject("SELECT password_hash FROM demo_users WHERE id=?", String.class, accountA.id()))
                .startsWith("$2a$").isNotEqualTo(accountA.password());
        var obsolete = new HttpHeaders(); obsolete.set(HttpHeaders.COOKIE, anonymousCookie);
        assertThat(request("/v1/auth/me", HttpMethod.GET, null, obsolete).getStatusCode().value()).isEqualTo(401);
    }
    @Test void invalidUnknownAndDisabledAccountsHaveSameSanitizedFailure() {
        var disabled = security().provision("MERCHANT", false, merchantA.getId());
        for (var account : List.of(new SecurityHttpFixtures.Account(accountA.id(), accountA.username(), "incorrect-password"),
                new SecurityHttpFixtures.Account(UUID.randomUUID(), "unknown-user", "incorrect-password"), disabled)) {
            var browser = new SecurityHttpFixtures.Browser(); security().refresh(browser);
            var response = security().loginResponse(account.username(), account.password(), browser);
            assertThat(response.getStatusCode().value()).isEqualTo(401);
            assertThat(response.getBody()).contains("Authentication required or invalid credentials")
                    .doesNotContain(account.username(), account.password(), "disabled", "not found");
            assertThat(request("/v1/auth/me", HttpMethod.GET, null, browser.headers()).getStatusCode().value()).isEqualTo(401);
        }
    }
    @ParameterizedTest
    @ValueSource(strings = {"ascii", "utf8"})
    void bcryptRejectsLongPasswordSuffixInsteadOfAuthenticatingItsTruncatedPrefix(String format) {
        // The library retains legacy truncating verification. KaiPay's login boundary
        // must enforce provisioning's byte limit, including multi-byte characters.
        String password = format.equals("ascii")
                ? (UUID.randomUUID().toString() + UUID.randomUUID() + UUID.randomUUID()).substring(0, 72)
                : "é".repeat(36);
        jdbc.update("UPDATE demo_users SET password_hash=?,credential_version=credential_version+1 WHERE id=?",
                new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4).encode(password), accountA.id());
        var account = new SecurityHttpFixtures.Account(accountA.id(), accountA.username(), password);
        var browser = security().login(account);
        assertThat(request("/v1/auth/me", HttpMethod.GET, null, browser.headers()).getStatusCode().value()).isEqualTo(200);
        for (String candidate : List.of(password + (format.equals("ascii") ? "-extra-suffix" : "é"), "x".repeat(300))) {
            browser = new SecurityHttpFixtures.Browser(); security().refresh(browser);
            var response = security().loginResponse(account.username(), candidate, browser);
            assertThat(response.getStatusCode().value()).isEqualTo(401);
            assertThat(response.getBody()).doesNotContain(password, candidate);
            assertThat(request("/v1/auth/me", HttpMethod.GET, null, browser.headers()).getStatusCode().value()).isEqualTo(401);
        }
    }
    @Test void failedIdentitySwitchInvalidatesEarlierPrincipal() {
        var browser = security().login(accountA);
        assertThat(security().loginResponse(accountB.username(), "wrong", browser).getStatusCode().value()).isEqualTo(401);
        assertThat(request("/v1/auth/me", HttpMethod.GET, null, browser.headers()).getStatusCode().value()).isEqualTo(401);
    }
    @Test void logoutInvalidatesSessionAndRequiresCsrf() {
        var browser = security().login(accountA);
        HttpHeaders noToken = browser.headers(); noToken.remove(browser.headerName);
        assertThat(request("/v1/auth/logout", HttpMethod.POST, null, noToken).getStatusCode().value()).isEqualTo(403);
        assertThat(request("/v1/auth/me", HttpMethod.GET, null, browser.headers()).getStatusCode().value()).isEqualTo(200);
        assertThat(request("/v1/auth/logout", HttpMethod.POST, null, browser.headers()).getStatusCode().value()).isEqualTo(204);
        assertThat(request("/v1/auth/me", HttpMethod.GET, null, browser.headers()).getStatusCode().value()).isEqualTo(401);
        assertThat(request("/v1/payments", HttpMethod.POST, paymentBody(customerA), merchantHeaders(browser, merchantA))
                .getStatusCode().value()).isEqualTo(401);
    }
    @Test void actualIdleExpiryRejectsSessionAndOldCsrfWithoutWrites() throws Exception {
        var browser = security().login(accountA);
        // Change only this real container session, without any runtime test-only security bean.
        session(browser).setMaxInactiveInterval(1);
        Thread.sleep(1200);
        assertThat(request("/v1/auth/me", HttpMethod.GET, null, browser.headers()).getStatusCode().value()).isEqualTo(401);
        assertThat(request("/v1/payments", HttpMethod.POST, paymentBody(customerA), merchantHeaders(browser, merchantA))
                .getStatusCode().value()).isEqualTo(401);
        assertThat(tenantPayments()).isZero();
    }
    @Test void missingInvalidAndOldPreLoginCsrfRejectWritesAndCurrentTokenWorks() {
        var browser = new SecurityHttpFixtures.Browser(); security().refresh(browser);
        String oldToken = browser.token;
        assertThat(security().loginResponse(accountA.username(), accountA.password(), browser).getStatusCode().value()).isEqualTo(204);
        var oldHeaders = merchantHeaders(browser, merchantA); oldHeaders.set(browser.headerName, oldToken);
        assertThat(request("/v1/payments", HttpMethod.POST, paymentBody(customerA), oldHeaders).getStatusCode().value()).isEqualTo(403);
        security().refresh(browser);
        for (String token : List.of("", "invalid")) {
            var headers = merchantHeaders(browser, merchantA);
            if (token.isEmpty()) headers.remove(browser.headerName); else headers.set(browser.headerName, token);
            assertThat(request("/v1/payments", HttpMethod.POST, paymentBody(customerA), headers).getStatusCode().value()).isEqualTo(403);
        }
        assertThat(tenantPayments()).isZero();
        assertThat(request("/v1/payments", HttpMethod.POST, paymentBody(customerA), merchantHeaders(browser, merchantA))
                .getStatusCode().value()).isEqualTo(201);
    }
    @Test void loginAlsoRequiresCsrfAndUnknownRoutesStayDenied() {
        var browser = new SecurityHttpFixtures.Browser();
        assertThat(security().loginResponse(accountA.username(), accountA.password(), browser).getStatusCode().value()).isEqualTo(401);
        for (String path : List.of("/login", "/v1/auth/login", "/actuator/health", "/error", "/v1/unknown")) {
            assertThat(request(path, HttpMethod.GET, null, new HttpHeaders()).getStatusCode().value()).isEqualTo(401);
        }
        browser = security().login(accountA);
        assertThat(request("/v1/unknown", HttpMethod.GET, null, browser.headers()).getStatusCode().value()).isEqualTo(403);
        assertThat(request("/v1/payments", HttpMethod.DELETE, null, merchantHeaders(browser, merchantA)).getStatusCode().value()).isEqualTo(403);
    }
    @Test void forgedMerchantContextAndForeignCustomerMethodResourceIdsAreDenied() {
        var browser = security().login(accountA);
        for (String path : List.of("/v1/payments?status=CREATED", "/v1/ledger/balance", "/v1/merchants/balance", "/v1/ledger/journals")) {
            assertThat(request(path, HttpMethod.GET, null, merchantHeaders(browser, merchantB)).getStatusCode().value()).isEqualTo(403);
        }
        assertThat(request("/v1/payments", HttpMethod.POST, paymentBody(customerB), merchantHeaders(browser, merchantA)).getStatusCode().value()).isEqualTo(404);
        UUID foreignMethod = UUID.randomUUID();
        jdbc.update("INSERT INTO payment_methods(id,customer_id,type,token,masked_number,expiry_month,expiry_year) VALUES (?,?,'CARD',?,'4242',12,2030)",
                foreignMethod, customerB.getId(), UUID.randomUUID().toString());
        var body = new java.util.HashMap<>(paymentBody(customerA)); body.put("paymentMethodId", foreignMethod);
        assertThat(request("/v1/payments", HttpMethod.POST, body, merchantHeaders(browser, merchantA)).getStatusCode().value()).isEqualTo(404);
        assertThat(tenantPayments()).isZero();
    }
    @Test void guessedForeignPaymentAndRefundIdsDoNotReachFinancialWork() {
        Payment foreign = payments.save(Payment.builder().merchant(merchantB).customer(customerB)
                .amountCents(10000L).currency("USD").status(PaymentStatus.CAPTURED).idempotencyKey(UUID.randomUUID().toString()).build());
        var browser = security().login(accountA);
        var headers = merchantHeaders(browser, merchantA);
        for (String path : List.of("/v1/payments/" + foreign.getId(), "/v1/payments/" + foreign.getId() + "/refunds")) {
            assertThat(request(path, HttpMethod.GET, null, headers).getStatusCode().value()).isEqualTo(404);
        }
        assertThat(request("/v1/payments/" + foreign.getId() + "/capture", HttpMethod.POST, null, headers).getStatusCode().value()).isEqualTo(404);
        assertThat(request("/v1/payments/" + foreign.getId() + "/refunds", HttpMethod.POST, Map.of("amountCents", 100L), headers).getStatusCode().value()).isEqualTo(404);
        assertThat(payments.findById(foreign.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refunds WHERE payment_id=?", Long.class, foreign.getId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM journals WHERE merchant_id=?", Long.class, merchantB.getId())).isZero();
    }
    @Test void merchantCannotUseOperationalEndpointsAndAdminCannotImpersonate() {
        var merchant = security().login(accountA);
        var admin = security().login(security().provision("ADMIN", true));
        for (String path : List.of("/v1/events/dlt", "/v1/events/dlt?paymentId=" + UUID.randomUUID(), "/v1/events/outbox")) {
            assertThat(request(path, HttpMethod.GET, null, merchantHeaders(merchant, merchantA)).getStatusCode().value()).isEqualTo(403);
            assertThat(request(path, HttpMethod.GET, null, admin.headers()).getStatusCode().value()).isEqualTo(200);
        }
        for (String path : List.of("/v1/payments", "/v1/ledger/balance", "/v1/merchants/balance")) {
            assertThat(request(path, HttpMethod.GET, null, merchantHeaders(admin, merchantA)).getStatusCode().value()).isEqualTo(403);
        }
        assertThat(request("/v1/payments", HttpMethod.POST, paymentBody(customerA), merchantHeaders(admin, merchantA)).getStatusCode().value()).isEqualTo(403);
        assertThat(request("/v1/ledger/journals", HttpMethod.GET, null, admin.headers()).getStatusCode().value()).isEqualTo(200);
        assertThat(request("/v1/ledger/journals", HttpMethod.GET, null, merchantHeaders(admin, merchantA)).getStatusCode().value()).isEqualTo(403);
        assertThat(request("/v1/ledger/journals", HttpMethod.GET, null, merchant.headers()).getStatusCode().value()).isEqualTo(403);
    }
    @Test void membershipRevocationInactiveMerchantDisableAndRotationTakeImmediateEffect() {
        for (String change : List.of("membership", "inactiveMerchant", "disabled", "rotated", "roleChanged", "deleted")) {
            jdbc.update("UPDATE merchants SET status='ACTIVE' WHERE id=?", merchantA.getId());
            var account = security().provision("MERCHANT", true, merchantA.getId());
            var browser = security().login(account);
            switch (change) {
                case "membership" -> jdbc.update("DELETE FROM demo_merchant_memberships WHERE user_id=?", account.id());
                case "inactiveMerchant" -> jdbc.update("UPDATE merchants SET status='SUSPENDED' WHERE id=?", merchantA.getId());
                case "disabled" -> jdbc.update("UPDATE demo_users SET enabled=false WHERE id=?", account.id());
                case "rotated" -> jdbc.update("UPDATE demo_users SET credential_version=credential_version+1 WHERE id=?", account.id());
                case "roleChanged" -> jdbc.update("UPDATE demo_users SET role='ADMIN' WHERE id=?", account.id());
                case "deleted" -> jdbc.update("DELETE FROM demo_users WHERE id=?", account.id());
            }
            int expected = change.equals("membership") || change.equals("inactiveMerchant") ? 403 : 401;
            assertThat(request("/v1/payments", HttpMethod.GET, null, merchantHeaders(browser, merchantA)).getStatusCode().value()).isEqualTo(expected);
        }
    }
    @Test void directHttpApplicationBoundaryRequiresPrincipalBeforeCachedFinancialWork() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        assertThatThrownBy(() -> merchantApplication.getPayment(merchantA.getId(), UUID.randomUUID()))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThatThrownBy(() -> merchantApplication.getMerchantBalance(merchantA.getId()))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThat(tenantPayments()).isZero();
    }
    @Test void separateBrowserSessionsAndIdentityReplacementNeverCarryEarlierMembership() throws Exception {
        var browserA = security().login(accountA);
        var browserB = security().login(accountB);
        assertThat(browserA.cookie).isNotEqualTo(browserB.cookie);
        assertThat(request("/v1/payments", HttpMethod.GET, null, merchantHeaders(browserB, merchantA)).getStatusCode().value()).isEqualTo(403);
        assertThat(data(request("/v1/auth/me", HttpMethod.GET, null, browserA.headers())).path("id").asText()).isEqualTo(accountA.id().toString());
        String previousToken = browserA.token;
        security().login(accountB, browserA);
        assertThat(data(request("/v1/auth/me", HttpMethod.GET, null, browserA.headers())).path("id").asText()).isEqualTo(accountB.id().toString());
        assertThat(request("/v1/payments", HttpMethod.GET, null, merchantHeaders(browserA, merchantA)).getStatusCode().value()).isEqualTo(403);
        var headers = merchantHeaders(browserA, merchantB); headers.set(browserA.headerName, previousToken);
        assertThat(request("/v1/payments", HttpMethod.POST, paymentBody(customerB), headers).getStatusCode().value()).isEqualTo(403);
    }
    @Test void assignedMultipleMembershipsAreServerProvidedAndUnassignedContextDenied() throws Exception {
        var browser = security().login(security().provision("MERCHANT", true, merchantA.getId(), merchantB.getId()));
        assertThat(data(request("/v1/auth/me", HttpMethod.GET, null, browser.headers())).path("merchants").size()).isEqualTo(2);
        for (Merchant merchant : List.of(merchantA, merchantB)) {
            assertThat(request("/v1/payments", HttpMethod.GET, null, merchantHeaders(browser, merchant)).getStatusCode().value()).isEqualTo(200);
        }
        var headers = browser.headers(); headers.set("X-Merchant-Id", UUID.randomUUID().toString());
        assertThat(request("/v1/payments", HttpMethod.GET, null, headers).getStatusCode().value()).isEqualTo(403);
    }
    @Test void authenticatedCreateCaptureRefundAndIdempotentReplayKeepExistingEffects() throws Exception {
        var browser = security().login(accountA);
        var headers = merchantHeaders(browser, merchantA);
        var created = request("/v1/payments", HttpMethod.POST, paymentBody(customerA), headers);
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        UUID id = UUID.fromString(data(created).path("id").asText());
        assertThat(data(request("/v1/payments", HttpMethod.POST, paymentBody(customerA), headers)).path("id").asText()).isEqualTo(id.toString());
        var payment = payments.findById(id).orElseThrow();
        var delivery = AuthorizationDelivery.fromJson(mapper.valueToTree(Map.of("paymentId", id,
                "amountCents", payment.getAmountCents(), "currency", payment.getCurrency())));
        var authorization = recovery.prepareAuthorization(delivery, "security-test-authorization").orElseThrow();
        recovery.completeAuthorization(delivery, "security-test-authorization", simulator.authorize(authorization));
        assertThat(request("/v1/payments/" + id + "/capture", HttpMethod.POST, null, headers).getStatusCode().value()).isEqualTo(200);
        var refund = request("/v1/payments/" + id + "/refunds", HttpMethod.POST, Map.of("amountCents", 1000L), headers);
        assertThat(refund.getStatusCode().value()).isEqualTo(201);
        assertThat(data(request("/v1/payments/" + id + "/refunds", HttpMethod.POST, Map.of("amountCents", 1000L), headers)).path("id").asText())
                .isEqualTo(data(refund).path("id").asText());
        assertThat(payments.findById(id).orElseThrow().getStatus()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refunds WHERE payment_id=?", Long.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM journals WHERE merchant_id=?", Long.class, merchantA.getId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_events_outbox WHERE aggregate_id=? OR aggregate_id=?", Long.class,
                id.toString(), data(refund).path("id").asText())).isEqualTo(3);
        assertThat(request("/v1/ledger/journals", HttpMethod.GET, null, headers).getStatusCode().value()).isEqualTo(200);
        var foreign = security().login(accountB);
        headers.set(HttpHeaders.COOKIE, foreign.cookie); headers.set(foreign.headerName, foreign.token);
        assertThat(request("/v1/payments", HttpMethod.POST, paymentBody(customerA), headers).getStatusCode().value()).isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments WHERE merchant_id=?", Long.class, merchantA.getId())).isEqualTo(1);
    }
    @Test void journalsRejectNullForeignAndForeignAccountLinesButPermitOwnBalancingLines() throws Exception {
        UUID own = insertJournal(merchantA.getId());
        UUID foreign = insertJournal(merchantB.getId());
        UUID global = insertJournal(null);
        var merchant = security().login(accountA);
        var admin = security().login(security().provision("ADMIN", true));
        try {
            for (UUID journal : List.of(foreign, global)) {
                assertThat(request("/v1/ledger/journals/" + journal, HttpMethod.GET, null, merchantHeaders(merchant, merchantA)).getStatusCode().value()).isEqualTo(404);
                assertThat(request("/v1/ledger/journals/" + journal, HttpMethod.GET, null, admin.headers()).getStatusCode().value()).isEqualTo(200);
            }
            UUID systemAccount = UUID.randomUUID();
            UUID foreignAccount = UUID.randomUUID();
            jdbc.update("INSERT INTO accounts(id,merchant_id,account_number,account_name,account_type) VALUES (?,NULL,?,'Synthetic system','ASSET')", systemAccount, systemAccount.toString());
            jdbc.update("INSERT INTO accounts(id,merchant_id,account_number,account_name,account_type) VALUES (?, ?,?,'Foreign merchant','LIABILITY')", foreignAccount, merchantB.getId(), foreignAccount.toString());
            try {
                UUID entry = UUID.randomUUID();
                jdbc.update("INSERT INTO ledger_entries(id,journal_id,account_id,entry_type,amount_cents) VALUES (?,?,?,'DEBIT',100)", entry, own, systemAccount);
                assertThat(request("/v1/ledger/journals/" + own, HttpMethod.GET, null, merchantHeaders(merchant, merchantA)).getStatusCode().value()).isEqualTo(200);
                jdbc.update("UPDATE ledger_entries SET account_id=? WHERE id=?", foreignAccount, entry);
                assertThat(request("/v1/ledger/journals/" + own, HttpMethod.GET, null, merchantHeaders(merchant, merchantA)).getStatusCode().value()).isEqualTo(404);
                assertThat(request("/v1/ledger/journals", HttpMethod.GET, null, merchantHeaders(merchant, merchantA)).getStatusCode().value()).isEqualTo(404);
                jdbc.update("DELETE FROM ledger_entries WHERE id=?", entry);
            } finally { jdbc.update("DELETE FROM accounts WHERE id IN (?,?)", systemAccount, foreignAccount); }
        } finally { jdbc.update("DELETE FROM journals WHERE id=?", global); }
    }
    @Test void corsAllowsOnlyConfiguredOriginAndSecurityHeadersPreventCachedIdentity() {
        var headers = new HttpHeaders();
        headers.setOrigin("http://localhost:28081");
        headers.set("Access-Control-Request-Method", "POST");
        headers.set("Access-Control-Request-Headers", "Content-Type,X-CSRF-TOKEN,X-Merchant-Id");
        var allowed = request("/v1/payments", HttpMethod.OPTIONS, null, headers);
        assertThat(allowed.getStatusCode().value()).isEqualTo(200);
        assertThat(allowed.getHeaders().getAccessControlAllowOrigin()).isEqualTo("http://localhost:28081");
        assertThat(allowed.getHeaders().getAccessControlAllowCredentials()).isTrue();
        headers.setOrigin("https://untrusted.example");
        var denied = request("/v1/payments", HttpMethod.OPTIONS, null, headers);
        assertThat(denied.getStatusCode().value()).isEqualTo(403);
        assertThat(denied.getHeaders().getAccessControlAllowOrigin()).isNull();
        var browser = security().login(accountA);
        var me = request("/v1/auth/me", HttpMethod.GET, null, browser.headers());
        assertThat(me.getHeaders().getCacheControl()).contains("no-store");
        assertThat(me.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(me.getHeaders().getFirst("X-Frame-Options")).isEqualTo("DENY");
    }
    private UUID insertJournal(UUID merchant) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO journals(id,journal_number,source_type,source_id,merchant_id,description) VALUES (?,?,'PAYMENT_CAPTURE',?,?, 'Synthetic security fixture')",
                id, id.toString(), id.toString(), merchant);
        return id;
    }
    private Session session(SecurityHttpFixtures.Browser browser) throws Exception {
        String id = browser.cookie.substring("JSESSIONID=".length());
        var server = (TomcatWebServer) application.getWebServer();
        for (var child : server.getTomcat().getHost().findChildren()) {
            if (child instanceof org.apache.catalina.Context context) {
                var session = context.getManager().findSession(id);
                if (session != null) return session;
            }
        }
        throw new AssertionError("Expected actual Tomcat session");
    }
    private long tenantPayments() {
        return jdbc.queryForObject("SELECT count(*) FROM payments WHERE merchant_id IN (?,?)", Long.class, merchantA.getId(), merchantB.getId());
    }
    private Map<String, Object> paymentBody(Customer customer) {
        return Map.of("amountCents", 10000L, "currency", "USD", "customerId", customer.getId());
    }
    private HttpHeaders merchantHeaders(SecurityHttpFixtures.Browser browser, Merchant merchant) {
        var headers = browser.headers();
        headers.set("X-Merchant-Id", merchant.getId().toString());
        headers.set("Idempotency-Key", "security-fixture-request");
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
    private ResponseEntity<String> request(String path, HttpMethod method, Object body, HttpHeaders headers) {
        return http.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }
    private JsonNode data(ResponseEntity<String> response) throws Exception { return mapper.readTree(response.getBody()).path("data"); }
}
