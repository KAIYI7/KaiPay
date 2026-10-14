package com.lky.kaipay.security;

import com.lky.kaipay.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PasswordNulBoundaryIntegrationTest extends AbstractPostgresIntegrationTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TestRestTemplate http;
    @Test void explicitNulCannotAuthenticateTheSamePrefixAsBcryptImplicitTerminator() {
        var account = security().provision("ADMIN", true);
        String password = (UUID.randomUUID().toString() + UUID.randomUUID() + UUID.randomUUID()).substring(0, 71);
        jdbc.update("UPDATE demo_users SET password_hash=?,credential_version=credential_version+1 WHERE id=?",
                new BCryptPasswordEncoder(4).encode(password), account.id());
        var valid = new SecurityHttpFixtures.Account(account.id(), account.username(), password);
        security().login(valid);
        var browser = new SecurityHttpFixtures.Browser(); security().refresh(browser);
        var rejected = security().loginResponse(account.username(), password + '\0', browser);
        assertThat(rejected.getStatusCode().value()).isEqualTo(401);
        assertThat(http.exchange("/v1/auth/me", HttpMethod.GET, new HttpEntity<>(browser.headers()), String.class)
                .getStatusCode().value()).isEqualTo(401);
        assertThat(rejected.getBody()).doesNotContain(password);
        assertThatThrownBy(() -> new BoundedBcryptPasswordEncoder().encode(password + '\0'))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
