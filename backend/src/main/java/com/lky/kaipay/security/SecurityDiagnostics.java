package com.lky.kaipay.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;

import java.util.UUID;

/** Bounded diagnostics in the existing log stream; never accepts request or credential text. */
final class SecurityDiagnostics {
    private static final Logger LOG = LoggerFactory.getLogger(SecurityDiagnostics.class);

    enum Event {
        LOGIN_SUCCEEDED("login", "success"),
        LOGIN_FAILED("login", "failure"),
        LOGIN_ADMISSION_DENIED("login", "denied"),
        LOGOUT_SUCCEEDED("logout", "success"),
        REQUEST_UNAUTHORIZED("request", "unauthorized"),
        REQUEST_DENIED("request", "denied"),
        SESSION_REVOKED("session", "revoked"),
        MERCHANT_ACCESS_DENIED("merchant_access", "denied"),
        ADMIN_ACCESS_GRANTED("admin_access", "granted"),
        ADMIN_ACCESS_DENIED("admin_access", "denied");

        private final String operation;
        private final String outcome;
        Event(String operation, String outcome) { this.operation = operation; this.outcome = outcome; }
    }

    private SecurityDiagnostics() {}

    static void record(Event event, Authentication authentication, UUID merchantId) {
        UUID actorId = authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof DemoPrincipal principal ? principal.id() : null;
        LOG.info("Security event={} operation={} outcome={} actorId={} merchantId={}",
                event.name(), event.operation, event.outcome, actorId == null ? "-" : actorId,
                merchantId == null ? "-" : merchantId);
    }
}
