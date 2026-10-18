package com.lky.kaipay.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Revoke sessions when accounts are disabled/deleted, rotated, or their role changes. */
final class SessionIdentityFilter extends OncePerRequestFilter {
    private final DemoIdentityStore identities;
    SessionIdentityFilter(DemoIdentityStore identities) { this.identities = identities; }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                              FilterChain chain) throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof DemoPrincipal principal) {
            var current = identities.findById(principal.id());
            if (current.isEmpty() || !current.get().enabled()
                    || current.get().credentialVersion() != principal.credentialVersion()
                    || !current.get().role().equals(principal.role())) {
                SecurityDiagnostics.record(SecurityDiagnostics.Event.SESSION_REVOKED, authentication, null);
                SecurityContextHolder.clearContext();
                var session = request.getSession(false);
                if (session != null) session.invalidate();
            }
        }
        chain.doFilter(request, response);
    }
}
