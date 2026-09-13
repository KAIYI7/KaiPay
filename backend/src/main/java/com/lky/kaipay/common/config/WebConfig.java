package com.lky.kaipay.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/v1/**")
                .allowedOrigins("http://localhost:28081", "http://127.0.0.1:28081")
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("X-Merchant-Id", "Idempotency-Key", "Content-Type", "Authorization")
                .exposedHeaders("X-Merchant-Id", "Idempotency-Key")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
