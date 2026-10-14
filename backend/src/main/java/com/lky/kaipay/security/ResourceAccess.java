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
        DemoPrincipal principal = principal();
        if (!"MERCHANT".equals(principal.role()) || merchantId == null
                || !identities.isMember(principal.id(), merchantId)) {
            throw new AccessDeniedException("Access denied");
        }
    }
    public void admin() {
        if (!"ADMIN".equals(principal().role())) throw new AccessDeniedException("Access denied");
    }
    public boolean isAdmin() { return "ADMIN".equals(principal().role()); }
}
