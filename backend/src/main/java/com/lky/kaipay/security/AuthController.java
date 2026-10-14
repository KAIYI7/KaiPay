package com.lky.kaipay.security;

import com.lky.kaipay.common.api.ApiResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/auth")
public class AuthController {
    private final ResourceAccess access;
    private final DemoIdentityStore identities;
    public AuthController(ResourceAccess access, DemoIdentityStore identities) {
        this.access = access;
        this.identities = identities;
    }
    @GetMapping("/csrf") public ApiResponse<CsrfResponse> csrf(CsrfToken token) {
        return ApiResponse.success(new CsrfResponse(token.getToken(), token.getHeaderName()));
    }
    @GetMapping("/me") public ApiResponse<SessionResponse> me() {
        var principal = access.principal();
        return ApiResponse.success(new SessionResponse(principal.id(), principal.username(),
                List.of(principal.role()), access.isAdmin() ? List.of() : identities.memberships(principal.id())));
    }
    public record CsrfResponse(String token, String headerName) {}
    public record SessionResponse(UUID id, String username, List<String> roles,
                                  List<DemoIdentityStore.MerchantMembership> merchants) {}
}
