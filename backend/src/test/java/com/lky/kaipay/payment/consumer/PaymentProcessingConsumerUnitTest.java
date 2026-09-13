package com.lky.kaipay.payment.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lky.kaipay.common.exception.GatewayTimeoutException;
import com.lky.kaipay.common.exception.GatewayUnavailableException;
import com.lky.kaipay.consumer.service.ConsumerDeduplicationService;
import com.lky.kaipay.dlt.domain.DeadLetterEvent;
import com.lky.kaipay.dlt.repository.DeadLetterEventRepository;
import com.lky.kaipay.payment.domain.Payment;
import com.lky.kaipay.payment.service.PaymentService;
import com.lky.kaipay.payment.service.acquirer.AcquirerAuthorizationResult;
import com.lky.kaipay.payment.service.acquirer.MockBankAcquirerClient;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentProcessingConsumer Unit Tests")
class PaymentProcessingConsumerUnitTest {

    @Mock
    private PaymentService paymentService;
    @Mock
    private MockBankAcquirerClient mockBankAcquirerClient;
    @Mock
    private ConsumerDeduplicationService consumerDeduplicationService;
    @Mock
    private DeadLetterEventRepository deadLetterEventRepository;
    @Mock
    private Acknowledgment acknowledgment;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private PaymentProcessingConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new PaymentProcessingConsumer(
                paymentService,
                mockBankAcquirerClient,
                consumerDeduplicationService,
                deadLetterEventRepository,
                objectMapper
        );
    }

    @Nested
    @DisplayName("processPaymentRequest Tests")
    class ProcessPaymentRequestTests {

        @Test
        @DisplayName("Should acknowledge and return early if event is already consumed (deduplication hit)")
        void fastPreCheckDeduplicationHit() {
            UUID eventId = UUID.randomUUID();
            UUID paymentId = UUID.randomUUID();
            String payload = String.format("{\"eventId\":\"%s\",\"payload\":{\"paymentId\":\"%s\",\"amountCents\":5000}}",
                    eventId, paymentId);

            when(consumerDeduplicationService.isEventConsumed(eventId, "kaipay-payment-processor-group")).thenReturn(true);

            ConsumerRecord<String, String> record = new ConsumerRecord<>("kaipay.payment.requests", 0, 1L, paymentId.toString(), payload);
            consumer.processPaymentRequest(record, acknowledgment);

            verify(acknowledgment).acknowledge();
            verify(paymentService, never()).transitionToProcessing(any());
            verify(mockBankAcquirerClient, never()).authorize(any(), anyLong(), anyString());
            verify(paymentService, never()).completeAuthorizationWithDeduplication(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("Should execute two-transaction flow when event is not consumed")
        void standardTwoTransactionFlow() {
            UUID eventId = UUID.randomUUID();
            UUID paymentId = UUID.randomUUID();
            String payload = String.format("{\"eventId\":\"%s\",\"eventType\":\"PaymentInitiatedEvent\",\"payload\":{\"paymentId\":\"%s\",\"amountCents\":5000,\"currency\":\"USD\"}}",
                    eventId, paymentId);

            when(consumerDeduplicationService.isEventConsumed(eventId, "kaipay-payment-processor-group")).thenReturn(false);
            when(paymentService.transitionToProcessing(paymentId)).thenReturn(mock(Payment.class));
            AcquirerAuthorizationResult authResult = AcquirerAuthorizationResult.approved("AUTH-1234");
            when(mockBankAcquirerClient.authorize(paymentId, 5000L, "USD")).thenReturn(authResult);

            ConsumerRecord<String, String> record = new ConsumerRecord<>("kaipay.payment.requests", 0, 1L, paymentId.toString(), payload);
            consumer.processPaymentRequest(record, acknowledgment);

            verify(paymentService).transitionToProcessing(paymentId);
            verify(mockBankAcquirerClient).authorize(paymentId, 5000L, "USD");
            verify(paymentService).completeAuthorizationWithDeduplication(
                    eq(paymentId),
                    eq(authResult),
                    eq(eventId),
                    eq("kaipay-payment-processor-group"),
                    eq("PaymentInitiatedEvent")
            );
            verify(acknowledgment).acknowledge();
        }

        @Test
        @DisplayName("Should rethrow GatewayTimeoutException to trigger non-blocking retry")
        void rethrowGatewayTimeoutException() {
            UUID eventId = UUID.randomUUID();
            UUID paymentId = UUID.randomUUID();
            String payload = String.format("{\"eventId\":\"%s\",\"payload\":{\"paymentId\":\"%s\",\"amountCents\":5000}}",
                    eventId, paymentId);

            when(consumerDeduplicationService.isEventConsumed(eventId, "kaipay-payment-processor-group")).thenReturn(false);
            when(paymentService.transitionToProcessing(paymentId)).thenReturn(mock(Payment.class));
            when(mockBankAcquirerClient.authorize(paymentId, 5000L, "USD"))
                    .thenThrow(new GatewayTimeoutException("Gateway read timed out"));

            ConsumerRecord<String, String> record = new ConsumerRecord<>("kaipay.payment.requests", 0, 1L, paymentId.toString(), payload);

            assertThatThrownBy(() -> consumer.processPaymentRequest(record, acknowledgment))
                    .isInstanceOf(GatewayTimeoutException.class)
                    .hasMessageContaining("Gateway read timed out");

            verify(acknowledgment, never()).acknowledge();
        }

        @Test
        @DisplayName("Should rethrow GatewayUnavailableException to trigger non-blocking retry")
        void rethrowGatewayUnavailableException() {
            UUID eventId = UUID.randomUUID();
            UUID paymentId = UUID.randomUUID();
            String payload = String.format("{\"eventId\":\"%s\",\"payload\":{\"paymentId\":\"%s\",\"amountCents\":5000}}",
                    eventId, paymentId);

            when(consumerDeduplicationService.isEventConsumed(eventId, "kaipay-payment-processor-group")).thenReturn(false);
            when(paymentService.transitionToProcessing(paymentId)).thenReturn(mock(Payment.class));
            when(mockBankAcquirerClient.authorize(paymentId, 5000L, "USD"))
                    .thenThrow(new GatewayUnavailableException("Gateway 503 unavailable"));

            ConsumerRecord<String, String> record = new ConsumerRecord<>("kaipay.payment.requests", 0, 1L, paymentId.toString(), payload);

            assertThatThrownBy(() -> consumer.processPaymentRequest(record, acknowledgment))
                    .isInstanceOf(GatewayUnavailableException.class)
                    .hasMessageContaining("Gateway 503 unavailable");

            verify(acknowledgment, never()).acknowledge();
        }

        @Test
        @DisplayName("Should throw RuntimeException on poison pill missing paymentId")
        void throwFatalOnPoisonPill() {
            String payload = "{\"somethingElse\":\"invalid\"}";
            ConsumerRecord<String, String> record = new ConsumerRecord<>("kaipay.payment.requests", 0, 1L, "key-1", payload);

            assertThatThrownBy(() -> consumer.processPaymentRequest(record, acknowledgment))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Fatal payment event processing failed");

            verify(acknowledgment, never()).acknowledge();
        }
    }

    @Nested
    @DisplayName("handleDltMessage Tests")
    class HandleDltMessageTests {

        @Test
        @DisplayName("Should mark payment failed and save dead letter event record")
        void handleDltMessageWithValidPaymentId() {
            UUID eventId = UUID.randomUUID();
            UUID paymentId = UUID.randomUUID();
            String payload = String.format("{\"eventId\":\"%s\",\"payload\":{\"paymentId\":\"%s\"}}", eventId, paymentId);

            ConsumerRecord<String, String> record = new ConsumerRecord<>("kaipay.payment.requests-dlt", 1, 200L, paymentId.toString(), payload);

            consumer.handleDltMessage(
                    record,
                    "org.springframework.kafka.listener.ListenerExecutionFailedException",
                    "com.lky.kaipay.common.exception.GatewayTimeoutException",
                    "Exhausted 3 retries",
                    acknowledgment
            );

            verify(paymentService).markPaymentFailed(paymentId, "DLT_ROUTED", "Exhausted 3 retries");

            ArgumentCaptor<DeadLetterEvent> captor = ArgumentCaptor.forClass(DeadLetterEvent.class);
            verify(deadLetterEventRepository).save(captor.capture());
            DeadLetterEvent saved = captor.getValue();
            assertThat(saved.getOriginalTopic()).isEqualTo("kaipay.payment.requests-dlt");
            assertThat(saved.getOriginalPartition()).isEqualTo(1);
            assertThat(saved.getOriginalOffset()).isEqualTo(200L);
            assertThat(saved.getEventId()).isEqualTo(eventId);
            assertThat(saved.getPaymentId()).isEqualTo(paymentId);
            assertThat(saved.getExceptionClass()).isEqualTo("com.lky.kaipay.common.exception.GatewayTimeoutException");
            assertThat(saved.getFailureMessage()).isEqualTo("Exhausted 3 retries");
            assertThat(saved.getPayload()).isEqualTo(payload);

            verify(acknowledgment).acknowledge();
        }

        @Test
        @DisplayName("Should handle unparseable payload gracefully and still save DeadLetterEvent")
        void handleDltMessageUnparseablePayload() {
            String unparseablePayload = "not-json-content";
            ConsumerRecord<String, String> record = new ConsumerRecord<>("kaipay.payment.requests-dlt", 0, 50L, "bad-key", unparseablePayload);

            consumer.handleDltMessage(
                    record,
                    "java.lang.IllegalArgumentException",
                    null,
                    "Cannot parse message",
                    acknowledgment
            );

            verify(paymentService, never()).markPaymentFailed(any(), any(), any());

            ArgumentCaptor<DeadLetterEvent> captor = ArgumentCaptor.forClass(DeadLetterEvent.class);
            verify(deadLetterEventRepository).save(captor.capture());
            DeadLetterEvent saved = captor.getValue();
            assertThat(saved.getOriginalTopic()).isEqualTo("kaipay.payment.requests-dlt");
            assertThat(saved.getPaymentId()).isNull();
            assertThat(saved.getEventId()).isNull();
            assertThat(saved.getPayload()).contains(unparseablePayload);

            verify(acknowledgment).acknowledge();
        }
    }
}
