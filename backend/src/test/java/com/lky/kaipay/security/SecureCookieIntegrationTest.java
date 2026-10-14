package com.lky.kaipay.security;

import com.lky.kaipay.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/** Actual default configuration, with no local HTTP profile or cookie overrides. */
@ActiveProfiles(value = "test", inheritProfiles = false)
class SecureCookieIntegrationTest extends AbstractPostgresIntegrationTest {
    @Autowired private TestRestTemplate http;
    @Test void defaultDeploymentSessionCookieIsSecureHttpOnlyAndSameSiteStrict() {
        var response = http.getForEntity("/v1/auth/csrf", String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getFirst(HttpHeaders.SET_COOKIE))
                .startsWith("JSESSIONID=").contains("Secure", "HttpOnly", "SameSite=Strict");
        assertThat(response.getBody()).contains("X-CSRF-TOKEN").doesNotContain("JSESSIONID");
    }
}
