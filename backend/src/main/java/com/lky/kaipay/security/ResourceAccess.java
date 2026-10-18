package com.lky.kaipay.security;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.UUID;

/** Authorization for the HTTP application boundary; internal workers do not use this boundary. */
@Service
public class ResourceAccess {
    private final DemoIdentityStore identities;
    public ResourceAccess(DemoIdentityStore identities) { this.identities = identities; }

    public DemoPrincipal principal() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof DemoPrincipal principal)) {
            throw new AccessDeniedException("Access denied");
        }
        return principal;
    }
    public void merchant(UUID merchantId) {
        DemoPrincipal principal;
        try { principal = principal(); }
        catch (AccessDeniedException exception) {
            SecurityDiagnostics.record(SecurityDiagnostics.Event.MERCHANT_ACCESS_DENIED,
                    SecurityContextHolder.getContext().getAuthentication(), merchantId);
            throw exception;
        }
        if (!"MERCHANT".equals(principal.role()) || merchantId == null
                || !identities.isMember(principal.id(), merchantId)) {
            SecurityDiagnostics.record(SecurityDiagnostics.Event.MERCHANT_ACCESS_DENIED,
                    SecurityContextHolder.getContext().getAuthentication(), merchantId);
            throw new AccessDeniedException("Access denied");
        }
    }
    public void admin() {
        try {
            if (!"ADMIN".equals(principal().role())) throw new AccessDeniedException("Access denied");
        } catch (AccessDeniedException exception) {
            SecurityDiagnostics.record(SecurityDiagnostics.Event.ADMIN_ACCESS_DENIED,
                    SecurityContextHolder.getContext().getAuthentication(), null);
            throw exception;
        }
        SecurityDiagnostics.record(SecurityDiagnostics.Event.ADMIN_ACCESS_GRANTED,
                SecurityContextHolder.getContext().getAuthentication(), null);
    }
    public boolean isAdmin() { return "ADMIN".equals(principal().role()); }
}
