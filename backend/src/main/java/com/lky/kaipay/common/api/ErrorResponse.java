package com.lky.kaipay.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.List;

@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorResponse {
    private final int status;
    private final String error;
    private final String message;
    private final String path;
    @Builder.Default
    private final Instant timestamp = Instant.now();
    private final List<ValidationError> validationErrors;

    @Getter
    @Builder
    public static class ValidationError {
        private final String field;
        private final String message;
    }
}
