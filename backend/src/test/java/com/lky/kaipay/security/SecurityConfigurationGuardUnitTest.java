package com.lky.kaipay.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityConfigurationGuardUnitTest {
    @Test void invalidBrowserSessionConfigurationFailsClosed() {
        for (String[] setting : new String[][]{{"http-only", "false"}, {"same-site", "none"}, {"same-site", ""}}) {
            var environment = new MockEnvironment().withProperty("server.servlet.session.cookie." + setting[0], setting[1]);
            assertThatThrownBy(() -> new SecurityConfigurationGuard(environment).afterPropertiesSet()).isInstanceOf(IllegalStateException.class);
        }
        var environment = new MockEnvironment().withProperty("server.servlet.session.tracking-modes", "url,cookie");
        assertThatThrownBy(() -> new SecurityConfigurationGuard(environment).afterPropertiesSet()).isInstanceOf(IllegalStateException.class);
        for (String duration : new String[]{"0", "-5m", "10s"}) {
            var candidate = new MockEnvironment().withProperty("server.servlet.session.timeout", duration);
            assertThatThrownBy(() -> new SecurityConfigurationGuard(candidate).afterPropertiesSet()).isInstanceOf(IllegalStateException.class);
        }
    }
    @Test void secureDefaultsAllowSameOriginAndExactHttpsOnly() {
        var environment = new MockEnvironment();
        assertThatCode(() -> new SecurityConfigurationGuard(environment).afterPropertiesSet()).doesNotThrowAnyException();
        environment.setProperty("kaipay.security.allowed-origins", "https://demo.example");
        assertThatCode(() -> new SecurityConfigurationGuard(environment).afterPropertiesSet()).doesNotThrowAnyException();
        for (String origin : new String[]{"*", "https://*.example", "http://demo.example", "https://demo.example/path", "https://user@demo.example", "https://demo.example?x=y"}) {
            environment.setProperty("kaipay.security.allowed-origins", origin);
            assertThatThrownBy(() -> new SecurityConfigurationGuard(environment).afterPropertiesSet()).isInstanceOf(IllegalStateException.class);
        }
    }
    @Test void testProfileCannotTurnOffSecureCookiesAndLocalMustBeLoopback() {
        var environment = new MockEnvironment().withProperty("server.servlet.session.cookie.secure", "false");
        environment.setActiveProfiles("test");
        assertThatThrownBy(() -> new SecurityConfigurationGuard(environment).afterPropertiesSet()).isInstanceOf(IllegalStateException.class);
        environment.setActiveProfiles("local");
        environment.setProperty("server.address", "0.0.0.0");
        assertThatThrownBy(() -> new SecurityConfigurationGuard(environment).afterPropertiesSet()).isInstanceOf(IllegalStateException.class);
        environment.setProperty("server.address", "127.0.0.1");
        assertThatCode(() -> new SecurityConfigurationGuard(environment).afterPropertiesSet()).doesNotThrowAnyException();
        environment.setActiveProfiles("local", "public-demo");
        assertThatThrownBy(() -> new SecurityConfigurationGuard(environment).afterPropertiesSet()).isInstanceOf(IllegalStateException.class);
    }
}
