package com.lky.kaipay.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;

final class IntegrationErrors {
    private IntegrationErrors() {}
    static void write(ObjectMapper mapper, HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setHeader("Cache-Control", "no-store");
        if (status == 401) response.setHeader("WWW-Authenticate", "Bearer");
        mapper.writeValue(response.getOutputStream(), Map.of("success", false, "status", status,
                "code", code, "message", message(code)));
    }
    static String message(String code) {
        return switch (code) {
            case "INVALID_REQUEST" -> "Invalid simulation request";
            case "UNAUTHORIZED" -> "Authentication required or invalid credentials";
            case "FORBIDDEN" -> "Access denied";
            case "NOT_FOUND" -> "Simulation reference not found";
            case "IDEMPOTENCY_CONFLICT" -> "Reference already has a different immutable request";
            case "RECOVERY_REQUIRED" -> "Retained identity requires operator recovery";
            case "RATE_LIMITED" -> "Request limit reached";
            default -> "Simulation temporarily unavailable";
        };
    }
}
