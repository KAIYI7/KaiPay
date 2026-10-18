package com.lky.kaipay.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lky.kaipay.common.api.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Constructed only inside the security chain, after CSRF and before form login. */
final class LoginAdmissionFilter extends OncePerRequestFilter {
    private final LoginAdmission admission;
    private final ObjectMapper mapper;
    // Match the same servlet path semantics as formLogin, including a deployed context path.
    private final AntPathRequestMatcher login = new AntPathRequestMatcher("/v1/auth/login", "POST");

    LoginAdmissionFilter(LoginAdmission admission, ObjectMapper mapper) {
        this.admission = admission; this.mapper = mapper;
    }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) { return !login.matches(request); }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                              FilterChain chain) throws ServletException, IOException {
        var decision = admission.acquire(request.getRemoteAddr());
        if (decision.permit() == null) {
            SecurityDiagnostics.record(SecurityDiagnostics.Event.LOGIN_ADMISSION_DENIED, null, null);
            // Failed identity switching must not preserve a previous logged-in principal.
            SecurityContextHolder.clearContext();
            var session = request.getSession(false);
            if (session != null) session.invalidate();
            response.setStatus(429);
            response.setHeader("Retry-After", Long.toString(decision.retryAfterSeconds()));
            response.setHeader("Cache-Control", "no-store");
            response.setContentType("application/json");
            mapper.writeValue(response.getOutputStream(), ErrorResponse.builder().status(429)
                    .error("Too Many Requests").message("Login temporarily unavailable; try again later")
                    .path(request.getRequestURI()).build());
            return;
        }
        try (var permit = decision.permit()) { chain.doFilter(request, response); }
    }
}
