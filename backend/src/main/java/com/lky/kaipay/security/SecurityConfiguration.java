package com.lky.kaipay.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lky.kaipay.common.api.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;

import java.io.IOException;

@Configuration
public class SecurityConfiguration {
    @Bean public PasswordEncoder passwordEncoder() { return new BoundedBcryptPasswordEncoder(); }
    @Bean public DaoAuthenticationProvider authenticationProvider(DemoIdentityStore identities, PasswordEncoder encoder) {
        var provider = new DaoAuthenticationProvider(identities);
        provider.setPasswordEncoder(encoder);
        return provider;
    }
    @Bean public SecurityFilterChain securityFilterChain(HttpSecurity http, DemoIdentityStore identities,
                                                        ObjectMapper mapper) throws Exception {
        http.cors(cors -> {})
            .csrf(csrf -> csrf.csrfTokenRepository(new HttpSessionCsrfTokenRepository())
                    .csrfTokenRequestHandler(new XorCsrfTokenRequestAttributeHandler()))
            .authorizeHttpRequests(auth -> auth
                    .requestMatchers(HttpMethod.GET, "/v1/auth/csrf").permitAll()
                    .requestMatchers(HttpMethod.POST, "/v1/auth/login", "/v1/auth/logout").permitAll()
                    .requestMatchers(HttpMethod.GET, "/v1/auth/me").authenticated()
                    .requestMatchers(HttpMethod.GET, "/v1/events/dlt", "/v1/events/outbox").hasRole("ADMIN")
                    .requestMatchers(HttpMethod.GET, "/v1/ledger/journals", "/v1/ledger/journals/*").hasAnyRole("MERCHANT", "ADMIN")
                    .requestMatchers(HttpMethod.GET, "/v1/payments", "/v1/payments/*", "/v1/payments/*/refunds",
                            "/v1/ledger/balance", "/v1/merchants/balance").hasRole("MERCHANT")
                    .requestMatchers(HttpMethod.POST, "/v1/payments", "/v1/payments/*/capture", "/v1/payments/*/refunds").hasRole("MERCHANT")
                    .anyRequest().denyAll())
            .formLogin(login -> login.loginPage("/v1/auth/login").loginProcessingUrl("/v1/auth/login")
                    .successHandler((request, response, authentication) -> {
                        // Dao provider's credential erasure removes password; replace immutable principal too.
                        if (authentication.getPrincipal() instanceof DemoPrincipal principal) {
                            var clean = org.springframework.security.authentication.UsernamePasswordAuthenticationToken
                                    .authenticated(principal.withoutPassword(), null, authentication.getAuthorities());
                            var context = org.springframework.security.core.context.SecurityContextHolder.getContext();
                            context.setAuthentication(clean);
                            new org.springframework.security.web.context.HttpSessionSecurityContextRepository()
                                    .saveContext(context, request, response);
                        }
                        response.setStatus(204);
                    })
                    .failureHandler((request, response, exception) -> {
                        org.springframework.security.core.context.SecurityContextHolder.clearContext();
                        var session = request.getSession(false);
                        if (session != null) session.invalidate();
                        error(mapper, request, response, 401);
                    }))
            .logout(logout -> logout.logoutUrl("/v1/auth/logout").invalidateHttpSession(true)
                    .deleteCookies("JSESSIONID").logoutSuccessHandler((request, response, authentication) -> response.setStatus(204)))
            .exceptionHandling(exceptions -> exceptions
                    .authenticationEntryPoint((request, response, exception) -> error(mapper, request, response, 401))
                    .accessDeniedHandler((request, response, exception) -> error(mapper, request, response,
                            authenticated() ? 403 : 401)))
            .sessionManagement(session -> session.sessionFixation(fixation -> fixation.changeSessionId()))
            .requestCache(cache -> cache.disable())
            .httpBasic(basic -> basic.disable());
        http.addFilterBefore(new SessionIdentityFilter(identities), CsrfFilter.class);
        return http.build();
    }
    private static boolean authenticated() {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof DemoPrincipal;
    }
    private static void error(ObjectMapper mapper, HttpServletRequest request, HttpServletResponse response,
                              int status) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        mapper.writeValue(response.getOutputStream(), ErrorResponse.builder().status(status)
                .error(status == 401 ? "Unauthorized" : "Forbidden")
                .message(status == 401 ? "Authentication required or invalid credentials" : "Access denied")
                .path(request.getRequestURI()).build());
    }
}
