package com.lky.kaipay.payment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lky.kaipay.common.exception.EntityNotFoundException;
import com.lky.kaipay.customer.domain.Customer;
import com.lky.kaipay.customer.repository.CustomerRepository;
import com.lky.kaipay.merchant.repository.MerchantRepository;
import com.lky.kaipay.outbox.repository.PaymentEventOutboxRepository;
import com.lky.kaipay.payment.domain.Payment;
import com.lky.kaipay.payment.domain.PaymentStatus;
import com.lky.kaipay.payment.repository.PaymentMethodRepository;
import com.lky.kaipay.payment.repository.PaymentRepository;
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
                ledgerService,
                objectMapper
        );
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
                    .currency("MYR")
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
                    .currency("MYR")
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
                    .currency("MYR")
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
