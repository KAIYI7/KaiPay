package com.lky.experimental.bridge;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class BridgeErrors {
    @ExceptionHandler(BridgeFailure.class) ResponseEntity<Map<String, String>> expected(BridgeFailure failure) {
        return ResponseEntity.status(failure.status).body(Map.of("error", failure.code));
    }
    // Framework exception messages can contain rejected data. Never echo or log them.
    @ExceptionHandler(Exception.class) ResponseEntity<Map<String, String>> sanitized(Exception ignored) {
        return ResponseEntity.badRequest().body(Map.of("error", "INVALID_REQUEST"));
    }
}
