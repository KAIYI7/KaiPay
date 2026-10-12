package com.lky.kaipay.common.exception;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseIntegrityClassifierUnitTest {

    @ParameterizedTest
    @ValueSource(strings = {"uk_merchant_idempotency", "pk_idempotency_records", "uk_refunds_idempotency"})
    void recognizesKnownUniqueConstraintsThroughWrappedCauses(String constraint) {
        Throwable failure = new DataIntegrityViolationException("outer",
                new IllegalStateException("wrapper", violation("23505", constraint)));

        assertThat(DatabaseIntegrityClassifier.isIdempotencyConflict(failure)).isTrue();
    }

    @ParameterizedTest
    @CsvSource(value = {
            "23503,uk_merchant_idempotency",
            "23502,pk_idempotency_records",
            "23514,uk_refunds_idempotency",
            "23505,uk_merchants_api_key_hash",
            "23505,uk_payment_methods_token",
            "23505,UK_MERCHANT_IDEMPOTENCY",
            "23505,uk_merchant_idempotency_suffix",
            "23505,NULL",
            "NULL,uk_merchant_idempotency"
    }, nullValues = "NULL")
    void rejectsUnknownConstraintsOrNonUniqueStates(String state, String constraint) {
        assertThat(DatabaseIntegrityClassifier.isIdempotencyConflict(violation(state, constraint))).isFalse();
    }

    @Test
    void doesNotClassifyByMessageOrSqlStateAlone() {
        String spoofedMessage = "23505 duplicate key uk_merchant_idempotency pk_idempotency_records uk_refunds_idempotency";
        Throwable failure = new DataIntegrityViolationException(spoofedMessage,
                new SQLException(spoofedMessage, "23505"));

        assertThat(DatabaseIntegrityClassifier.isIdempotencyConflict(failure)).isFalse();
        assertThat(DatabaseIntegrityClassifier.isIdempotencyConflict(null)).isFalse();
    }

    @Test
    @Timeout(1)
    void terminatesOnCyclicCauseChains() {
        RuntimeException first = new RuntimeException("first");
        RuntimeException second = new RuntimeException("second", first);
        first.initCause(second);

        assertThat(DatabaseIntegrityClassifier.isIdempotencyConflict(first)).isFalse();
    }

    private ConstraintViolationException violation(String state, String constraint) {
        return new ConstraintViolationException("secret database detail", new SQLException("secret", state),
                "insert into sensitive_table values ('secret')", constraint);
    }
}
