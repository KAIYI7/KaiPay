package com.lky.kaipay.security;

import com.lky.kaipay.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/** Actual default configuration, with no local HTTP profile or cookie overrides. */
@ActiveProfiles(value = "test", inheritProfiles = false)
@TestPropertySource(properties = "spring.main.cloud-platform=CLOUD_FOUNDRY")
class SecureCookieIntegrationTest extends AbstractPostgresIntegrationTest {
    @Autowired private TestRestTemplate http;
    @Test void defaultDeploymentSessionCookieIsSecureHttpOnlyAndSameSiteStrict() {
        var response = http.getForEntity("/v1/auth/csrf", String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getFirst(HttpHeaders.SET_COOKIE))
                .startsWith("JSESSIONID=").contains("Secure", "HttpOnly", "SameSite=Strict").doesNotContain("Domain=");
        assertThat(response.getBody()).contains("X-CSRF-TOKEN").doesNotContain("JSESSIONID");
    }
    @Test void forgedForwardedHeadersCannotTurnDirectHttpIntoTrustedHttpsEvenOnCloudPlatform() {
        for (String header : new String[]{"X-Forwarded-Proto", "Forwarded"}) {
            var headers = new HttpHeaders();
            headers.set(header, header.equals("Forwarded")
                    ? "for=203.0.113.1;proto=https;host=forged.example" : "https");
            headers.set("X-Forwarded-For", "203.0.113.1");
            headers.set("X-Forwarded-Host", "forged.example");
            var response = http.exchange("/v1/auth/csrf", HttpMethod.GET, new HttpEntity<>(headers), String.class);
            assertThat(response.getStatusCode().value()).isEqualTo(200);
            // The security HSTS writer only emits this on a request considered secure.
            assertThat(response.getHeaders().getFirst("Strict-Transport-Security")).isNull();
            assertThat(response.getHeaders().getFirst(HttpHeaders.SET_COOKIE))
                    .contains("Secure", "HttpOnly", "SameSite=Strict").doesNotContain("forged.example", "Domain=");
        }
    }
    @Test void oversizedLoginFormWithCorrectCredentialsCannotAuthenticateOrEchoParametersAndNormalLoginRecovers() {
        var account = security().provision("ADMIN", true);
        var browser = new SecurityHttpFixtures.Browser();
        security().refresh(browser);
        var headers = browser.headers();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        String padding = "private-form-marker".repeat(500);
        var response = http.exchange("/v1/auth/login", HttpMethod.POST,
                new HttpEntity<>("username=" + account.username() + "&password=" + account.password() + "&padding=" + padding, headers),
                String.class);
        assertThat(response.getStatusCode().is4xxClientError()).isTrue();
        assertThat(response.getBody()).doesNotContain(account.username(), account.password(), "private-form-marker");
        assertThat(http.exchange("/v1/auth/me", HttpMethod.GET, new HttpEntity<>(browser.headers()), String.class)
                .getStatusCode().value()).isEqualTo(401);
        security().refresh(browser);
        assertThat(security().loginResponse(account.username(), account.password(), browser).getStatusCode().value()).isEqualTo(204);
        assertThat(http.exchange("/v1/auth/me", HttpMethod.GET, new HttpEntity<>(browser.headers()), String.class)
                .getStatusCode().value()).isEqualTo(200);
    }
}
