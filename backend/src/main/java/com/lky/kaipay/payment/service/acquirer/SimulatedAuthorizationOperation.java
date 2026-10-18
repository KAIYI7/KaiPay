package com.lky.kaipay.payment.service.acquirer;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** One retained intent and at most one definitive simulator outcome per payment. */
@Entity
@Table(name = "simulated_authorization_operations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SimulatedAuthorizationOperation implements org.springframework.data.domain.Persistable<UUID> {
    public enum Disposition { UNRESOLVED, APPROVED, DECLINED, REJECTED_NO_ACCEPTANCE }
    public static final String REJECTION_CODE = "SIMULATED_NO_ACCEPTANCE";
    public static final String REJECTION_MESSAGE = "Simulated fatal non-retryable acquirer error";

    @Id
    @Column(name = "payment_id", updatable = false)
    private UUID paymentId;
    @Column(nullable = false, updatable = false, length = 40)
    private String namespace;
    @Column(name = "contract_version", nullable = false, updatable = false)
    private int contractVersion;
    @Column(name = "merchant_id", nullable = false, updatable = false)
    private UUID merchantId;
    @Column(name = "amount_cents", nullable = false, updatable = false)
    private long amountCents;
    @Column(nullable = false, updatable = false, length = 3)
    private String currency;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Disposition disposition;
    @Column(name = "evaluation_count", nullable = false)
    private long evaluationCount;
    @Column(name = "authorization_code", length = 100)
    private String authorizationCode;
    @Column(name = "decline_code", length = 50)
    private String declineCode;
    @Column(name = "decline_message", columnDefinition = "TEXT")
    private String declineMessage;
    @Column(name = "failure_code", length = 50)
    private String failureCode;
    @Column(name = "failure_message", columnDefinition = "TEXT")
    private String failureMessage;
    @Column(name = "result_at")
    private Instant resultAt;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Transient
    private boolean newOperation = true;

    @Override public UUID getId() { return paymentId; }
    @Override public boolean isNew() { return newOperation; }
    @PostLoad @PostPersist
    private void markPersisted() { newOperation = false; }

    public static SimulatedAuthorizationOperation intent(AuthorizationRequest request) {
        com.lky.kaipay.common.MoneyCurrency.requireMyr(request.currency());
        var operation = new SimulatedAuthorizationOperation();
        operation.paymentId = request.paymentId();
        operation.namespace = AuthorizationRequest.NAMESPACE;
        operation.contractVersion = AuthorizationRequest.CONTRACT_VERSION;
        operation.merchantId = request.merchantId();
        operation.amountCents = request.amountCents();
        operation.currency = request.currency();
        operation.disposition = Disposition.UNRESOLVED;
        operation.createdAt = operation.updatedAt = now();
        return operation;
    }

    public AuthorizationRequest getRequest() {
        return new AuthorizationRequest(paymentId, merchantId, amountCents, currency);
    }

    public void validateRequest(AuthorizationRequest request) {
        if (!AuthorizationRequest.NAMESPACE.equals(namespace)
                || contractVersion != AuthorizationRequest.CONTRACT_VERSION || !getRequest().equals(request)) {
            throw new IllegalArgumentException("Conflicting simulated authorization identity or request");
        }
    }

    /** Invoked only with the operation row locked in the independent simulator transaction. */
    void evaluate() {
        if (disposition != Disposition.UNRESOLVED) return;
        // Definitive historical results can be replayed; never create a new non-MYR outcome.
        com.lky.kaipay.common.MoneyCurrency.requireMyr(currency);
        evaluationCount = Math.incrementExact(evaluationCount);
        updatedAt = now();
        if (amountCents == 777700L || (amountCents == 888800L && evaluationCount < 3)) return;
        resultAt = updatedAt;
        if (amountCents == 666600L) {
            disposition = Disposition.REJECTED_NO_ACCEPTANCE;
            failureCode = REJECTION_CODE;
            failureMessage = REJECTION_MESSAGE;
        } else if (amountCents >= 999900L) {
            disposition = Disposition.DECLINED;
            declineCode = "INSUFFICIENT_FUNDS";
            declineMessage = "Simulated card decline: insufficient funds";
        } else {
            disposition = Disposition.APPROVED;
            authorizationCode = (amountCents == 888800L ? "AUTH-RECOVERED-" : "AUTH-")
                    + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(java.util.Locale.ROOT);
        }
    }

    public AcquirerAuthorizationResult getResult() {
        if (disposition == Disposition.UNRESOLVED) return null;
        return AcquirerAuthorizationResult.builder()
                .approved(disposition == Disposition.APPROVED)
                .rejectedNoAcceptance(disposition == Disposition.REJECTED_NO_ACCEPTANCE)
                .authorizationCode(authorizationCode).declineCode(declineCode).declineMessage(declineMessage)
                .failureCode(failureCode).failureMessage(failureMessage).timestamp(resultAt).build();
    }

    public AcquirerAuthorizationResult result() { return getResult(); }

    private static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MICROS); }
}
