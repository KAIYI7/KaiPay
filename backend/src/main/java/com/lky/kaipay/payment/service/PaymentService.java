package com.lky.kaipay.payment.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lky.kaipay.common.event.EventEnvelope;
import com.lky.kaipay.common.exception.EntityNotFoundException;
import com.lky.kaipay.common.exception.InvalidRequestException;
import com.lky.kaipay.consumer.service.ConsumerDeduplicationService;
import com.lky.kaipay.customer.domain.Customer;
import com.lky.kaipay.customer.repository.CustomerRepository;
import com.lky.kaipay.ledger.service.LedgerService;
import com.lky.kaipay.merchant.domain.Merchant;
import com.lky.kaipay.merchant.repository.MerchantRepository;
import com.lky.kaipay.outbox.domain.PaymentEventOutbox;
import com.lky.kaipay.outbox.domain.PaymentEventOutboxStatus;
import com.lky.kaipay.outbox.repository.PaymentEventOutboxRepository;
import com.lky.kaipay.payment.api.dto.CreatePaymentRequest;
import com.lky.kaipay.payment.api.dto.PaymentResponse;
import com.lky.kaipay.payment.domain.Payment;
import com.lky.kaipay.payment.domain.PaymentMethod;
import com.lky.kaipay.payment.domain.PaymentStatus;
import com.lky.kaipay.payment.domain.event.PaymentCapturedEvent;
import com.lky.kaipay.payment.domain.event.PaymentInitiatedEvent;
import com.lky.kaipay.payment.repository.PaymentMethodRepository;
import com.lky.kaipay.payment.repository.PaymentRepository;
import com.lky.kaipay.payment.service.acquirer.AcquirerAuthorizationResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final MerchantRepository merchantRepository;
    private final CustomerRepository customerRepository;
    private final PaymentMethodRepository paymentMethodRepository;
    private final IdempotencyService idempotencyService;
    private final PaymentEventOutboxRepository paymentEventOutboxRepository;
    private final ConsumerDeduplicationService consumerDeduplicationService;
    private final LedgerService ledgerService;
    private final ObjectMapper objectMapper;

    @Transactional
    public PaymentResponse createPayment(UUID merchantId, String idempotencyKey, CreatePaymentRequest request) {
        String requestHash = idempotencyService.computeRequestHash(request);

        // 1. Check if idempotent response already exists
        Optional<PaymentResponse> existingResponse = idempotencyService.getExistingResponse(merchantId, idempotencyKey, requestHash);
        if (existingResponse.isPresent()) {
            return existingResponse.get();
        }

        // 2. Validate Merchant
        Merchant merchant = merchantRepository.findById(merchantId)
                .orElseThrow(() -> new EntityNotFoundException("Merchant not found with ID: " + merchantId));

        if (!merchant.isActive()) {
            throw new InvalidRequestException("Merchant account is not active");
        }

        // 3. Validate Customer (Scoped to this Merchant)
        Customer customer = customerRepository.findByIdAndMerchantId(request.getCustomerId(), merchantId)
                .orElseThrow(() -> new EntityNotFoundException(
                        String.format("Customer %s not found for merchant %s", request.getCustomerId(), merchantId)
                ));

        // 4. Validate PaymentMethod if provided
        PaymentMethod paymentMethod = null;
        if (request.getPaymentMethodId() != null) {
            paymentMethod = paymentMethodRepository.findByIdAndCustomerId(request.getPaymentMethodId(), customer.getId())
                    .orElseThrow(() -> new EntityNotFoundException(
                            String.format("Payment method %s not found for customer %s", request.getPaymentMethodId(), customer.getId())
                    ));

            if (!paymentMethod.isActive()) {
                throw new InvalidRequestException("Payment method is not active");
            }
        }

        // 5. Build and Save Payment Aggregate
        Payment payment = Payment.builder()
                .merchant(merchant)
                .customer(customer)
                .paymentMethod(paymentMethod)
                .amountCents(request.getAmountCents())
                .currency(request.getCurrency())
                .status(PaymentStatus.CREATED)
                .idempotencyKey(idempotencyKey)
                .metadata(request.getMetadata() != null ? new HashMap<>(request.getMetadata()) : new HashMap<>())
                .build();

        Payment savedPayment;
        try {
            savedPayment = paymentRepository.save(payment);
            log.info("Created payment {} with status CREATED for merchant {}", savedPayment.getId(), merchantId);

            // 6. Build and Save Transactional Outbox Record for PaymentInitiatedEvent
            PaymentInitiatedEvent initiatedEvent = PaymentInitiatedEvent.fromPayment(savedPayment);
            EventEnvelope<PaymentInitiatedEvent> envelope = EventEnvelope.of(
                    "PaymentInitiatedEvent",
                    "PAYMENT",
                    savedPayment.getId().toString(),
                    merchantId,
                    initiatedEvent
            );

            String jsonPayload;
            try {
                jsonPayload = objectMapper.writeValueAsString(envelope);
            } catch (JsonProcessingException e) {
                log.error("Failed to serialize outbox event envelope for payment {}", savedPayment.getId(), e);
                throw new IllegalStateException("Failed to serialize outbox event envelope", e);
            }

            PaymentEventOutbox outboxRecord = PaymentEventOutbox.builder()
                    .aggregateType("PAYMENT")
                    .aggregateId(savedPayment.getId().toString())
                    .eventType("PaymentInitiatedEvent")
                    .payload(jsonPayload)
                    .headers(Map.of("merchantId", merchantId.toString()))
                    .status(PaymentEventOutboxStatus.PENDING)
                    .build();

            paymentEventOutboxRepository.save(outboxRecord);
        } catch (DataIntegrityViolationException e) {
            // Concurrent race condition: another thread inserted the exact same (merchant_id, idempotency_key)
            log.warn("Database unique constraint violation on idempotency key {} for merchant {}", idempotencyKey, merchantId);
            return idempotencyService.getExistingResponse(merchantId, idempotencyKey, requestHash)
                    .orElseThrow(() -> e);
        }

        PaymentResponse response = PaymentResponse.fromDomain(savedPayment);

        // 7. Record response in idempotency cache
        idempotencyService.recordResponse(merchantId, idempotencyKey, requestHash, 201, response);

        return response;
    }

    @Transactional(readOnly = true)
    public PaymentResponse getPayment(UUID merchantId, UUID paymentId) {
        Payment payment = paymentRepository.findByIdAndMerchantId(paymentId, merchantId)
                .orElseThrow(() -> new EntityNotFoundException(
                        String.format("Payment %s not found for merchant %s", paymentId, merchantId)
                ));
        return PaymentResponse.fromDomain(payment);
    }

    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<PaymentResponse> listPayments(
            UUID merchantId,
            PaymentStatus status,
            org.springframework.data.domain.Pageable pageable
    ) {
        if (!merchantRepository.existsById(merchantId)) {
            throw new EntityNotFoundException("Merchant not found with ID: " + merchantId);
        }

        org.springframework.data.domain.Page<Payment> page = (status != null)
                ? paymentRepository.findByMerchantIdAndStatus(merchantId, status, pageable)
                : paymentRepository.findByMerchantId(merchantId, pageable);

        return page.map(PaymentResponse::fromDomain);
    }

    @Transactional
    public PaymentResponse updateStatus(UUID merchantId, UUID paymentId, PaymentStatus targetStatus) {
        Payment payment = paymentRepository.findByIdAndMerchantId(paymentId, merchantId)
                .orElseThrow(() -> new EntityNotFoundException(
                        String.format("Payment %s not found for merchant %s", paymentId, merchantId)
                ));

        payment.transitionTo(targetStatus);
        Payment updatedPayment = paymentRepository.save(payment);
        log.info("Transitioned payment {} to {}", paymentId, targetStatus);
        return PaymentResponse.fromDomain(updatedPayment);
    }

    @Transactional
    public Payment transitionToProcessing(UUID paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new EntityNotFoundException("Payment not found with ID: " + paymentId));

        if (payment.getStatus() == PaymentStatus.CREATED) {
            payment.transitionTo(PaymentStatus.PROCESSING);
            Payment updated = paymentRepository.save(payment);
            log.info("Transitioned payment {} to PROCESSING", paymentId);
            return updated;
        }

        if (payment.getStatus() == PaymentStatus.PROCESSING) {
            log.debug("Payment {} is already in PROCESSING status, returning directly", paymentId);
            return payment;
        }

        return payment;
    }

    @Transactional
    public Payment completeAuthorization(UUID paymentId, AcquirerAuthorizationResult authResult) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new EntityNotFoundException("Payment not found with ID: " + paymentId));

        if (payment.getStatus() == PaymentStatus.PROCESSING) {
            if (authResult.isApproved()) {
                payment.setGatewayReference(authResult.getAuthorizationCode());
                payment.transitionTo(PaymentStatus.AUTHORIZED);
            } else {
                payment.setFailureCode(authResult.getDeclineCode());
                payment.setFailureMessage(authResult.getDeclineMessage());
                payment.transitionTo(PaymentStatus.DECLINED);
            }
            Payment updated = paymentRepository.save(payment);
            log.info("Completed authorization for payment {} with final status {}", paymentId, updated.getStatus());
            return updated;
        }

        return payment;
    }

    @Transactional
    public Payment completeAuthorizationWithDeduplication(
            UUID paymentId,
            AcquirerAuthorizationResult authResult,
            UUID eventId,
            String consumerGroup,
            String eventType
    ) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new EntityNotFoundException("Payment not found with ID: " + paymentId));

        if (payment.getStatus() == PaymentStatus.PROCESSING) {
            if (authResult.isApproved()) {
                payment.setGatewayReference(authResult.getAuthorizationCode());
                payment.transitionTo(PaymentStatus.AUTHORIZED);
            } else {
                payment.setFailureCode(authResult.getDeclineCode());
                payment.setFailureMessage(authResult.getDeclineMessage());
                payment.transitionTo(PaymentStatus.DECLINED);
            }
            Payment updated = paymentRepository.save(payment);
            consumerDeduplicationService.recordConsumed(eventId, consumerGroup, paymentId, eventType, updated.getStatus().name());
            log.info("Completed authorization with deduplication for payment {} with final status {}", paymentId, updated.getStatus());
            return updated;
        }

        if (payment.getStatus() == PaymentStatus.AUTHORIZED || payment.getStatus() == PaymentStatus.DECLINED) {
            if (!consumerDeduplicationService.isEventConsumed(eventId, consumerGroup)) {
                consumerDeduplicationService.recordConsumed(eventId, consumerGroup, paymentId, eventType, payment.getStatus().name());
            }
            return payment;
        }

        return payment;
    }

    @Transactional
    public Payment markPaymentFailed(UUID paymentId, String failureCode, String failureMessage) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new EntityNotFoundException("Payment not found with ID: " + paymentId));

        if (payment.getStatus() == PaymentStatus.CREATED || payment.getStatus() == PaymentStatus.PROCESSING) {
            payment.setFailureCode(failureCode);
            payment.setFailureMessage(failureMessage);
            payment.transitionTo(PaymentStatus.FAILED);
            Payment updated = paymentRepository.save(payment);
            log.info("Marked payment {} as FAILED with code={}", paymentId, failureCode);
            return updated;
        }

        return payment;
    }

    @Transactional
    public PaymentResponse capturePayment(UUID merchantId, UUID paymentId, String idempotencyKey) {
        Payment payment = paymentRepository.findByIdAndMerchantId(paymentId, merchantId)
                .orElseThrow(() -> new EntityNotFoundException(
                        String.format("Payment %s not found for merchant %s", paymentId, merchantId)
                ));

        if (payment.getStatus() == PaymentStatus.CAPTURED) {
            log.info("Payment {} is already CAPTURED, returning existing payment", paymentId);
            return PaymentResponse.fromEntity(payment);
        }

        if (payment.getStatus() != PaymentStatus.AUTHORIZED) {
            throw new InvalidRequestException("Cannot capture payment in status " + payment.getStatus());
        }

        payment.transitionTo(PaymentStatus.CAPTURED);
        Payment savedPayment = paymentRepository.save(payment);
        log.info("Captured payment {} for merchant {}", paymentId, merchantId);

        PaymentCapturedEvent capturedEvent = PaymentCapturedEvent.builder()
                .paymentId(savedPayment.getId())
                .merchantId(merchantId)
                .amountCents(savedPayment.getAmountCents())
                .currency(savedPayment.getCurrency())
                .capturedAt(Instant.now())
                .build();

        EventEnvelope<PaymentCapturedEvent> envelope = EventEnvelope.of(
                "PaymentCapturedEvent",
                "PAYMENT",
                savedPayment.getId().toString(),
                merchantId,
                capturedEvent
        );

        String jsonPayload;
        try {
            jsonPayload = objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize outbox event envelope for payment capture {}", savedPayment.getId(), e);
            throw new IllegalStateException("Failed to serialize outbox event envelope", e);
        }

        PaymentEventOutbox outboxRecord = PaymentEventOutbox.builder()
                .aggregateType("PAYMENT")
                .aggregateId(savedPayment.getId().toString())
                .eventType("PaymentCapturedEvent")
                .payload(jsonPayload)
                .headers(Map.of("merchantId", merchantId.toString()))
                .status(PaymentEventOutboxStatus.PENDING)
                .build();

        paymentEventOutboxRepository.save(outboxRecord);

        ledgerService.recordPaymentCapture(savedPayment, envelope.getEventId());

        return PaymentResponse.fromEntity(savedPayment);
    }
}
