package com.lky.kaipay.payment.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lky.kaipay.common.event.EventEnvelope;
import com.lky.kaipay.common.exception.GatewayTimeoutException;
import com.lky.kaipay.common.exception.GatewayUnavailableException;
import com.lky.kaipay.common.exception.NonRetryableGatewayException;
import com.lky.kaipay.dlt.domain.DeadLetterEvent;
import com.lky.kaipay.dlt.repository.DeadLetterEventRepository;
import com.lky.kaipay.payment.domain.event.PaymentCapturedEvent;
import com.lky.kaipay.payment.service.AuthorizationDelivery;
import com.lky.kaipay.payment.service.AuthorizationRecoveryService;
import com.lky.kaipay.payment.service.acquirer.AcquirerAuthorizationResult;
import com.lky.kaipay.payment.service.acquirer.AuthorizationRequest;
import com.lky.kaipay.payment.service.acquirer.MockBankAcquirerClient;
import com.lky.kaipay.refund.domain.event.PaymentRefundedEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentProcessingConsumerUnitTest {
    private static final String GROUP = "kaipay-payment-processor-group";
    @Mock private AuthorizationRecoveryService recovery;
    @Mock private MockBankAcquirerClient acquirer;
    @Mock private DeadLetterEventRepository deadLetters;
    @Mock private Acknowledgment ack;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private PaymentProcessingConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new PaymentProcessingConsumer(recovery, acquirer, deadLetters, mapper);
    }

    @Test
    void compatibleReplayAcknowledgesOnlyAfterValidatedPreparation() throws Exception {
        var record = initiated();
        AuthorizationDelivery delivery = AuthorizationDelivery.fromJson(mapper.readTree(record.value()));
        when(recovery.prepareAuthorization(delivery, GROUP)).thenReturn(Optional.empty());
        consumer.processPaymentRequest(record, ack);
        var order = inOrder(recovery, ack);
        order.verify(recovery).prepareAuthorization(delivery, GROUP);
        order.verify(ack).acknowledge();
        verifyNoInteractions(acquirer, deadLetters);
    }

    @Test
    void approvalPreparesAuthorizesAndCompletesBeforeAcknowledgment() throws Exception {
        var record = initiated();
        var delivery = AuthorizationDelivery.fromJson(mapper.readTree(record.value()));
        var request = new AuthorizationRequest(UUID.fromString(record.key()), UUID.randomUUID(), 5000, "MYR");
        var result = AcquirerAuthorizationResult.approved("AUTH-1234");
        when(recovery.prepareAuthorization(delivery, GROUP)).thenReturn(Optional.of(request));
        when(acquirer.authorize(request)).thenReturn(result);
        consumer.processPaymentRequest(record, ack);
        var order = inOrder(recovery, acquirer, ack);
        order.verify(recovery).prepareAuthorization(delivery, GROUP);
        order.verify(acquirer).authorize(request);
        order.verify(recovery).completeAuthorization(delivery, GROUP, result);
        order.verify(ack).acknowledge();
    }

    @ParameterizedTest
    @ValueSource(strings = {"timeout", "unavailable"})
    void gatewayTransientFailureLeavesCompletionAndAcknowledgmentForRetry(String kind) throws Exception {
        var record = initiated();
        var request = new AuthorizationRequest(UUID.fromString(record.key()), UUID.randomUUID(), 5000, "MYR");
        RuntimeException failure = kind.equals("timeout") ? new GatewayTimeoutException("timeout")
                : new GatewayUnavailableException("unavailable");
        when(recovery.prepareAuthorization(any(), eq(GROUP))).thenReturn(Optional.of(request));
        when(acquirer.authorize(request)).thenThrow(failure);
        assertThatThrownBy(() -> consumer.processPaymentRequest(record, ack)).isSameAs(failure);
        verify(recovery, never()).completeAuthorization(any(), any(), any());
        verifyNoInteractions(ack, deadLetters);
    }

    @ParameterizedTest
    @ValueSource(strings = {"08006", "08007", "23505", "non-sql", "no-cause"})
    void transactionSystemFailureRetriesOnlySqlConnectionFailures(String sqlState) {
        var record = initiated();
        Throwable cause = switch (sqlState) {
            case "non-sql" -> new IllegalStateException("transaction callback failed");
            case "no-cause" -> null;
            default -> new java.sql.SQLException("injected commit failure", sqlState);
        };
        var failure = new org.springframework.transaction.TransactionSystemException("commit failed", cause);
        when(recovery.prepareAuthorization(any(), eq(GROUP))).thenThrow(failure);

        var assertion = assertThatThrownBy(() -> consumer.processPaymentRequest(record, ack));
        if (sqlState.startsWith("08")) {
            assertion.isInstanceOf(com.lky.kaipay.common.exception.AuthorizationPersistenceException.class)
                    .hasCause(failure);
        } else {
            assertion.isSameAs(failure);
        }
        verifyNoInteractions(acquirer, ack, deadLetters);
    }

    @Test
    void definitiveRejectionFinalizesBeforeNonRetryableRouting() throws Exception {
        var record = initiated();
        var request = new AuthorizationRequest(UUID.fromString(record.key()), UUID.randomUUID(), 5000, "MYR");
        var result = AcquirerAuthorizationResult.builder().rejectedNoAcceptance(true)
                .failureCode("SIMULATED_NO_ACCEPTANCE").failureMessage("rejected").build();
        when(recovery.prepareAuthorization(any(), eq(GROUP))).thenReturn(Optional.of(request));
        when(acquirer.authorize(request)).thenReturn(result);
        assertThatThrownBy(() -> consumer.processPaymentRequest(record, ack))
                .isInstanceOf(NonRetryableGatewayException.class);
        var order = inOrder(recovery, acquirer);
        order.verify(recovery).prepareAuthorization(any(), eq(GROUP));
        order.verify(acquirer).authorize(request);
        order.verify(recovery).completeAuthorization(any(), eq(GROUP), eq(result));
        verifyNoInteractions(ack);
    }

    @Test
    void completionFailureDoesNotAcknowledge() throws Exception {
        var record = initiated();
        var request = new AuthorizationRequest(UUID.fromString(record.key()), UUID.randomUUID(), 5000, "MYR");
        var result = AcquirerAuthorizationResult.approved("AUTH");
        when(recovery.prepareAuthorization(any(), eq(GROUP))).thenReturn(Optional.of(request));
        when(acquirer.authorize(request)).thenReturn(result);
        doThrow(new IllegalStateException("completion failed")).when(recovery).completeAuthorization(any(), any(), any());
        assertThatThrownBy(() -> consumer.processPaymentRequest(record, ack)).hasRootCauseMessage("completion failed");
        verifyNoInteractions(ack);
    }

    @Test
    void transientCompletionFailurePrecedesDefinitiveRejectionDiagnostic() throws Exception {
        var record = initiated();
        var request = new AuthorizationRequest(UUID.fromString(record.key()), UUID.randomUUID(), 5000, "MYR");
        var result = AcquirerAuthorizationResult.builder().rejectedNoAcceptance(true)
                .failureCode("SIMULATED_NO_ACCEPTANCE").failureMessage("rejected").build();
        when(recovery.prepareAuthorization(any(), eq(GROUP))).thenReturn(Optional.of(request));
        when(acquirer.authorize(request)).thenReturn(result);
        var failure = new org.springframework.dao.CannotAcquireLockException("temporary completion conflict");
        doThrow(failure).when(recovery).completeAuthorization(any(), any(), any());
        assertThatThrownBy(() -> consumer.processPaymentRequest(record, ack))
                .isInstanceOf(com.lky.kaipay.common.exception.AuthorizationPersistenceException.class)
                .hasCause(failure);
        verifyNoInteractions(ack, deadLetters);
    }

    @Test
    void lifecycleNotificationsNeverAuthorizeOrPrepare() throws Exception {
        UUID paymentId = UUID.randomUUID();
        var capture = EventEnvelope.of("PaymentCapturedEvent", "PAYMENT", paymentId.toString(), UUID.randomUUID(),
                PaymentCapturedEvent.builder().paymentId(paymentId).amountCents(5000L).currency("MYR").build());
        var refund = EventEnvelope.of("PaymentRefundedEvent", "REFUND", UUID.randomUUID().toString(), UUID.randomUUID(),
                PaymentRefundedEvent.builder().paymentId(paymentId).refundId(UUID.randomUUID())
                        .refundAmountCents(2000L).currency("MYR").build());
        consumer.processPaymentRequest(record(mapper.writeValueAsString(capture), paymentId), ack);
        consumer.processPaymentRequest(record(mapper.writeValueAsString(refund), paymentId), ack);
        verify(ack, org.mockito.Mockito.times(2)).acknowledge();
        verifyNoInteractions(recovery, acquirer, deadLetters);
    }

    @Test
    void unknownEventTypeRejectsBeforeAuthorizationFields() throws Exception {
        UUID paymentId = UUID.randomUUID();
        var envelope = EventEnvelope.of("UnsupportedPaymentEvent", "PAYMENT", paymentId.toString(), UUID.randomUUID(),
                Map.of("paymentId", paymentId));
        assertThatThrownBy(() -> consumer.processPaymentRequest(record(mapper.writeValueAsString(envelope), paymentId), ack))
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .hasRootCauseMessage("Unsupported payment event type: UnsupportedPaymentEvent");
        verifyNoInteractions(recovery, acquirer, deadLetters, ack);
    }

    @Test
    void missingPaymentIdRejectsWithoutSideEffects() {
        assertThatThrownBy(() -> consumer.processPaymentRequest(record("{}", UUID.randomUUID()), ack))
                .hasMessage("Fatal payment event processing failed");
        verifyNoInteractions(recovery, acquirer, deadLetters, ack);
    }

    @Test
    void dltSavesEvidenceWithoutInvokingPaymentRecovery() {
        UUID eventId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        String payload = "{\"eventId\":\"" + eventId + "\",\"payload\":{\"paymentId\":\"" + paymentId + "\"}}";
        var record = new ConsumerRecord<>("kaipay.payment.requests-dlt", 1, 200L, paymentId.toString(), payload);
        consumer.handleDltMessage(record, "ListenerFailure", "GatewayTimeoutException", "Exhausted retries", ack);
        var captured = ArgumentCaptor.forClass(DeadLetterEvent.class);
        verify(deadLetters).save(captured.capture());
        var saved = captured.getValue();
        assertThat(saved.getOriginalTopic()).isEqualTo(record.topic());
        assertThat(saved.getOriginalPartition()).isEqualTo(1);
        assertThat(saved.getOriginalOffset()).isEqualTo(200L);
        assertThat(saved.getEventId()).isEqualTo(eventId);
        assertThat(saved.getPaymentId()).isEqualTo(paymentId);
        assertThat(saved.getExceptionClass()).isEqualTo("GatewayTimeoutException");
        assertThat(saved.getFailureMessage()).isEqualTo("Exhausted retries");
        assertThat(saved.getPayload()).isEqualTo(payload);
        verify(ack).acknowledge();
        verifyNoInteractions(recovery, acquirer);
    }

    @Test
    void unparseableDltStillPersistsEvidence() {
        consumer.handleDltMessage(record("not-json-content", UUID.randomUUID()), "IllegalArgumentException", null, "Cannot parse", ack);
        var captured = ArgumentCaptor.forClass(DeadLetterEvent.class);
        verify(deadLetters).save(captured.capture());
        assertThat(captured.getValue().getPaymentId()).isNull();
        assertThat(captured.getValue().getEventId()).isNull();
        assertThat(captured.getValue().getPayload()).contains("not-json-content");
        verify(ack).acknowledge();
        verifyNoInteractions(recovery, acquirer);
    }

    private ConsumerRecord<String, String> initiated() {
        UUID paymentId = UUID.randomUUID();
        return record("{\"eventId\":\"" + UUID.randomUUID() + "\",\"payload\":{\"paymentId\":\"" + paymentId
                + "\",\"amountCents\":5000,\"currency\":\"MYR\"}}", paymentId);
    }

    private ConsumerRecord<String, String> record(String payload, UUID id) {
        return new ConsumerRecord<>("kaipay.payment.requests", 0, 1, id.toString(), payload);
    }
}
