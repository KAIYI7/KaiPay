package com.lky.kaipay.refund.repository;

import com.lky.kaipay.AbstractPostgresIntegrationTest;
import com.lky.kaipay.customer.domain.Customer;
import com.lky.kaipay.customer.repository.CustomerRepository;
import com.lky.kaipay.merchant.domain.Merchant;
import com.lky.kaipay.merchant.domain.MerchantStatus;
import com.lky.kaipay.merchant.repository.MerchantRepository;
import com.lky.kaipay.payment.domain.Payment;
import com.lky.kaipay.payment.domain.PaymentStatus;
import com.lky.kaipay.payment.repository.PaymentMethodRepository;
import com.lky.kaipay.payment.repository.PaymentRepository;
import com.lky.kaipay.refund.domain.Refund;
import com.lky.kaipay.refund.domain.RefundStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("RefundRepository Integration Tests")
class RefundRepositoryIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private RefundRepository refundRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private PaymentMethodRepository paymentMethodRepository;

    @Autowired
    private MerchantRepository merchantRepository;

    @Autowired
    private CustomerRepository customerRepository;

    private Merchant merchant;
    private Customer customer;
    private Payment payment;

    @BeforeEach
    void setUp() {
        cleanup();

        merchant = merchantRepository.save(
                Merchant.builder()
                        .name("Refund Test Merchant " + UUID.randomUUID())
                        .apiKeyHash("hash_" + UUID.randomUUID())
                        .status(MerchantStatus.ACTIVE)
                        .build()
        );

        customer = customerRepository.save(
                Customer.builder()
                        .merchant(merchant)
                        .email("refund_cust_" + UUID.randomUUID() + "@example.com")
                        .fullName("Refund Test Customer")
                        .build()
        );

        payment = paymentRepository.save(
                Payment.builder()
                        .merchant(merchant)
                        .customer(customer)
                        .amountCents(10000L)
                        .currency("USD")
                        .status(PaymentStatus.CAPTURED)
                        .idempotencyKey("pay_idem_" + UUID.randomUUID())
                        .build()
        );
    }

    @AfterEach
    void tearDown() {
        cleanup();
        if (merchant != null && merchant.getId() != null) {
            merchantRepository.deleteById(merchant.getId());
        }
    }

    private void cleanup() {
        refundRepository.deleteAll();
        paymentRepository.deleteAll();
        paymentMethodRepository.deleteAll();
        customerRepository.deleteAll();
    }

    @Test
    @DisplayName("Persist and query refund by merchant ID and idempotency key")
    void testSaveAndFindRefundByIdempotencyKey() {
        String idempotencyKey = "ref_idem_" + UUID.randomUUID();

        Refund refund = refundRepository.save(
                Refund.builder()
                        .payment(payment)
                        .merchant(merchant)
                        .amountCents(2500L)
                        .currency("USD")
                        .status(RefundStatus.COMPLETED)
                        .reason("Customer request")
                        .idempotencyKey(idempotencyKey)
                        .build()
        );

        assertThat(refund.getId()).isNotNull();
        assertThat(refund.getCreatedAt()).isNotNull();

        Optional<Refund> found = refundRepository.findByMerchantIdAndIdempotencyKey(merchant.getId(), idempotencyKey);
        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(refund.getId());
        assertThat(found.get().getAmountCents()).isEqualTo(2500L);
        assertThat(found.get().getStatus()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(found.get().getReason()).isEqualTo("Customer request");
    }

    @Test
    @DisplayName("Idempotency key unique per merchant constraint is enforced")
    void testRefundIdempotencyKeyUniquePerMerchant() {
        String idempotencyKey = "ref_idem_unique_" + UUID.randomUUID();

        refundRepository.save(
                Refund.builder()
                        .payment(payment)
                        .merchant(merchant)
                        .amountCents(1000L)
                        .currency("USD")
                        .status(RefundStatus.COMPLETED)
                        .reason("First refund")
                        .idempotencyKey(idempotencyKey)
                        .build()
        );

        assertThatThrownBy(() -> {
            refundRepository.saveAndFlush(
                    Refund.builder()
                            .payment(payment)
                            .merchant(merchant)
                            .amountCents(2000L)
                            .currency("USD")
                            .status(RefundStatus.COMPLETED)
                            .reason("Duplicate idempotency key")
                            .idempotencyKey(idempotencyKey)
                            .build()
            );
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("findByPaymentId returns all refunds for a payment")
    void testFindByPaymentId() {
        refundRepository.save(
                Refund.builder()
                        .payment(payment)
                        .merchant(merchant)
                        .amountCents(1000L)
                        .currency("USD")
                        .status(RefundStatus.COMPLETED)
                        .reason("Partial refund 1")
                        .idempotencyKey("idem_1_" + UUID.randomUUID())
                        .build()
        );

        refundRepository.save(
                Refund.builder()
                        .payment(payment)
                        .merchant(merchant)
                        .amountCents(2000L)
                        .currency("USD")
                        .status(RefundStatus.PENDING)
                        .reason("Partial refund 2")
                        .idempotencyKey("idem_2_" + UUID.randomUUID())
                        .build()
        );

        List<Refund> refunds = refundRepository.findByPaymentId(payment.getId());
        assertThat(refunds).hasSize(2);
    }

    @Test
    @DisplayName("sumRefundedAmountByPaymentId correctly sums only COMPLETED refunds")
    void testSumRefundedAmountByPaymentId() {
        // No refunds yet -> sum should be 0
        Long initialSum = refundRepository.sumRefundedAmountByPaymentId(payment.getId());
        assertThat(initialSum).isEqualTo(0L);

        // Add completed refund 2500
        refundRepository.save(
                Refund.builder()
                        .payment(payment)
                        .merchant(merchant)
                        .amountCents(2500L)
                        .currency("USD")
                        .status(RefundStatus.COMPLETED)
                        .reason("Completed 1")
                        .idempotencyKey("idem_comp_1_" + UUID.randomUUID())
                        .build()
        );

        // Add completed refund 1500
        refundRepository.save(
                Refund.builder()
                        .payment(payment)
                        .merchant(merchant)
                        .amountCents(1500L)
                        .currency("USD")
                        .status(RefundStatus.COMPLETED)
                        .reason("Completed 2")
                        .idempotencyKey("idem_comp_2_" + UUID.randomUUID())
                        .build()
        );

        // Add pending refund 1000 (should NOT be included in sum)
        refundRepository.save(
                Refund.builder()
                        .payment(payment)
                        .merchant(merchant)
                        .amountCents(1000L)
                        .currency("USD")
                        .status(RefundStatus.PENDING)
                        .reason("Pending refund")
                        .idempotencyKey("idem_pend_" + UUID.randomUUID())
                        .build()
        );

        // Add failed refund 800 (should NOT be included in sum)
        refundRepository.save(
                Refund.builder()
                        .payment(payment)
                        .merchant(merchant)
                        .amountCents(800L)
                        .currency("USD")
                        .status(RefundStatus.FAILED)
                        .reason("Failed refund")
                        .idempotencyKey("idem_fail_" + UUID.randomUUID())
                        .build()
        );

        Long totalRefunded = refundRepository.sumRefundedAmountByPaymentId(payment.getId());
        assertThat(totalRefunded).isEqualTo(4000L);
    }
}
