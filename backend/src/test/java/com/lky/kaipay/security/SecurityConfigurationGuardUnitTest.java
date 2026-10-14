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
    @Test void forwardedHeadersCannotBeEnabledWithoutReviewedProxyBoundary() {
        for (String strategy : new String[]{"native", "framework", ""}) {
            var environment = new MockEnvironment().withProperty("server.forward-headers-strategy", strategy);
            assertThatThrownBy(() -> new SecurityConfigurationGuard(environment).afterPropertiesSet())
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("trusted proxy");
        }
        // Native headers independently enable Tomcat's valve, including when strategy is NONE.
        for (String header : new String[]{"remote-ip-header", "protocol-header"}) {
            var environment = new MockEnvironment().withProperty("server.forward-headers-strategy", "NONE")
                    .withProperty("server.tomcat.remoteip." + header, "X-Client-Header");
            assertThatThrownBy(() -> new SecurityConfigurationGuard(environment).afterPropertiesSet())
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("trusted proxy");
        }
        var environment = new MockEnvironment().withProperty("server.forward-headers-strategy", "NONE")
                .withProperty("spring.main.cloud-platform", "CLOUD_FOUNDRY");
        assertThatCode(() -> new SecurityConfigurationGuard(environment).afterPropertiesSet()).doesNotThrowAnyException();
    }
    @Test void sessionCookiesRemainHostOnlyAndSessionsAreNeverPersisted() {
        var domain = new MockEnvironment().withProperty("server.servlet.session.cookie.domain", ".example.com");
        assertThatThrownBy(() -> new SecurityConfigurationGuard(domain).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("host-only");
        var persistent = new MockEnvironment().withProperty("server.servlet.session.persistent", "true");
        assertThatThrownBy(() -> new SecurityConfigurationGuard(persistent).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("memory");
    }
    @Test void sessionTimeoutCannotOverflowContainerSecondsAndDisableExpiry() {
        var overflow = new MockEnvironment().withProperty("server.servlet.session.timeout", "2147483648s");
        assertThatThrownBy(() -> new SecurityConfigurationGuard(overflow).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("container seconds");
        for (String timeout : new String[]{"60s", "20m", "2147483647s"}) {
            var environment = new MockEnvironment().withProperty("server.servlet.session.timeout", timeout);
            assertThatCode(() -> new SecurityConfigurationGuard(environment).afterPropertiesSet()).doesNotThrowAnyException();
        }
    }
    @Test void formParameterParsingCannotBeUnboundedOrExceedLoginResourceCap() {
        for (String size : new String[]{"0B", "-1B", "9KB", "2097152B"}) {
            var environment = new MockEnvironment().withProperty("server.tomcat.max-http-form-post-size", size);
            assertThatThrownBy(() -> new SecurityConfigurationGuard(environment).afterPropertiesSet())
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("8KB");
        }
        var bounded = new MockEnvironment().withProperty("server.tomcat.max-http-form-post-size", "8KB");
        assertThatCode(() -> new SecurityConfigurationGuard(bounded).afterPropertiesSet()).doesNotThrowAnyException();
    }
}
