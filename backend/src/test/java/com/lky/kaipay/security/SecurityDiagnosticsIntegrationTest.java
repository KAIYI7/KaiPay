package com.lky.kaipay.security;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.lky.kaipay.AbstractPostgresIntegrationTest;
import com.lky.kaipay.merchant.domain.Merchant;
import com.lky.kaipay.merchant.repository.MerchantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.LinkedMultiValueMap;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** Real HTTP/security handlers with persisted identities, session revocation and captured log MDC. */
class SecurityDiagnosticsIntegrationTest extends AbstractPostgresIntegrationTest {
    @Autowired private TestRestTemplate http;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MerchantRepository merchants;
    private final Logger logger = (Logger) LoggerFactory.getLogger(SecurityDiagnostics.class);
    private final List<Entry> entries = new CopyOnWriteArrayList<>();
    private final AppenderBase<ILoggingEvent> capture = new AppenderBase<>() {
        @Override protected void append(ILoggingEvent event) {
            // Freeze MDC on the emitting HTTP thread rather than lazily reading it after completion.
            entries.add(new Entry(event.getFormattedMessage(), Map.copyOf(event.getMDCPropertyMap()),
                    event.getThrowableProxy() != null));
        }
    };

    @BeforeEach void captureLogs() { capture.start(); logger.addAppender(capture); }
    @AfterEach void stopCapture() { logger.detachAppender(capture); capture.stop(); }

    @Test void failedLoginAndUnauthorizedRequestExcludeAllHostileRequestText() {
        var browser = new SecurityHttpFixtures.Browser();
        security().refresh(browser);
        String cookie = browser.cookie;
        String csrf = browser.token;
        var form = new LinkedMultiValueMap<String, String>();
        form.add("username", "hostile-user\r\nforged-event=success");
        form.add("password", "hostile-password");
        HttpHeaders headers = browser.headers();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.set("X-Client-Secret", "hostile-header");
        headers.set("X-Forwarded-For", "198.51.100.227");
        headers.set("X-Correlation-Id", "security-login-test");
        var failure = http.exchange("/v1/auth/login?hostile-query=hostile-value", HttpMethod.POST,
                new HttpEntity<>(form, headers), String.class);
        assertThat(failure.getStatusCode().value()).isEqualTo(401);
        assertEvent("LOGIN_FAILED", "actorId=-", failure);
        var unauthorized = http.exchange("/v1/hostile-path?hostile-query=hostile-value", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        assertThat(unauthorized.getStatusCode().value()).isEqualTo(401);
        assertEvent("REQUEST_UNAUTHORIZED", "actorId=-", unauthorized);
        assertSafe("hostile-user", "forged-event", "hostile-password", "hostile-header", "198.51.100.227",
                "hostile-path", "hostile-query", "hostile-value", cookie, csrf);
    }

    @Test void successfulLoginAdminDiagnosticsAndLogoutAttributeOnlyTrustedUserId() {
        var admin = security().provision("ADMIN", true);
        var browser = security().login(admin);
        assertThat(entries).anySatisfy(entry -> assertThat(entry.message())
                .contains("event=LOGIN_SUCCEEDED", "operation=login", "outcome=success", "actorId=" + admin.id()));
        String cookie = browser.cookie;
        String csrf = browser.token;
        assertSafe(admin.username(), admin.password(), cookie, csrf);
        for (String route : List.of("/v1/events/dlt", "/v1/events/outbox", "/v1/ledger/journals")) {
            entries.clear();
            var response = request(route, HttpMethod.GET, browser.headers());
            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertEvent("ADMIN_ACCESS_GRANTED", "actorId=" + admin.id(), response);
            assertSafe(admin.username(), admin.password(), cookie, csrf);
        }
        entries.clear();
        var logout = request("/v1/auth/logout", HttpMethod.POST, browser.headers());
        assertThat(logout.getStatusCode().value()).isEqualTo(204);
        assertEvent("LOGOUT_SUCCEEDED", "actorId=" + admin.id(), logout);
        assertSafe(admin.username(), admin.password(), cookie, csrf);
    }

    @Test void crossMerchantDenialIncludesTrustedActorAndTargetAndNormalAccessIsQuiet() {
        UUID own = createMerchant();
        UUID other = createMerchant();
        var account = security().provision("MERCHANT", true, own);
        try {
            var browser = security().login(account);
            entries.clear();
            var headers = browser.headers();
            headers.set("X-Merchant-Id", own.toString());
            assertThat(request("/v1/payments", HttpMethod.GET, headers).getStatusCode().value()).isEqualTo(200);
            assertThat(entries).isEmpty();
            headers.set("X-Merchant-Id", other.toString());
            var denied = request("/v1/payments", HttpMethod.GET, headers);
            assertThat(denied.getStatusCode().value()).isEqualTo(403);
            assertEvent("MERCHANT_ACCESS_DENIED", "actorId=" + account.id(), denied);
            assertThat(entries).anySatisfy(entry -> assertThat(entry.message()).contains("merchantId=" + other));
            assertSafe(account.username(), account.password(), browser.cookie, browser.token);
        } finally {
            jdbc.update("DELETE FROM demo_users WHERE id=?", account.id());
            jdbc.update("DELETE FROM merchants WHERE id IN (?,?)", own, other);
        }
    }

    @Test void securityChainDenialAndRevocationRetainAttributionWithoutSessionMaterial() {
        var account = security().provision("MERCHANT", true);
        var browser = security().login(account);
        entries.clear();
        var denied = request("/v1/events/outbox", HttpMethod.GET, browser.headers());
        assertThat(denied.getStatusCode().value()).isEqualTo(403);
        assertEvent("REQUEST_DENIED", "actorId=" + account.id(), denied);
        entries.clear();
        jdbc.update("UPDATE demo_users SET enabled=false WHERE id=?", account.id());
        var revoked = request("/v1/auth/me", HttpMethod.GET, browser.headers());
        assertThat(revoked.getStatusCode().value()).isEqualTo(401);
        assertEvent("SESSION_REVOKED", "actorId=" + account.id(), revoked);
        assertEvent("REQUEST_UNAUTHORIZED", "actorId=-", revoked);
        assertSafe(account.username(), account.password(), browser.cookie, browser.token);
    }

    private UUID createMerchant() {
        return merchants.save(Merchant.builder().name("Diagnostics fixture")
                .apiKeyHash(UUID.randomUUID().toString()).build()).getId();
    }
    private ResponseEntity<String> request(String route, HttpMethod method, HttpHeaders headers) {
        return http.exchange(route, method, new HttpEntity<>(headers), String.class);
    }
    private void assertEvent(String event, String actor, ResponseEntity<?> response) {
        assertThat(entries).anySatisfy(entry -> {
            assertThat(entry.message()).contains("event=" + event, actor);
            assertThat(entry.mdc().get("traceId")).isNotBlank()
                    .isEqualTo(response.getHeaders().getFirst("X-Correlation-Id"));
            assertThat(entry.throwable()).isFalse();
        });
    }
    private void assertSafe(String... secrets) {
        assertThat(entries).isNotEmpty();
        for (Entry entry : entries) {
            assertThat(entry.message()).doesNotContain(secrets);
            assertThat(entry.throwable()).isFalse();
            assertThat(entry.message()).doesNotContain("\r", "\n");
        }
    }
    private record Entry(String message, Map<String, String> mdc, boolean throwable) {}
}
