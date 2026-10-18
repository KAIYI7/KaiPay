package com.lky.kaipay.refund.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lky.kaipay.common.event.EventEnvelope;
import com.lky.kaipay.common.observability.CorrelationContext;
import com.lky.kaipay.common.exception.EntityNotFoundException;
import com.lky.kaipay.common.exception.IdempotencyConflictException;
import com.lky.kaipay.common.exception.InvalidRefundException;
import com.lky.kaipay.ledger.service.LedgerService;
import com.lky.kaipay.outbox.domain.PaymentEventOutbox;
import com.lky.kaipay.outbox.domain.PaymentEventOutboxStatus;
import com.lky.kaipay.outbox.repository.PaymentEventOutboxRepository;
import com.lky.kaipay.payment.domain.Payment;
import com.lky.kaipay.payment.domain.PaymentStatus;
import com.lky.kaipay.payment.repository.PaymentRepository;
import com.lky.kaipay.refund.api.dto.CreateRefundRequest;
import com.lky.kaipay.refund.api.dto.RefundResponse;
import com.lky.kaipay.refund.domain.Refund;
import com.lky.kaipay.refund.domain.RefundStatus;
import com.lky.kaipay.refund.domain.event.PaymentRefundedEvent;
import com.lky.kaipay.refund.repository.RefundRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RefundService {

    private final RefundRepository refundRepository;
    private final PaymentRepository paymentRepository;
    private final LedgerService ledgerService;
    private final PaymentEventOutboxRepository paymentEventOutboxRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public RefundResponse createRefund(UUID merchantId, UUID paymentId, String idempotencyKey, CreateRefundRequest request) {
        // 1. Idempotency check
        Optional<Refund> existingRefund = refundRepository.findByMerchantIdAndIdempotencyKey(merchantId, idempotencyKey);
        if (existingRefund.isPresent()) {
            return replayRefund(existingRefund.get(), paymentId, request);
        }

        // 2. Find payment by id and merchantId with pessimistic lock
        Payment payment = paymentRepository.findByIdAndMerchantIdForUpdate(paymentId, merchantId)
                .orElseThrow(() -> new EntityNotFoundException(
                        String.format("Payment %s not found for merchant %s", paymentId, merchantId)
                ));

        // A concurrent request for this payment may have committed while we waited for its lock.
        // Recheck before status/balance validation, including after a completed full refund.
        existingRefund = refundRepository.findByMerchantIdAndIdempotencyKey(merchantId, idempotencyKey);
        if (existingRefund.isPresent()) {
            return replayRefund(existingRefund.get(), paymentId, request);
        }

        // 3. Validate status: must be CAPTURED or PARTIALLY_REFUNDED
        com.lky.kaipay.common.MoneyCurrency.requireMyr(payment.getCurrency());
        if (refundRepository.findByPaymentId(paymentId).stream()
                .anyMatch(refund -> !payment.getCurrency().equals(refund.getCurrency()))) {
            throw new InvalidRefundException("Stored refund currency conflicts with payment currency");
        }
        if (payment.getStatus() != PaymentStatus.CAPTURED && payment.getStatus() != PaymentStatus.PARTIALLY_REFUNDED) {
            throw new InvalidRefundException("Cannot refund payment in status " + payment.getStatus());
        }

        // 4. Validate amount
        Long sum = refundRepository.sumRefundedAmountByPaymentId(paymentId);
        long totalRefunded = sum != null ? sum : 0L;
        long maxRefundable = payment.getAmountCents() - totalRefunded;

        if (request.getAmountCents() > maxRefundable) {
            throw new InvalidRefundException("Refund amount exceeds refundable balance of " + maxRefundable + " sen");
        }

        // 5. Create and save Refund entity
        Refund refund = Refund.builder()
                .payment(payment)
                .merchant(payment.getMerchant())
                .amountCents(request.getAmountCents())
                .currency(payment.getCurrency())
                .status(RefundStatus.COMPLETED)
                .reason(request.getReason())
                .idempotencyKey(idempotencyKey)
                .build();

        // Different payments do not share a lock. The merchant/key unique constraint remains
        // the final guard; let violations roll back the whole transaction and map to HTTP 409.
        Refund savedRefund = refundRepository.save(refund);

        // 6. Update payment status
        long newTotalRefunded = totalRefunded + request.getAmountCents();
        if (newTotalRefunded == payment.getAmountCents()) {
            payment.transitionTo(PaymentStatus.REFUNDED);
        } else {
            payment.transitionTo(PaymentStatus.PARTIALLY_REFUNDED);
        }
        paymentRepository.save(payment);

        // 7. Build PaymentRefundedEvent, wrap in EventEnvelope, save to PaymentEventOutbox
        PaymentRefundedEvent refundedEvent = PaymentRefundedEvent.builder()
                .refundId(savedRefund.getId())
                .paymentId(payment.getId())
                .merchantId(merchantId)
                .refundAmountCents(savedRefund.getAmountCents())
                .currency(savedRefund.getCurrency())
                .reason(savedRefund.getReason())
                .refundedAt(Instant.now())
                .build();

        EventEnvelope<PaymentRefundedEvent> envelope = EventEnvelope.of(
                "PaymentRefundedEvent",
                "REFUND",
                savedRefund.getId().toString(),
                merchantId,
                refundedEvent
        );

        String jsonPayload;
        try {
            jsonPayload = objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize outbox event envelope for refund {}", savedRefund.getId(), e);
            throw new IllegalStateException("Failed to serialize outbox event envelope", e);
        }

        PaymentEventOutbox outboxRecord = PaymentEventOutbox.builder()
                .aggregateType("REFUND")
                .aggregateId(savedRefund.getId().toString())
                .eventType("PaymentRefundedEvent")
                .payload(jsonPayload)
                .headers(CorrelationContext.outboxHeaders(merchantId, envelope.getEventId()))
                .status(PaymentEventOutboxStatus.PENDING)
                .build();

        paymentEventOutboxRepository.save(outboxRecord);

        // 8. Post reversing journal
        ledgerService.recordPaymentRefund(payment, savedRefund, envelope.getEventId());

        return RefundResponse.fromEntity(savedRefund);
    }

    private RefundResponse replayRefund(Refund refund, UUID paymentId, CreateRefundRequest request) {
        if (!refund.getPayment().getId().equals(paymentId)
                || refund.getAmountCents() != request.getAmountCents()
                || !Objects.equals(refund.getReason(), request.getReason())) {
            throw new IdempotencyConflictException("Refund idempotency key has already been used for a different request");
        }
        log.info("Idempotent refund request returning existing refund {}", refund.getId());
        return RefundResponse.fromEntity(refund);
    }

    @Transactional(readOnly = true)
    public List<RefundResponse> getPaymentRefunds(UUID merchantId, UUID paymentId) {
        paymentRepository.findByIdAndMerchantId(paymentId, merchantId)
                .orElseThrow(() -> new EntityNotFoundException(
                        String.format("Payment %s not found for merchant %s", paymentId, merchantId)
                ));

        List<Refund> refunds = refundRepository.findByPaymentId(paymentId);
        return refunds.stream()
                .map(RefundResponse::fromEntity)
                .toList();
    }
}
