package com.lky.kaipay.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.util.LinkedMultiValueMap;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Explicit persisted accounts and real browser session/CSRF exchanges; no server bypass. */
public final class SecurityHttpFixtures {
    private final TestRestTemplate http;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public SecurityHttpFixtures(TestRestTemplate http, JdbcTemplate jdbc, ObjectMapper mapper) {
        this.http = http; this.jdbc = jdbc; this.mapper = mapper;
    }
    public Account provision(String role, boolean enabled, UUID... merchants) {
        UUID id = UUID.randomUUID();
        String username = "fixture-" + id;
        String password = UUID.randomUUID().toString();
        // Low-cost hashes are synthetic test data only; runtime/operator encoder remains cost 12.
        jdbc.update("INSERT INTO demo_users(id,username,password_hash,role,enabled) VALUES (?,?,?,?,?)",
                id, username, new BCryptPasswordEncoder(4).encode(password), role, enabled);
        for (UUID merchant : merchants) jdbc.update("INSERT INTO demo_merchant_memberships VALUES (?,?)", id, merchant);
        return new Account(id, username, password);
    }
    public Browser login(Account account) { return login(account, new Browser()); }
    public Browser login(Account account, Browser browser) {
        refresh(browser);
        assertThat(loginResponse(account.username(), account.password(), browser).getStatusCode().value()).isEqualTo(204);
        refresh(browser);
        return browser;
    }
    public ResponseEntity<String> loginResponse(String username, String password, Browser browser) {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("username", username); form.add("password", password);
        HttpHeaders headers = browser.headers();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        var response = http.exchange("/v1/auth/login", HttpMethod.POST, new HttpEntity<>(form, headers), String.class);
        browser.acceptCookies(response);
        return response;
    }
    public void refresh(Browser browser) {
        var response = http.exchange("/v1/auth/csrf", HttpMethod.GET, new HttpEntity<>(browser.headers()), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        browser.acceptCookies(response);
        try {
            var data = mapper.readTree(response.getBody()).path("data");
            browser.token = data.path("token").asText();
            browser.headerName = data.path("headerName").asText();
        } catch (Exception exception) { throw new AssertionError(exception); }
    }
    public HttpHeaders merchantHeaders(UUID merchantId) {
        Browser browser = login(provision("MERCHANT", true, merchantId));
        HttpHeaders headers = browser.headers();
        headers.set("X-Merchant-Id", merchantId.toString());
        return headers;
    }
    public HttpHeaders adminHeaders() { return login(provision("ADMIN", true)).headers(); }
    public record Account(UUID id, String username, String password) {}
    public static final class Browser {
        public String cookie;
        public String token;
        public String headerName = "X-CSRF-TOKEN";
        public HttpHeaders headers() {
            HttpHeaders headers = new HttpHeaders();
            if (cookie != null) headers.set(HttpHeaders.COOKIE, cookie);
            if (token != null) headers.set(headerName, token);
            return headers;
        }
        public void acceptCookies(ResponseEntity<?> response) {
            List<String> cookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
            if (cookies != null) for (String value : cookies) if (value.startsWith("JSESSIONID=")) cookie = value.split(";", 2)[0];
        }
    }
}
