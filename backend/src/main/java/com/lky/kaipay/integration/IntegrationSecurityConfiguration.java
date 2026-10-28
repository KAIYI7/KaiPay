package com.lky.kaipay.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.NullSecurityContextRepository;

@Configuration
@EnableConfigurationProperties(IntegrationProperties.class)
public class IntegrationSecurityConfiguration {
    @Bean @Order(1)
    SecurityFilterChain integrationSecurity(HttpSecurity http, IntegrationProperties properties,
                                             ObjectMapper mapper) throws Exception {
        http.securityMatcher("/v1/integrations/**")
                .cors(cors -> cors.disable())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(context -> context.securityContextRepository(new NullSecurityContextRepository()))
                .requestCache(cache -> cache.disable())
                .formLogin(login -> login.disable()).httpBasic(basic -> basic.disable()).logout(logout -> logout.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.PUT, "/v1/integrations/payments/*").hasAuthority("INTEGRATION_SIMULATION")
                        .requestMatchers(HttpMethod.GET, "/v1/integrations/payments/*").hasAuthority("INTEGRATION_SIMULATION")
                        .anyRequest().denyAll())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, failure) -> IntegrationErrors.write(mapper, response, 401, "UNAUTHORIZED"))
                        .accessDeniedHandler((request, response, failure) -> IntegrationErrors.write(mapper, response, 403, "FORBIDDEN")))
                .addFilterBefore(new IntegrationAuthenticationFilter(properties, mapper), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
