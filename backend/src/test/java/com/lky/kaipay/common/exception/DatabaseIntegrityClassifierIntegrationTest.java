package com.lky.kaipay.common.exception;

import com.lky.kaipay.AbstractPostgresIntegrationTest;
import com.lky.kaipay.common.api.GlobalExceptionHandler;
import jakarta.persistence.EntityManager;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.jpa.vendor.HibernateJpaDialect;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/** Verifies metadata produced by the actual migrated PostgreSQL schema and Hibernate dialect. */
class DatabaseIntegrityClassifierIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @ParameterizedTest
    @ValueSource(strings = {"uk_merchant_idempotency", "pk_idempotency_records", "uk_refunds_idempotency"})
    void identifiesRealIdempotencyConstraintMetadata(String constraint) {
        Throwable failure = catchThrowable(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Map<String, Object> ids = seedMerchantAndCustomer();
            String insert;
            switch (constraint) {
                case "uk_merchant_idempotency" -> insert = paymentInsert("100");
                case "pk_idempotency_records" -> insert = """
                        insert into idempotency_records (merchant_id, idempotency_key, request_hash, response_status, response_body, expires_at)
                        values (:merchant, 'same-key', 'hash', 201, '{}', now() + interval '1 hour')
                        """;
                case "uk_refunds_idempotency" -> {
                    execute(paymentInsert("100"), ids);
                    insert = """
                            insert into refunds (payment_id, merchant_id, amount_cents, currency, status, idempotency_key)
                            values (:payment, :merchant, 50, 'MYR', 'COMPLETED', 'same-key')
                            """;
                }
                default -> throw new IllegalArgumentException(constraint);
            }
            execute(insert, ids);
            // Payments have different primary keys, so the intended idempotency constraint wins.
            if (constraint.equals("uk_merchant_idempotency")) {
                ids.put("payment", UUID.randomUUID());
            }
            execute(insert, ids);
        }));

        ConstraintViolationException violation = findViolation(failure);
        assertThat(violation.getSQLState()).isEqualTo("23505");
        assertThat(violation.getConstraintName()).isEqualTo(constraint);
        DataIntegrityViolationException translated = translate(violation);
        assertThat(DatabaseIntegrityClassifier.isIdempotencyConflict(translated)).isTrue();
        assertThat(new GlobalExceptionHandler().handleDataIntegrityViolationException(
                translated, new MockHttpServletRequest("POST", "/v1/payments")).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void rejectsRealUnrelatedUniqueConstraint() {
        Throwable failure = catchThrowable(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Map<String, Object> ids = seedMerchantAndCustomer();
            execute("insert into customers (merchant_id, email, full_name) values (:merchant, 'same@example.com', 'Duplicate')", ids);
        }));

        ConstraintViolationException violation = findViolation(failure);
        assertThat(violation.getSQLState()).isEqualTo("23505");
        assertThat(violation.getConstraintName()).isEqualTo("uk_merchant_customer_email");
        assertInternalError(violation);
    }

    @Test
    void rejectsRealCheckConstraint() {
        Throwable failure = catchThrowable(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Map<String, Object> ids = seedMerchantAndCustomer();
            execute(paymentInsert("-1"), ids);
        }));

        ConstraintViolationException violation = findViolation(failure);
        assertThat(violation.getSQLState()).isEqualTo("23514");
        assertInternalError(violation);
    }

    private Map<String, Object> seedMerchantAndCustomer() {
        Map<String, Object> ids = new java.util.HashMap<>();
        ids.put("merchant", UUID.randomUUID());
        ids.put("customer", UUID.randomUUID());
        ids.put("payment", UUID.randomUUID());
        execute("insert into merchants (id, name, api_key_hash) values (:merchant, 'Classification Test', :hash)",
                Map.of("merchant", ids.get("merchant"), "hash", UUID.randomUUID().toString()));
        execute("insert into customers (id, merchant_id, email, full_name) values (:customer, :merchant, 'same@example.com', 'Test')", ids);
        return ids;
    }

    private String paymentInsert(String amount) {
        return """
                insert into payments (id, merchant_id, customer_id, amount_cents, currency, status, idempotency_key)
                values (:payment, :merchant, :customer, %s, 'MYR', 'CREATED', 'same-key')
                """.formatted(amount);
    }

    private void execute(String sql, Map<String, Object> parameters) {
        var query = entityManager.createNativeQuery(sql);
        parameters.forEach((name, value) -> {
            if (sql.contains(":" + name)) {
                query.setParameter(name, value);
            }
        });
        query.executeUpdate();
    }

    private ConstraintViolationException findViolation(Throwable failure) {
        assertThat(failure).isNotNull();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                return violation;
            }
        }
        throw new AssertionError("Expected actual Hibernate constraint metadata", failure);
    }

    private DataIntegrityViolationException translate(ConstraintViolationException violation) {
        var translated = new HibernateJpaDialect().translateExceptionIfPossible(violation);
        assertThat(translated).isInstanceOf(DataIntegrityViolationException.class);
        return (DataIntegrityViolationException) translated;
    }

    private void assertInternalError(ConstraintViolationException violation) {
        DataIntegrityViolationException translated = translate(violation);
        assertThat(DatabaseIntegrityClassifier.isIdempotencyConflict(translated)).isFalse();
        var response = new GlobalExceptionHandler().handleDataIntegrityViolationException(
                translated, new MockHttpServletRequest("POST", "/v1/payments"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).isEqualTo("An unexpected internal server error occurred");
    }
}
