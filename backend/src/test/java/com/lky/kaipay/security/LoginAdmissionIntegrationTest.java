package com.lky.kaipay.security;

import com.lky.kaipay.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.test.annotation.DirtiesContext;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "kaipay.security.login.peer-attempts=3", "kaipay.security.login.total-attempts=12"})
@DirtiesContext
class LoginAdmissionIntegrationTest extends AbstractPostgresIntegrationTest {
    @Autowired private TestRestTemplate http;
    @Test void actualCsrfLoginBudgetIsIdentityIndependentAndRejectedSwitchRevokesOldSession() {
        var account = security().provision("ADMIN",true);
        // Invalid CSRF is checked before admission and cannot consume the login budget.
        for (int i=0;i<5;i++) {
            var response = http.postForEntity("/v1/auth/login", null, String.class);
            assertThat(response.getStatusCode().value()).isEqualTo(401);
        }
        for (String username : new String[]{"missing-user",account.username()}) {
            var browser = new SecurityHttpFixtures.Browser(); security().refresh(browser);
            assertThat(security().loginResponse(username,"incorrect",browser).getStatusCode().value()).isEqualTo(401);
        }
        var browser = security().login(account); // Third admitted attempt succeeds, still charges budget.
        var blocked = security().loginResponse("another-user","incorrect",browser);
        assertThat(blocked.getStatusCode().value()).isEqualTo(429);
        assertThat(blocked.getHeaders().getFirst("Retry-After")).matches("[1-6][0-9]?");
        assertThat(blocked.getHeaders().getCacheControl()).contains("no-store");
        assertThat(blocked.getBody()).doesNotContain(account.username(),"another-user","incorrect");
        assertThat(http.exchange("/v1/auth/me",HttpMethod.GET,new HttpEntity<>(browser.headers()),String.class).getStatusCode().value()).isEqualTo(401);
        security().refresh(browser);
        assertThat(security().loginResponse(account.username(),account.password(),browser).getStatusCode().value()).isEqualTo(429);
        // CSRF bootstrap and protected endpoints retain their original behavior while login is blocked.
        assertThat(http.getForEntity("/v1/payments",String.class).getStatusCode().value()).isEqualTo(401);
    }
}
