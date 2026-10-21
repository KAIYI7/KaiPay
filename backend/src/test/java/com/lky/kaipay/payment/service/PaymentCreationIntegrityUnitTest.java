package com.lky.kaipay.payment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lky.kaipay.customer.domain.Customer;
import com.lky.kaipay.customer.repository.CustomerRepository;
import com.lky.kaipay.ledger.service.LedgerService;
import com.lky.kaipay.merchant.domain.Merchant;
import com.lky.kaipay.merchant.repository.MerchantRepository;
import com.lky.kaipay.outbox.repository.PaymentEventOutboxRepository;
import com.lky.kaipay.payment.api.dto.CreatePaymentRequest;
import com.lky.kaipay.payment.domain.Payment;
import com.lky.kaipay.payment.repository.PaymentMethodRepository;
import com.lky.kaipay.payment.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentCreationIntegrityUnitTest {

    @Mock private PaymentRepository paymentRepository;
    @Mock private MerchantRepository merchantRepository;
    @Mock private CustomerRepository customerRepository;
    @Mock private PaymentMethodRepository paymentMethodRepository;
    @Mock private IdempotencyService idempotencyService;
    @Mock private PaymentEventOutboxRepository outboxRepository;
    @Mock private LedgerService ledgerService;
    @Mock private ObjectMapper objectMapper;

    private PaymentService service;
    private UUID merchantId;
    private CreatePaymentRequest request;

    @BeforeEach
    void setUp() {
        service = new PaymentService(paymentRepository, merchantRepository, customerRepository,
                paymentMethodRepository, idempotencyService, outboxRepository,
                ledgerService, objectMapper, org.mockito.Mockito.mock(com.lky.kaipay.webhook.WebhookIntentService.class));
        merchantId = UUID.randomUUID();
        Merchant merchant = Merchant.builder().id(merchantId).name("Test").apiKeyHash("hash").build();
        Customer customer = Customer.builder().id(UUID.randomUUID()).merchant(merchant)
                .email("test@example.com").fullName("Test").build();
        request = CreatePaymentRequest.builder().customerId(customer.getId()).amountCents(100L).currency("MYR").build();
        when(idempotencyService.computeRequestHash(request)).thenReturn("hash");
        when(idempotencyService.getExistingResponse(merchantId, "key", "hash")).thenReturn(Optional.empty());
        when(merchantRepository.findById(merchantId)).thenReturn(Optional.of(merchant));
        when(customerRepository.findByIdAndMerchantId(customer.getId(), merchantId)).thenReturn(Optional.of(customer));
    }

    @Test
    void paymentIntegrityFailurePropagatesWithoutRetryingLookupInFailedTransaction() {
        DataIntegrityViolationException failure = new DataIntegrityViolationException("payment constraint");
        when(paymentRepository.save(any())).thenThrow(failure);

        assertThatThrownBy(() -> service.createPayment(merchantId, "key", request)).isSameAs(failure);

        verifyOnlyInitialIdempotencyLookup();
        verifyNoInteractions(outboxRepository, objectMapper, ledgerService);
    }

    @Test
    void outboxIntegrityFailurePropagatesWithoutMaskingItAsPaymentReplay() throws Exception {
        when(paymentRepository.save(any())).thenAnswer(invocation -> {
            Payment payment = invocation.getArgument(0);
            payment.setId(UUID.randomUUID());
            return payment;
        });
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        DataIntegrityViolationException failure = new DataIntegrityViolationException("outbox constraint");
        when(outboxRepository.save(any())).thenThrow(failure);

        assertThatThrownBy(() -> service.createPayment(merchantId, "key", request)).isSameAs(failure);

        verifyOnlyInitialIdempotencyLookup();
        verifyNoInteractions(ledgerService);
    }

    private void verifyOnlyInitialIdempotencyLookup() {
        verify(idempotencyService).computeRequestHash(request);
        verify(idempotencyService, times(1)).getExistingResponse(merchantId, "key", "hash");
        verifyNoMoreInteractions(idempotencyService);
    }
}
