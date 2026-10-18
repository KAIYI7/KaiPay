package com.lky.kaipay.payment.service;

import com.lky.kaipay.consumer.domain.ConsumedEvent;
import com.lky.kaipay.consumer.repository.ConsumedEventRepository;
import com.lky.kaipay.payment.domain.Payment;
import com.lky.kaipay.payment.domain.PaymentStatus;
import com.lky.kaipay.payment.repository.PaymentRepository;
import com.lky.kaipay.payment.service.acquirer.AcquirerAuthorizationResult;
import com.lky.kaipay.payment.service.acquirer.AuthorizationRequest;
import com.lky.kaipay.payment.service.acquirer.SimulatedAuthorizationOperation;
import com.lky.kaipay.payment.service.acquirer.SimulatedAuthorizationOperationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;

/** Short payment transactions; the independently committed simulator runs between these calls. */
@Service
@Slf4j
@RequiredArgsConstructor
public class AuthorizationRecoveryService {
    private final PaymentRepository payments;
    private final SimulatedAuthorizationOperationRepository operations;
    private final ConsumedEventRepository markers;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<AuthorizationRequest> prepareAuthorization(AuthorizationDelivery delivery, String group) {
        Payment payment = loadAndValidate(delivery, group);
        AuthorizationRequest request = request(payment);
        SimulatedAuthorizationOperation operation = operations.findById(payment.getId()).orElse(null);
        if (operation != null) operation.validateRequest(request);
        ConsumedEvent marker = markers.findByIdEventIdAndIdConsumerGroup(delivery.eventId(), group).orElse(null);
        if (marker != null) validateMarker(marker, delivery, payment);

        if (payment.getStatus() == PaymentStatus.CREATED) {
            require(operation == null && marker == null, "Inconsistent authorization intent");
            operations.save(SimulatedAuthorizationOperation.intent(request));
            payment.transitionTo(PaymentStatus.PROCESSING);
            return Optional.of(request);
        }
        if (payment.getStatus() == PaymentStatus.PROCESSING) {
            require(operation != null && marker == null, "Ambiguous legacy authorization");
            if (!com.lky.kaipay.common.MoneyCurrency.MYR.equals(payment.getCurrency())) {
                require(operation.result() != null, "Unresolved historical non-MYR authorization");
            }
            return Optional.of(request);
        }

        String outcome = validateFinal(payment, operation);
        recordMarker(delivery, group, payment, outcome);
        return Optional.empty();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completeAuthorization(AuthorizationDelivery delivery, String group, AcquirerAuthorizationResult returned) {
        Payment payment = loadAndValidate(delivery, group);
        SimulatedAuthorizationOperation operation = operations.findById(payment.getId())
                .orElseThrow(() -> new IllegalStateException("Missing authorization intent"));
        operation.validateRequest(request(payment));
        AcquirerAuthorizationResult authoritative = operation.result();
        require(authoritative != null && authoritative.equals(returned), "Conflicting authorization result");

        if (payment.getStatus() == PaymentStatus.PROCESSING) {
            if (authoritative.isRejectedNoAcceptance()) {
                payment.setGatewayReference(null);
                payment.setFailureCode(authoritative.getFailureCode());
                payment.setFailureMessage(authoritative.getFailureMessage());
                payment.transitionTo(PaymentStatus.FAILED);
            } else if (authoritative.isApproved()) {
                payment.setGatewayReference(authoritative.getAuthorizationCode());
                payment.setFailureCode(null);
                payment.setFailureMessage(null);
                payment.transitionTo(PaymentStatus.AUTHORIZED);
            } else {
                payment.setGatewayReference(null);
                payment.setFailureCode(authoritative.getDeclineCode());
                payment.setFailureMessage(authoritative.getDeclineMessage());
                payment.transitionTo(PaymentStatus.DECLINED);
            }
        }
        String outcome = validateFinal(payment, operation);
        recordMarker(delivery, group, payment, outcome);
        log.info("Completed authorization with deduplication for payment {}", payment.getId());
    }

    private Payment loadAndValidate(AuthorizationDelivery d, String group) {
        require(group != null && !group.isBlank(), "Invalid consumer group");
        Payment p = payments.findByIdForUpdate(d.paymentId())
                .orElseThrow(() -> new IllegalArgumentException("Unknown authorization payment"));
        require(p.getAmountCents() == d.amountCents() && p.getCurrency().equals(d.currency())
                        && sameIfProvided(d.merchantId(), p.getMerchant().getId())
                        && sameIfProvided(d.envelopeMerchantId(), p.getMerchant().getId())
                        && sameIfProvided(d.customerId(), p.getCustomer().getId())
                        && sameIfProvided(d.idempotencyKey(), p.getIdempotencyKey())
                        && sameIfProvided(d.aggregateId(), p.getId()), "Conflicting authorization request");
        return p;
    }

    private AuthorizationRequest request(Payment p) {
        return new AuthorizationRequest(p.getId(), p.getMerchant().getId(), p.getAmountCents(), p.getCurrency());
    }

    private String validateFinal(Payment p, SimulatedAuthorizationOperation operation) {
        String outcome = authorizationStatus(p.getStatus());
        require(outcome != null, "Ineligible authorization payment");
        if (operation == null) {
            // Historical final payments are handled without inventing provider acceptance records.
            if ("AUTHORIZED".equals(outcome)) require(nonblank(p.getGatewayReference()), "Missing legacy reference");
            else require(nonblank(p.getFailureCode()), "Missing legacy failure evidence");
            return outcome;
        }
        AcquirerAuthorizationResult result = operation.result();
        require(result != null, "Unresolved terminal authorization");
        if ("AUTHORIZED".equals(outcome)) {
            require(result.isApproved() && !result.isRejectedNoAcceptance()
                    && Objects.equals(p.getGatewayReference(), result.getAuthorizationCode())
                    && p.getFailureCode() == null && p.getFailureMessage() == null, "Conflicting final authorization");
        } else if ("DECLINED".equals(outcome)) {
            require(!result.isApproved() && !result.isRejectedNoAcceptance()
                    && p.getGatewayReference() == null
                    && Objects.equals(p.getFailureCode(), result.getDeclineCode())
                    && Objects.equals(p.getFailureMessage(), result.getDeclineMessage()), "Conflicting final decline");
        } else {
            require(result.isRejectedNoAcceptance()
                    && p.getGatewayReference() == null
                    && Objects.equals(p.getFailureCode(), result.getFailureCode())
                    && Objects.equals(p.getFailureMessage(), result.getFailureMessage()), "Conflicting final rejection");
        }
        return outcome;
    }

    private void recordMarker(AuthorizationDelivery d, String group, Payment p, String outcome) {
        markers.insertIfAbsent(d.eventId(), group, p.getId(), AuthorizationDelivery.EVENT_TYPE, outcome);
        // Separate statement sees a committed winner even if INSERT's snapshot could not.
        ConsumedEvent marker = markers.findByIdEventIdAndIdConsumerGroup(d.eventId(), group).orElseThrow();
        validateMarker(marker, d, p);
        require(outcome.equals(marker.getStatus()), "Conflicting authorization marker");
    }

    private void validateMarker(ConsumedEvent marker, AuthorizationDelivery d, Payment p) {
        require(marker.getPaymentId().equals(d.paymentId())
                        && AuthorizationDelivery.EVENT_TYPE.equals(marker.getEventType())
                        && Objects.equals(authorizationStatus(p.getStatus()), marker.getStatus()),
                "Conflicting authorization marker");
    }

    private String authorizationStatus(PaymentStatus status) {
        return switch (status) {
            case AUTHORIZED, CAPTURED, REFUND_PENDING, PARTIALLY_REFUNDED, REFUNDED -> "AUTHORIZED";
            case DECLINED -> "DECLINED";
            case FAILED -> "FAILED";
            default -> null;
        };
    }

    private static boolean sameIfProvided(Object supplied, Object persisted) {
        return supplied == null || supplied.equals(persisted);
    }
    private static boolean nonblank(String value) { return value != null && !value.isBlank(); }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
