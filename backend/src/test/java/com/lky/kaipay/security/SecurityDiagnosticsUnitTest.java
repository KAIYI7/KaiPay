package com.lky.kaipay.security;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class SecurityDiagnosticsUnitTest {
    private final Logger logger = (Logger) LoggerFactory.getLogger(SecurityDiagnostics.class);
    private final List<String> messages = new ArrayList<>();
    private final List<Map<String, String>> contexts = new ArrayList<>();
    private final AppenderBase<ILoggingEvent> capture = new AppenderBase<>() {
        @Override protected void append(ILoggingEvent event) {
            messages.add(event.getFormattedMessage());
            contexts.add(Map.copyOf(event.getMDCPropertyMap()));
            assertThat(event.getThrowableProxy()).isNull();
        }
    };
    @BeforeEach void start() { capture.start(); logger.addAppender(capture); }
    @AfterEach void cleanup() {
        logger.detachAppender(capture); capture.stop(); SecurityContextHolder.clearContext(); MDC.clear();
    }

    @Test void admissionRejectionHasNoPeerCredentialsRequestTextOrPreviousIdentity() throws Exception {
        var admission = new LoginAdmission(1, 10, 1, 4, Duration.ofMinutes(1));
        try (var ignored = admission.acquire("198.51.100.228").permit()) { }
        var principal = principal("ADMIN");
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, "prior-credentials", principal.getAuthorities()));
        MDC.put("traceId", "admission-test");
        var request = new MockHttpServletRequest("POST", "/hostile-context/v1/auth/login");
        request.setContextPath("/hostile-context");
        request.setServletPath("/v1/auth/login");
        request.setRemoteAddr("198.51.100.228");
        request.setQueryString("hostile-query=secret");
        request.addParameter("username", "hostile-user\r\nforged-event=success");
        request.addParameter("password", "hostile-password");
        request.addHeader("X-Forwarded-For", "hostile-header");
        request.getSession();
        var response = new MockHttpServletResponse();
        new LoginAdmissionFilter(admission, new ObjectMapper().findAndRegisterModules()).doFilter(request, response,
                (req, res) -> { throw new AssertionError("Rejected before authentication"); });
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(messages).containsExactly("Security event=LOGIN_ADMISSION_DENIED operation=login outcome=denied actorId=- merchantId=-");
        assertThat(contexts).containsExactly(Map.of("traceId", "admission-test"));
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test void adminDenialAndMissingPrincipalMerchantDenialAreAttributableOnlyWhenTrusted() {
        var access = new ResourceAccess(mock(DemoIdentityStore.class));
        var merchant = principal("MERCHANT");
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(merchant, null, merchant.getAuthorities()));
        assertThatThrownBy(access::admin).isInstanceOf(AccessDeniedException.class);
        UUID target = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("hostile-principal\r\n", "secret", List.of()));
        assertThatThrownBy(() -> access.merchant(target)).isInstanceOf(AccessDeniedException.class);
        assertThat(messages).containsExactly(
                "Security event=ADMIN_ACCESS_DENIED operation=admin_access outcome=denied actorId=" + merchant.id() + " merchantId=-",
                "Security event=MERCHANT_ACCESS_DENIED operation=merchant_access outcome=denied actorId=- merchantId=" + target);
    }

    @Test void unauthenticatedDemoPrincipalDoesNotBecomeAnActor() {
        var principal = principal("ADMIN");
        SecurityDiagnostics.record(SecurityDiagnostics.Event.REQUEST_DENIED,
                UsernamePasswordAuthenticationToken.unauthenticated(principal, "secret"), null);
        assertThat(messages).containsExactly("Security event=REQUEST_DENIED operation=request outcome=denied actorId=- merchantId=-");
    }

    private DemoPrincipal principal(String role) {
        return new DemoPrincipal(UUID.randomUUID(), "hostile-user\r\n", "hostile-hash", role, true, 1);
    }
}
