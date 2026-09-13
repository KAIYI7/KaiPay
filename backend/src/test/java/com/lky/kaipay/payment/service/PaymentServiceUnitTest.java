package com.lky.kaipay.payment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lky.kaipay.common.exception.EntityNotFoundException;
import com.lky.kaipay.consumer.service.ConsumerDeduplicationService;
import com.lky.kaipay.customer.domain.Customer;
import com.lky.kaipay.customer.repository.CustomerRepository;
import com.lky.kaipay.merchant.repository.MerchantRepository;
import com.lky.kaipay.outbox.repository.PaymentEventOutboxRepository;
import com.lky.kaipay.payment.domain.Payment;
import com.lky.kaipay.payment.domain.PaymentStatus;
import com.lky.kaipay.payment.repository.PaymentMethodRepository;
import com.lky.kaipay.payment.repository.PaymentRepository;
import com.lky.kaipay.payment.service.acquirer.AcquirerAuthorizationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentService Unit Tests")
class PaymentServiceUnitTest {

    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private MerchantRepository merchantRepository;
    @Mock
    private CustomerRepository customerRepository;
    @Mock
    private PaymentMethodRepository paymentMethodRepository;
    @Mock
    private IdempotencyService idempotencyService;
    @Mock
    private PaymentEventOutboxRepository paymentEventOutboxRepository;
    @Mock
    private ConsumerDeduplicationService consumerDeduplicationService;
    @Mock
    private com.lky.kaipay.ledger.service.LedgerService ledgerService;
    @Mock
    private ObjectMapper objectMapper;

    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        paymentService = new PaymentService(
                paymentRepository,
                merchantRepository,
                customerRepository,
                paymentMethodRepository,
                idempotencyService,
                paymentEventOutboxRepository,
                consumerDeduplicationService,
                ledgerService,
                objectMapper
        );
    }

    @Nested
    @DisplayName("transitionToProcessing Tests")
    class TransitionToProcessingTests {

        @Test
        @DisplayName("Should transition payment from CREATED to PROCESSING and save")
        void transitionFromCreatedToProcessing() {
            UUID paymentId = UUID.randomUUID();
            Payment payment = Payment.builder()
                    .id(paymentId)
                    .amountCents(5000L)
                    .currency("USD")
                    .status(PaymentStatus.CREATED)
                    .idempotencyKey("key-1")
                    .build();

            when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));
            when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));

            Payment result = paymentService.transitionToProcessing(paymentId);

            assertThat(result.getStatus()).isEqualTo(PaymentStatus.PROCESSING);
            verify(paymentRepository).save(payment);
        }

        @Test
        @DisplayName("Should return payment directly without saving if already in PROCESSING")
        void noOpIfAlreadyProcessing() {
            UUID paymentId = UUID.randomUUID();
            Payment payment = Payment.builder()
                    .id(paymentId)
                    .amountCents(5000L)
                    .currency("USD")
                    .status(PaymentStatus.PROCESSING)
                    .idempotencyKey("key-1")
                    .build();

            when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));

            Payment result = paymentService.transitionToProcessing(paymentId);

            assertThat(result.getStatus()).isEqualTo(PaymentStatus.PROCESSING);
            verify(paymentRepository, never()).save(any());
        }

        @Test
        @DisplayName("Should throw EntityNotFoundException when payment does not exist")
        void throwsWhenNotFound() {
            UUID paymentId = UUID.randomUUID();
            when(paymentRepository.findById(paymentId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> paymentService.transitionToProcessing(paymentId))
                    .isInstanceOf(EntityNotFoundException.class)
                    .hasMessageContaining("Payment not found with ID: " + paymentId);
        }
    }

    @Nested
    @DisplayName("completeAuthorization Tests")
    class CompleteAuthorizationTests {

        @Test
        @DisplayName("Should transition from PROCESSING to AUTHORIZED when approved")
        void completeAuthorizationApproved() {
            UUID paymentId = UUID.randomUUID();
            Payment payment = Payment.builder()
                    .id(paymentId)
                    .amountCents(5000L)
                    .currency("USD")
                    .status(PaymentStatus.PROCESSING)
                    .idempotencyKey("key-1")
                    .build();

            AcquirerAuthorizationResult authResult = AcquirerAuthorizationResult.approved("AUTH-ABC12345");

            when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));
            when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));

            Payment result = paymentService.completeAuthorization(paymentId, authResult);

            assertThat(result.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
            assertThat(result.getGatewayReference()).isEqualTo("AUTH-ABC12345");
            assertThat(result.getFailureCode()).isNull();
            verify(paymentRepository).save(payment);
        }

        @Test
        @DisplayName("Should transition from PROCESSING to DECLINED when declined")
        void completeAuthorizationDeclined() {
            UUID paymentId = UUID.randomUUID();
            Payment payment = Payment.builder()
                    .id(paymentId)
                    .amountCents(999900L)
                    .currency("USD")
                    .status(PaymentStatus.PROCESSING)
                    .idempotencyKey("key-1")
                    .build();

            AcquirerAuthorizationResult authResult = AcquirerAuthorizationResult.declined("INSUFFICIENT_FUNDS", "Not enough funds");

            when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));
            when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));

            Payment result = paymentService.completeAuthorization(paymentId, authResult);

            assertThat(result.getStatus()).isEqualTo(PaymentStatus.DECLINED);
            assertThat(result.getFailureCode()).isEqualTo("INSUFFICIENT_FUNDS");
            assertThat(result.getFailureMessage()).isEqualTo("Not enough funds");
            assertThat(result.getGatewayReference()).isNull();
            verify(paymentRepository).save(payment);
        }

        @Test
        @DisplayName("Should return payment directly without saving if already in AUTHORIZED")
        void noOpIfAlreadyAuthorized() {
            UUID paymentId = UUID.randomUUID();
            Payment payment = Payment.builder()
                    .id(paymentId)
                    .amountCents(5000L)
                    .currency("USD")
                    .status(PaymentStatus.AUTHORIZED)
                    .gatewayReference("AUTH-EXISTING")
                    .idempotencyKey("key-1")
                    .build();

            AcquirerAuthorizationResult authResult = AcquirerAuthorizationResult.approved("AUTH-NEW");

            when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));

            Payment result = paymentService.completeAuthorization(paymentId, authResult);

            assertThat(result.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
            assertThat(result.getGatewayReference()).isEqualTo("AUTH-EXISTING");
            verify(paymentRepository, never()).save(any());
        }

        @Test
        @DisplayName("Should throw EntityNotFoundException when payment not found")
        void throwsWhenNotFound() {
            UUID paymentId = UUID.randomUUID();
            AcquirerAuthorizationResult authResult = AcquirerAuthorizationResult.approved("AUTH-123");
            when(paymentRepository.findById(paymentId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> paymentService.completeAuthorization(paymentId, authResult))
                    .isInstanceOf(EntityNotFoundException.class)
                    .hasMessageContaining("Payment not found with ID: " + paymentId);
        }
    }

    @Nested
    @DisplayName("completeAuthorizationWithDeduplication Tests")
    class CompleteAuthorizationWithDeduplicationTests {

        @Test
        @DisplayName("Should transition from PROCESSING to AUTHORIZED and record ConsumedEvent when approved")
        void completeAuthorizationWithDeduplicationApproved() {
            UUID paymentId = UUID.randomUUID();
            UUID eventId = UUID.randomUUID();
            String consumerGroup = "test-group";
            String eventType = "PaymentInitiatedEvent";

            Payment payment = Payment.builder()
                    .id(paymentId)
                    .amountCents(5000L)
                    .currency("USD")
                    .status(PaymentStatus.PROCESSING)
                    .idempotencyKey("key-1")
                    .build();

            AcquirerAuthorizationResult authResult = AcquirerAuthorizationResult.approved("AUTH-XYZ999");

            when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));
            when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));

            Payment result = paymentService.completeAuthorizationWithDeduplication(paymentId, authResult, eventId, consumerGroup, eventType);

            assertThat(result.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
            assertThat(result.getGatewayReference()).isEqualTo("AUTH-XYZ999");
            verify(paymentRepository).save(payment);
            verify(consumerDeduplicationService).recordConsumed(eventId, consumerGroup, paymentId, eventType, "AUTHORIZED");
        }

        @Test
        @DisplayName("Should transition from PROCESSING to DECLINED and record ConsumedEvent when declined")
        void completeAuthorizationWithDeduplicationDeclined() {
            UUID paymentId = UUID.randomUUID();
            UUID eventId = UUID.randomUUID();
            String consumerGroup = "test-group";
            String eventType = "PaymentInitiatedEvent";

            Payment payment = Payment.builder()
                    .id(paymentId)
                    .amountCents(999900L)
                    .currency("USD")
                    .status(PaymentStatus.PROCESSING)
                    .idempotencyKey("key-1")
                    .build();

            AcquirerAuthorizationResult authResult = AcquirerAuthorizationResult.declined("CARD_DECLINED", "Card was declined");

            when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));
            when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));

            Payment result = paymentService.completeAuthorizationWithDeduplication(paymentId, authResult, eventId, consumerGroup, eventType);

            assertThat(result.getStatus()).isEqualTo(PaymentStatus.DECLINED);
            assertThat(result.getFailureCode()).isEqualTo("CARD_DECLINED");
            assertThat(result.getFailureMessage()).isEqualTo("Card was declined");
            verify(paymentRepository).save(payment);
            verify(consumerDeduplicationService).recordConsumed(eventId, consumerGroup, paymentId, eventType, "DECLINED");
        }

        @Test
        @DisplayName("Should ensure ConsumedEvent is recorded if payment already AUTHORIZED and event not consumed")
        void recordConsumedEventIfAlreadyAuthorizedAndNotConsumed() {
            UUID paymentId = UUID.randomUUID();
            UUID eventId = UUID.randomUUID();
            String consumerGroup = "test-group";
            String eventType = "PaymentInitiatedEvent";

            Payment payment = Payment.builder()
                    .id(paymentId)
                    .amountCents(5000L)
                    .currency("USD")
                    .status(PaymentStatus.AUTHORIZED)
                    .gatewayReference("AUTH-EXISTING")
                    .idempotencyKey("key-1")
                    .build();

            AcquirerAuthorizationResult authResult = AcquirerAuthorizationResult.approved("AUTH-NEW");

            when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));
            when(consumerDeduplicationService.isEventConsumed(eventId, consumerGroup)).thenReturn(false);

            Payment result = paymentService.completeAuthorizationWithDeduplication(paymentId, authResult, eventId, consumerGroup, eventType);

            assertThat(result.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
            verify(paymentRepository, never()).save(any());
            verify(consumerDeduplicationService).recordConsumed(eventId, consumerGroup, paymentId, eventType, "AUTHORIZED");
        }

        @Test
        @DisplayName("Should not record ConsumedEvent if already AUTHORIZED and already consumed")
        void noOpIfAlreadyAuthorizedAndConsumed() {
            UUID paymentId = UUID.randomUUID();
            UUID eventId = UUID.randomUUID();
            String consumerGroup = "test-group";
            String eventType = "PaymentInitiatedEvent";

            Payment payment = Payment.builder()
                    .id(paymentId)
                    .amountCents(5000L)
                    .currency("USD")
                    .status(PaymentStatus.AUTHORIZED)
                    .gatewayReference("AUTH-EXISTING")
                    .idempotencyKey("key-1")
                    .build();

            AcquirerAuthorizationResult authResult = AcquirerAuthorizationResult.approved("AUTH-NEW");

            when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));
            when(consumerDeduplicationService.isEventConsumed(eventId, consumerGroup)).thenReturn(true);

            Payment result = paymentService.completeAuthorizationWithDeduplication(paymentId, authResult, eventId, consumerGroup, eventType);

            assertThat(result.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
            verify(paymentRepository, never()).save(any());
            verify(consumerDeduplicationService, never()).recordConsumed(any(), any(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("markPaymentFailed Tests")
    class MarkPaymentFailedTests {

        @Test
        @DisplayName("Should mark CREATED payment as FAILED")
        void markCreatedAsFailed() {
            UUID paymentId = UUID.randomUUID();
            Payment payment = Payment.builder()
                    .id(paymentId)
                    .amountCents(5000L)
                    .currency("USD")
                    .status(PaymentStatus.CREATED)
                    .idempotencyKey("key-1")
                    .build();

            when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));
            when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));

            Payment result = paymentService.markPaymentFailed(paymentId, "DLT_ROUTED", "Exhausted retries");

            assertThat(result.getStatus()).isEqualTo(PaymentStatus.FAILED);
            assertThat(result.getFailureCode()).isEqualTo("DLT_ROUTED");
            assertThat(result.getFailureMessage()).isEqualTo("Exhausted retries");
            verify(paymentRepository).save(payment);
        }

        @Test
        @DisplayName("Should mark PROCESSING payment as FAILED")
        void markProcessingAsFailed() {
            UUID paymentId = UUID.randomUUID();
            Payment payment = Payment.builder()
                    .id(paymentId)
                    .amountCents(5000L)
                    .currency("USD")
                    .status(PaymentStatus.PROCESSING)
                    .idempotencyKey("key-1")
                    .build();

            when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));
            when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));

            Payment result = paymentService.markPaymentFailed(paymentId, "TIMEOUT", "Gateway timeout");

            assertThat(result.getStatus()).isEqualTo(PaymentStatus.FAILED);
            assertThat(result.getFailureCode()).isEqualTo("TIMEOUT");
            assertThat(result.getFailureMessage()).isEqualTo("Gateway timeout");
            verify(paymentRepository).save(payment);
        }

        @Test
        @DisplayName("Should return payment directly without saving if already in terminal state")
        void noOpIfAlreadyTerminal() {
            UUID paymentId = UUID.randomUUID();
            Payment payment = Payment.builder()
                    .id(paymentId)
                    .amountCents(5000L)
                    .currency("USD")
                    .status(PaymentStatus.DECLINED)
                    .failureCode("CARD_DECLINED")
                    .failureMessage("Card declined")
                    .idempotencyKey("key-1")
                    .build();

            when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));

            Payment result = paymentService.markPaymentFailed(paymentId, "DLT_ROUTED", "Some error");

            assertThat(result.getStatus()).isEqualTo(PaymentStatus.DECLINED);
            verify(paymentRepository, never()).save(any());
        }

        @Test
        @DisplayName("Should throw EntityNotFoundException when payment not found")
        void throwsWhenNotFound() {
            UUID paymentId = UUID.randomUUID();
            when(paymentRepository.findById(paymentId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> paymentService.markPaymentFailed(paymentId, "DLT_ROUTED", "msg"))
                    .isInstanceOf(EntityNotFoundException.class)
                    .hasMessageContaining("Payment not found with ID: " + paymentId);
        }
    }

    @Nested
    @DisplayName("capturePayment Tests")
    class CapturePaymentTests {

        @Test
        @DisplayName("Should successfully capture AUTHORIZED payment, persist outbox event and post ledger")
        void captureAuthorizedPaymentSuccess() throws Exception {
            UUID merchantId = UUID.randomUUID();
            UUID paymentId = UUID.randomUUID();
            com.lky.kaipay.merchant.domain.Merchant merchant = com.lky.kaipay.merchant.domain.Merchant.builder()
                    .id(merchantId)
                    .name("Acme")
                    .apiKeyHash("hash")
                    .build();
            Customer customer = Customer.builder()
                    .id(UUID.randomUUID())
                    .merchant(merchant)
                    .email("test@example.com")
                    .fullName("Test Customer")
                    .build();
            Payment payment = Payment.builder()
                    .id(paymentId)
                    .merchant(merchant)
                    .customer(customer)
                    .amountCents(10000L)
                    .currency("USD")
                    .status(PaymentStatus.AUTHORIZED)
                    .idempotencyKey("idem-123")
                    .build();

            when(paymentRepository.findByIdAndMerchantId(paymentId, merchantId)).thenReturn(Optional.of(payment));
            when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(objectMapper.writeValueAsString(any())).thenReturn("{\"eventType\":\"PaymentCapturedEvent\"}");

            com.lky.kaipay.payment.api.dto.PaymentResponse response = paymentService.capturePayment(merchantId, paymentId, "idem-cap-1");

            assertThat(response).isNotNull();
            assertThat(response.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
            verify(paymentRepository).save(payment);
            verify(paymentEventOutboxRepository).save(any());
            verify(ledgerService).recordPaymentCapture(any(), any());
        }

        @Test
        @DisplayName("Should return existing payment if already CAPTURED without saving new outbox or ledger")
        void captureAlreadyCapturedPaymentIdempotent() {
            UUID merchantId = UUID.randomUUID();
            UUID paymentId = UUID.randomUUID();
            com.lky.kaipay.merchant.domain.Merchant merchant = com.lky.kaipay.merchant.domain.Merchant.builder()
                    .id(merchantId)
                    .name("Acme")
                    .apiKeyHash("hash")
                    .build();
            Customer customer = Customer.builder()
                    .id(UUID.randomUUID())
                    .merchant(merchant)
                    .email("test@example.com")
                    .fullName("Test Customer")
                    .build();
            Payment payment = Payment.builder()
                    .id(paymentId)
                    .merchant(merchant)
                    .customer(customer)
                    .amountCents(10000L)
                    .currency("USD")
                    .status(PaymentStatus.CAPTURED)
                    .idempotencyKey("idem-123")
                    .build();

            when(paymentRepository.findByIdAndMerchantId(paymentId, merchantId)).thenReturn(Optional.of(payment));

            com.lky.kaipay.payment.api.dto.PaymentResponse response = paymentService.capturePayment(merchantId, paymentId, "idem-cap-1");

            assertThat(response.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
            verify(paymentRepository, never()).save(any());
            verify(paymentEventOutboxRepository, never()).save(any());
            verify(ledgerService, never()).recordPaymentCapture(any(), any());
        }

        @Test
        @DisplayName("Should throw InvalidRequestException when capturing payment in CREATED status")
        void captureInCreatedStatusThrowsException() {
            UUID merchantId = UUID.randomUUID();
            UUID paymentId = UUID.randomUUID();
            Payment payment = Payment.builder()
                    .id(paymentId)
                    .amountCents(10000L)
                    .currency("USD")
                    .status(PaymentStatus.CREATED)
                    .idempotencyKey("idem-123")
                    .build();

            when(paymentRepository.findByIdAndMerchantId(paymentId, merchantId)).thenReturn(Optional.of(payment));

            assertThatThrownBy(() -> paymentService.capturePayment(merchantId, paymentId, "idem-cap-1"))
                    .isInstanceOf(com.lky.kaipay.common.exception.InvalidRequestException.class)
                    .hasMessageContaining("Cannot capture payment in status CREATED");
        }

        @Test
        @DisplayName("Should throw EntityNotFoundException when payment not found for merchant")
        void captureNotFoundThrowsException() {
            UUID merchantId = UUID.randomUUID();
            UUID paymentId = UUID.randomUUID();

            when(paymentRepository.findByIdAndMerchantId(paymentId, merchantId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> paymentService.capturePayment(merchantId, paymentId, "idem-cap-1"))
                    .isInstanceOf(EntityNotFoundException.class)
                    .hasMessageContaining("Payment " + paymentId + " not found for merchant " + merchantId);
        }
    }
}
