package com.lky.kaipay.common.api;

import com.lky.kaipay.common.exception.IdempotencyConflictException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerUnitTest {

    private static final String CONFLICT_MESSAGE = "Concurrent request conflict: an operation with this idempotency key is already in progress or completed.";
    private static final String INTERNAL_MESSAGE = "An unexpected internal server error occurred";
    private static final String SECRET = "customer-secret-123";

    @ParameterizedTest
    @ValueSource(strings = {"uk_merchant_idempotency", "pk_idempotency_records", "uk_refunds_idempotency"})
    void identifiedIdempotencyViolationsReturnExistingConflictContract(String constraint) throws Exception {
        MockMvc mvc = mvcFor(integrityFailure("23505", constraint));

        String body = mvc.perform(get("/failure"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.message").value(CONFLICT_MESSAGE))
                .andExpect(jsonPath("$.path").value("/failure"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(SECRET, constraint, "sensitive_table", "23505");
    }

    @ParameterizedTest
    @ValueSource(strings = {"23503", "23502", "23514", "23505"})
    void unrelatedIntegrityViolationsReturnSanitizedInternalError(String state) throws Exception {
        MockMvc mvc = mvcFor(integrityFailure(state, "unrelated_private_constraint"));

        String body = mvc.perform(get("/failure"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.error").value("Internal Server Error"))
                .andExpect(jsonPath("$.message").value(INTERNAL_MESSAGE))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(SECRET, "unrelated_private_constraint", "sensitive_table", state, "idempotency");
    }

    @Test
    void missingMetadataCannotSpoofIdempotencyByMessage() throws Exception {
        mvcFor(new DataIntegrityViolationException("uk_merchant_idempotency " + SECRET))
                .perform(get("/failure"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value(INTERNAL_MESSAGE));
    }

    @Test
    void preservesExplicitApplicationIdempotencyConflict() throws Exception {
        mvcFor(new IdempotencyConflictException("Idempotency key reused with different request parameters"))
                .perform(get("/failure"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Idempotency key reused with different request parameters"));
    }

    private DataIntegrityViolationException integrityFailure(String state, String constraint) {
        ConstraintViolationException cause = new ConstraintViolationException(SECRET,
                new SQLException(SECRET, state), "insert into sensitive_table values ('" + SECRET + "')", constraint);
        return new DataIntegrityViolationException(SECRET, new IllegalStateException(SECRET, cause));
    }

    private MockMvc mvcFor(RuntimeException failure) {
        return MockMvcBuilders.standaloneSetup(new FailureController(failure))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @RestController
    private static class FailureController {
        private final RuntimeException failure;

        private FailureController(RuntimeException failure) {
            this.failure = failure;
        }

        @GetMapping("/failure")
        public void fail() {
            throw failure;
        }
    }
}
