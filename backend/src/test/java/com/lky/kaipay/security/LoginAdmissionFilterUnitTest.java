package com.lky.kaipay.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoginAdmissionFilterUnitTest {
    private final LoginAdmission admission = new LoginAdmission(1, 10, 1, 4, Duration.ofMinutes(1));
    private final LoginAdmissionFilter filter = new LoginAdmissionFilter(admission, new ObjectMapper().findAndRegisterModules());
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    private MockHttpServletRequest request(String method, String path, String peer) {
        var request = new MockHttpServletRequest(method, path);
        request.setServletPath(path); request.setRemoteAddr(peer);
        return request;
    }
    @Test void spoofedHeadersAndUsernameRotationDoNotChangePeerBudgetOrLeakIdentity() throws Exception {
        var first = request("POST","/v1/auth/login","127.0.0.1"); first.addParameter("username","known");
        filter.doFilter(first,new MockHttpServletResponse(),(req,res) -> {});
        var blocked = request("POST","/v1/auth/login","127.0.0.1");
        blocked.addHeader("Forwarded","for=192.0.2.1;proto=https"); blocked.addHeader("X-Forwarded-For","192.0.2.2");
        blocked.addHeader("X-Real-IP","192.0.2.3"); blocked.addParameter("username","unknown"); blocked.addParameter("password","secret");
        var session = blocked.getSession();
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated("old",null,java.util.List.of()));
        var response = new MockHttpServletResponse();
        filter.doFilter(blocked,response,(req,res) -> { throw new AssertionError("Must reject before authentication"); });
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("60");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getContentAsString()).doesNotContain("unknown","secret","127.0.0.1");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(((org.springframework.mock.web.MockHttpSession) session).isInvalid()).isTrue();
    }
    @Test void exceptionReleasesConcurrencyAndUnrelatedRequestsBypass() throws Exception {
        assertThatThrownBy(() -> filter.doFilter(request("POST","/v1/auth/login","a"),new MockHttpServletResponse(),
                (req,res) -> { throw new ServletException("test"); })).isInstanceOf(ServletException.class);
        var calls = new AtomicInteger();
        filter.doFilter(request("POST","/v1/auth/login","b"),new MockHttpServletResponse(),(req,res) -> calls.incrementAndGet());
        for (String path : new String[]{"/v1/payments","/v1/auth/logout","/v1/auth/csrf"})
            filter.doFilter(request("POST",path,"a"),new MockHttpServletResponse(),(req,res) -> calls.incrementAndGet());
        filter.doFilter(request("GET","/v1/auth/login","a"),new MockHttpServletResponse(),(req,res) -> calls.incrementAndGet());
        assertThat(calls.get()).isEqualTo(5);
    }
    @Test void contextPathLoginUsesSameMatcherAsFormAuthentication() throws Exception {
        var request = request("POST","/v1/auth/login","a");
        request.setContextPath("/demo"); request.setRequestURI("/demo/v1/auth/login");
        filter.doFilter(request,new MockHttpServletResponse(),(req,res) -> {});
        var response = new MockHttpServletResponse();
        filter.doFilter(request("POST","/v1/auth/login","a"),response,(req,res) -> { throw new AssertionError(); });
        assertThat(response.getStatus()).isEqualTo(429);
    }
}
