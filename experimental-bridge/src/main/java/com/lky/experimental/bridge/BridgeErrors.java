package com.lky.experimental.bridge;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class BridgeErrors {
    // The code is also handed to the filter's live-route rejection line, which prints it only if it is allowlisted.
    @ExceptionHandler(BridgeFailure.class) ResponseEntity<Map<String, String>> expected(BridgeFailure failure, HttpServletRequest request) {
        request.setAttribute(BridgeFilter.REJECTION, failure.code);
        return ResponseEntity.status(failure.status).body(Map.of("error", failure.code));
    }
    // Framework exception messages can contain rejected data. Never echo or log them.
    @ExceptionHandler(Exception.class) ResponseEntity<Map<String, String>> sanitized(Exception ignored, HttpServletRequest request) {
        request.setAttribute(BridgeFilter.REJECTION, "INVALID_REQUEST");
        return ResponseEntity.badRequest().body(Map.of("error", "INVALID_REQUEST"));
    }
}
