package com.lky.kaipay.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${kaipay.security.allowed-origins:}")
    private String origins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/v1/**")
                .allowedOrigins(origins.isBlank() ? new String[0] : origins.split(","))
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("X-Merchant-Id", "Idempotency-Key", "Content-Type", "X-CSRF-TOKEN", "X-Correlation-Id")
                .exposedHeaders("X-Merchant-Id", "Idempotency-Key", "X-Correlation-Id")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
