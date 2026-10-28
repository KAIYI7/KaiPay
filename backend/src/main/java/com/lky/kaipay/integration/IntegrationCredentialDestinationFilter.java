package com.lky.kaipay.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.Collections;

/** Machine credentials never gain authority from a simultaneous browser session. */
public final class IntegrationCredentialDestinationFilter extends OncePerRequestFilter {
    private final ObjectMapper mapper;
    public IntegrationCredentialDestinationFilter(ObjectMapper mapper) { this.mapper = mapper; }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                               FilterChain chain) throws ServletException, IOException {
        boolean machineCredential = Collections.list(request.getHeaders("Authorization")).stream()
                .anyMatch(value -> value.regionMatches(true, 0, "Bearer kp_int_", 0, "Bearer kp_int_".length()));
        if (machineCredential) { IntegrationErrors.write(mapper, response, 403, "FORBIDDEN"); return; }
        chain.doFilter(request, response);
    }
}
