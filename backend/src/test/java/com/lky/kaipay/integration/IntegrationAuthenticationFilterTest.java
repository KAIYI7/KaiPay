package com.lky.kaipay.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class IntegrationAuthenticationFilterTest {
    final byte[] secret = new byte[32];
    final IntegrationProperties.Binding binding;
    final String token;
    IntegrationAuthenticationFilterTest() throws Exception {
        new SecureRandom().nextBytes(secret);
        binding = new IntegrationProperties.Binding(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), true,
                List.of(new IntegrationProperties.Key("fixture", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(secret)))));
        token = "Bearer kp_int_fixture." + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
    }
    IntegrationAuthenticationFilter filter(boolean enabled) {
        return new IntegrationAuthenticationFilter(new IntegrationProperties(enabled, List.of(binding)), new ObjectMapper());
    }
    MockHttpServletRequest request(String method) {
        var request = new MockHttpServletRequest(method, "/v1/integrations/payments/" + UUID.randomUUID());
        request.setRemoteAddr("127.0.0.1"); request.addHeader("Authorization", token); return request;
    }
    @Test void disabledAndMixedCredentialsFailWithoutLeakingOrCreatingSessions() throws Exception {
        var disabled = filter(false); var response = new MockHttpServletResponse(); var request = request("GET");
        disabled.doFilter(request, response, (req, res) -> fail("disabled feature dispatched"));
        assertThat(response.getStatus()).isEqualTo(401); assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("Bearer");
        assertThat(response.getContentAsString()).doesNotContain(token); assertThat(request.getSession(false)).isNull();
        var enabled = filter(true);
        for (String value : List.of(token + "=", token + ", " + token, "Basic invalid", "Bearer kp_int_fixture." + "_".repeat(43))) {
            request = request("GET"); request.removeHeader("Authorization"); request.addHeader("Authorization", value);
            response = new MockHttpServletResponse(); enabled.doFilter(request, response, (req, res) -> fail("invalid credential dispatched"));
            assertThat(response.getStatus()).isEqualTo(401);
        }
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
    @Test void perBindingPutAndGetBudgetsAreSeparateAndBounded() throws Exception {
        var filter = filter(true); var calls = new AtomicInteger();
        for (int i=0;i<31;i++) {
            var response = new MockHttpServletResponse();
            filter.doFilter(request("PUT"), response, (req, res) -> calls.incrementAndGet());
            assertThat(response.getStatus()).isEqualTo(i < 30 ? 200 : 429);
            if (i==30) assertThat(response.getHeader("Retry-After")).isEqualTo("60");
        }
        for (int i=0;i<121;i++) {
            var response = new MockHttpServletResponse();
            filter.doFilter(request("GET"), response, (req, res) -> calls.incrementAndGet());
            assertThat(response.getStatus()).isEqualTo(i < 120 ? 200 : 429);
        }
        assertThat(calls.get()).isEqualTo(150);
    }
    @Test void fourInFlightRequestsBoundAdmissionAndReleaseAfterFailure() throws Exception {
        var filter = filter(true); var entered = new CountDownLatch(4); var release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(4)) {
            List<Future<?>> tasks = new ArrayList<>();
            for (int i=0;i<4;i++) tasks.add(pool.submit(() -> {
                try { filter.doFilter(request("GET"), new MockHttpServletResponse(), (req, res) -> {
                    entered.countDown();
                    try { assertThat(release.await(10, TimeUnit.SECONDS)).isTrue(); }
                    catch (InterruptedException failure) { throw new AssertionError(failure); }
                }); } catch (Exception failure) { throw new AssertionError(failure); }
            }));
            try {
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                var blocked = new MockHttpServletResponse();
                filter.doFilter(request("GET"), blocked, (req, res) -> fail("fifth request entered"));
                assertThat(blocked.getStatus()).isEqualTo(429);
            } finally { release.countDown(); }
            for (var task : tasks) task.get(10, TimeUnit.SECONDS);
        }
        var admitted = new MockHttpServletResponse(); filter.doFilter(request("GET"), admitted, (req, res) -> {});
        assertThat(admitted.getStatus()).isEqualTo(200);
    }
}
