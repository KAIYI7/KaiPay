package com.lky.kaipay.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lky.kaipay.security.LoginAdmission;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;

final class IntegrationAuthenticationFilter extends OncePerRequestFilter {
    private final IntegrationProperties properties;
    private final ObjectMapper mapper;
    private final LoginAdmission admission = new LoginAdmission(240, 2000, 4, 4096, Duration.ofMinutes(1));
    private final Map<UUID, Budget> budgets = new HashMap<>();

    IntegrationAuthenticationFilter(IntegrationProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                               FilterChain chain) throws ServletException, IOException {
        var decision = admission.acquire(request.getRemoteAddr());
        if (decision.permit() == null) {
            response.setHeader("Retry-After", Long.toString(decision.retryAfterSeconds()));
            IntegrationErrors.write(mapper, response, 429, "RATE_LIMITED");
            return;
        }
        try (var ignored = decision.permit()) {
            response.setHeader("Cache-Control", "no-store");
            if (request.getHeader("Origin") != null || request.getHeader("Cookie") != null) {
                throw new IntegrationFailure(401, "UNAUTHORIZED");
            }
            var binding = authenticate(request);
            if (!request.getRequestURI().matches("/v1/integrations/payments/[^/]+")
                    || !("PUT".equals(request.getMethod()) || "GET".equals(request.getMethod()))) {
                throw new IntegrationFailure(403, "FORBIDDEN");
            }
            if (request.getQueryString() != null || request.getHeader("X-Merchant-Id") != null
                    || request.getHeader("X-Customer-Id") != null || request.getHeader("Idempotency-Key") != null)
                throw new IntegrationFailure(400, "INVALID_REQUEST");
            if (!takeBudget(binding.integrationId(), request.getMethod())) {
                response.setHeader("Retry-After", "60");
                throw new IntegrationFailure(429, "RATE_LIMITED");
            }
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(binding, null,
                    List.of(new SimpleGrantedAuthority("INTEGRATION_SIMULATION"))));
            SecurityContextHolder.setContext(context);
            chain.doFilter(request, response);
        } catch (IntegrationFailure failure) {
            IntegrationErrors.write(mapper, response, failure.status, failure.code);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
    private IntegrationProperties.Binding authenticate(HttpServletRequest request) {
        var headers = Collections.list(request.getHeaders("Authorization"));
        if (!properties.enabled() || headers.size() != 1) throw new IntegrationFailure(401, "UNAUTHORIZED");
        String header = headers.getFirst();
        if (header.length() > 128 || !header.matches("Bearer kp_int_[A-Za-z0-9_-]{1,32}\\.[A-Za-z0-9_-]{43}"))
            throw new IntegrationFailure(401, "UNAUTHORIZED");
        String value = header.substring("Bearer kp_int_".length());
        String keyId = value.substring(0, value.indexOf('.'));
        String secret = value.substring(value.indexOf('.') + 1);
        byte[] decoded;
        byte[] digest;
        try {
            decoded = Base64.getUrlDecoder().decode(secret);
            if (decoded.length != 32 || !Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(secret))
                throw new IntegrationFailure(401, "UNAUTHORIZED");
            digest = MessageDigest.getInstance("SHA-256").digest(decoded);
            java.util.Arrays.fill(decoded, (byte) 0);
        } catch (java.security.NoSuchAlgorithmException | IllegalArgumentException exception) {
            throw new IntegrationFailure(401, "UNAUTHORIZED");
        }
        for (var binding : properties.bindings()) for (var key : binding.keys()) {
            if (key.keyId().equals(keyId) && MessageDigest.isEqual(HexFormat.of().parseHex(key.sha256()), digest)
                    && binding.enabled()) return binding;
        }
        throw new IntegrationFailure(401, "UNAUTHORIZED");
    }
    private synchronized boolean takeBudget(UUID id, String method) {
        long now = System.nanoTime();
        Budget b = budgets.computeIfAbsent(id, ignored -> new Budget(now));
        if (now - b.started >= Duration.ofMinutes(1).toNanos()) { b = new Budget(now); budgets.put(id, b); }
        if ("PUT".equals(method)) return ++b.puts <= 30;
        return ++b.gets <= 120;
    }
    private static final class Budget {
        final long started;
        int puts, gets;
        Budget(long started) { this.started = started; }
    }
}
