package com.lky.kaipay.ledger.service;

import com.lky.kaipay.AbstractPostgresIntegrationTest;
import com.lky.kaipay.common.exception.UnbalancedJournalException;
import com.lky.kaipay.customer.domain.Customer;
import com.lky.kaipay.customer.repository.CustomerRepository;
import com.lky.kaipay.ledger.domain.Account;
import com.lky.kaipay.ledger.domain.AccountType;
import com.lky.kaipay.ledger.domain.EntryType;
import com.lky.kaipay.ledger.domain.Journal;
import com.lky.kaipay.ledger.domain.JournalSourceType;
import com.lky.kaipay.ledger.domain.LedgerEntry;
import com.lky.kaipay.ledger.repository.AccountRepository;
import com.lky.kaipay.ledger.repository.JournalRepository;
import com.lky.kaipay.ledger.repository.LedgerEntryRepository;
import com.lky.kaipay.ledger.service.dto.LedgerEntryRequest;
import com.lky.kaipay.merchant.domain.Merchant;
import com.lky.kaipay.merchant.domain.MerchantStatus;
import com.lky.kaipay.merchant.repository.MerchantRepository;
import com.lky.kaipay.payment.domain.Payment;
import com.lky.kaipay.payment.domain.PaymentStatus;
import com.lky.kaipay.payment.repository.PaymentRepository;
import com.lky.kaipay.refund.domain.Refund;
import com.lky.kaipay.refund.domain.RefundStatus;
import com.lky.kaipay.refund.repository.RefundRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("LedgerService Integration Tests")
class LedgerServiceIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private AccountProvisioningService accountProvisioningService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private JournalRepository journalRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private MerchantRepository merchantRepository;

    @Autowired
    private RefundRepository refundRepository;

    private Merchant merchant;
    private Customer customer;

    @BeforeEach
    void setUp() {
        cleanup();

        merchant = merchantRepository.save(
                Merchant.builder()
                        .name("Ledger Integration Merchant " + UUID.randomUUID())
                        .apiKeyHash("hash_" + UUID.randomUUID())
                        .status(MerchantStatus.ACTIVE)
                        .build()
        );

        customer = customerRepository.save(
                Customer.builder()
                        .merchant(merchant)
                        .email("ledger_test_" + UUID.randomUUID() + "@example.com")
                        .fullName("Ledger Customer")
                        .build()
        );
    }

    @AfterEach
    void tearDown() {
        cleanup();
        if (customer != null && customer.getId() != null) {
            customerRepository.deleteById(customer.getId());
        }
        if (merchant != null && merchant.getId() != null) {
            merchantRepository.deleteById(merchant.getId());
        }
    }

    private void cleanup() {
        refundRepository.deleteAll();
        ledgerEntryRepository.deleteAll();
        journalRepository.deleteAll();
        accountRepository.deleteAll();
        paymentRepository.deleteAll();
    }

    @Test
    @DisplayName("recordPaymentCapture creates balanced journal and provisions accounts correctly")
    void testRecordPaymentCaptureEndToEnd() {
        Payment payment = paymentRepository.save(
                Payment.builder()
                        .merchant(merchant)
                        .customer(customer)
                        .amountCents(10000L) // $100.00
                        .currency("USD")
                        .status(PaymentStatus.CAPTURED)
                        .idempotencyKey("idem_" + UUID.randomUUID())
                        .build()
        );

        UUID eventId = UUID.randomUUID();

        Journal journal = ledgerService.recordPaymentCapture(payment, eventId);

        assertThat(journal.getId()).isNotNull();
        assertThat(journal.getJournalNumber()).startsWith("JNL-");
        assertThat(journal.getSourceType()).isEqualTo(JournalSourceType.PAYMENT_CAPTURE);
        assertThat(journal.getSourceId()).isEqualTo(payment.getId().toString());
        assertThat(journal.getEventId()).isEqualTo(eventId);
        assertThat(journal.getEntries()).hasSize(3);

        // Verify accounts in DB
        Account receivable = accountRepository.findByAccountNumber(AccountProvisioningService.SYS_CUSTOMER_RECEIVABLE_ACC).orElseThrow();
        Account payable = accountRepository.findByMerchantIdAndAccountType(merchant.getId(), AccountType.LIABILITY).orElseThrow();
        Account revenue = accountRepository.findByAccountNumber(AccountProvisioningService.SYS_PLATFORM_REVENUE_ACC).orElseThrow();

        assertThat(receivable.getAccountType()).isEqualTo(AccountType.ASSET);
        assertThat(payable.getAccountType()).isEqualTo(AccountType.LIABILITY);
        assertThat(revenue.getAccountType()).isEqualTo(AccountType.REVENUE);

        // Fee: round(10000 * 0.029) + 30 = 320 cents ($3.20)
        // Net Merchant: 10000 - 320 = 9680 cents ($96.80)
        // Balance query returns CREDIT - DEBIT
        long receivableBalance = ledgerService.getAccountBalance(receivable.getId());
        long payableBalance = ledgerService.getAccountBalance(payable.getId());
        long revenueBalance = ledgerService.getAccountBalance(revenue.getId());

        assertThat(receivableBalance).isEqualTo(-10000L); // DEBIT 10000 -> -10000
        assertThat(payableBalance).isEqualTo(9680L);     // CREDIT 9680 -> +9680
        assertThat(revenueBalance).isEqualTo(320L);      // CREDIT 320 -> +320

        // Invariant: sum of all account balances in system must be exactly 0
        assertThat(receivableBalance + payableBalance + revenueBalance).isEqualTo(0L);
    }

    @Test
    @DisplayName("recordPaymentRefund creates balanced reversal journal and updates balances accurately")
    void testRecordPaymentRefundEndToEnd() {
        Payment payment = paymentRepository.save(
                Payment.builder()
                        .merchant(merchant)
                        .customer(customer)
                        .amountCents(10000L) // $100.00
                        .currency("USD")
                        .status(PaymentStatus.CAPTURED)
                        .idempotencyKey("idem_" + UUID.randomUUID())
                        .build()
        );

        UUID captureEventId = UUID.randomUUID();
        ledgerService.recordPaymentCapture(payment, captureEventId);

        Refund refund = refundRepository.save(
                Refund.builder()
                        .payment(payment)
                        .merchant(merchant)
                        .amountCents(5000L) // $50.00 partial refund
                        .currency("USD")
                        .status(RefundStatus.COMPLETED)
                        .idempotencyKey("refund_idem_" + UUID.randomUUID())
                        .build()
        );

        UUID refundEventId = UUID.randomUUID();
        Journal refundJournal = ledgerService.recordPaymentRefund(payment, refund, refundEventId);

        assertThat(refundJournal.getId()).isNotNull();
        assertThat(refundJournal.getSourceType()).isEqualTo(JournalSourceType.PAYMENT_REFUND);
        assertThat(refundJournal.getSourceId()).isEqualTo(refund.getId().toString());
        assertThat(refundJournal.getEntries()).hasSize(3);

        Account receivable = accountRepository.findByAccountNumber(AccountProvisioningService.SYS_CUSTOMER_RECEIVABLE_ACC).orElseThrow();
        Account payable = accountRepository.findByMerchantIdAndAccountType(merchant.getId(), AccountType.LIABILITY).orElseThrow();
        Account revenue = accountRepository.findByAccountNumber(AccountProvisioningService.SYS_PLATFORM_REVENUE_ACC).orElseThrow();

        // Refund fee reversal: round(5000 * 0.029) = 145 cents ($1.45)
        // Merchant net refund debit: 5000 - 145 = 4855 cents ($48.55)
        // New balances:
        // Receivable: -10000 (DEBIT) + 5000 (CREDIT) = -5000
        // Payable: +9680 (CREDIT) - 4855 (DEBIT) = +4825
        // Revenue: +320 (CREDIT) - 145 (DEBIT) = +175
        long receivableBalance = ledgerService.getAccountBalance(receivable.getId());
        long payableBalance = ledgerService.getAccountBalance(payable.getId());
        long revenueBalance = ledgerService.getAccountBalance(revenue.getId());

        assertThat(receivableBalance).isEqualTo(-5000L);
        assertThat(payableBalance).isEqualTo(4825L);
        assertThat(revenueBalance).isEqualTo(175L);

        // Global double-entry balance check
        assertThat(receivableBalance + payableBalance + revenueBalance).isEqualTo(0L);
    }

    @Test
    @DisplayName("Idempotent eventId returns existing journal without creating duplicates")
    void testIdempotency() {
        Payment payment = paymentRepository.save(
                Payment.builder()
                        .merchant(merchant)
                        .customer(customer)
                        .amountCents(5000L)
                        .currency("USD")
                        .status(PaymentStatus.CAPTURED)
                        .idempotencyKey("idem_" + UUID.randomUUID())
                        .build()
        );

        UUID eventId = UUID.randomUUID();

        Journal firstJournal = ledgerService.recordPaymentCapture(payment, eventId);
        Journal secondJournal = ledgerService.recordPaymentCapture(payment, eventId);

        assertThat(firstJournal.getId()).isEqualTo(secondJournal.getId());
        assertThat(journalRepository.count()).isEqualTo(1);
        assertThat(ledgerEntryRepository.count()).isEqualTo(3);
    }

    @Test
    @DisplayName("postJournal rejects unbalanced entries and does not persist anything")
    void testUnbalancedJournalRollback() {
        Account sysAcc = accountProvisioningService.getOrCreateSystemAccount(
                AccountProvisioningService.SYS_CUSTOMER_RECEIVABLE_ACC, "Customer Funds", AccountType.ASSET, "USD"
        );
        Account mchAcc = accountProvisioningService.getOrCreateMerchantAccount(merchant, AccountType.LIABILITY, "USD");

        List<LedgerEntryRequest> unbalancedEntries = List.of(
                new LedgerEntryRequest(sysAcc, EntryType.DEBIT, 1000L, "USD"),
                new LedgerEntryRequest(mchAcc, EntryType.CREDIT, 900L, "USD")
        );

        assertThatThrownBy(() -> ledgerService.postJournal(
                JournalSourceType.PAYMENT_CAPTURE,
                "src_test",
                UUID.randomUUID(),
                merchant,
                "Unbalanced test",
                unbalancedEntries
        )).isInstanceOf(UnbalancedJournalException.class);

        assertThat(journalRepository.count()).isEqualTo(0);
        assertThat(ledgerEntryRepository.count()).isEqualTo(0);
    }

    @Test
    @DisplayName("AccountProvisioningService idempotently gets or creates accounts")
    void testAccountProvisioningIdempotency() {
        Account sysAcc1 = accountProvisioningService.getOrCreateSystemAccount(
                AccountProvisioningService.SYS_PLATFORM_REVENUE_ACC, "Platform Revenue", AccountType.REVENUE, "USD"
        );
        Account sysAcc2 = accountProvisioningService.getOrCreateSystemAccount(
                AccountProvisioningService.SYS_PLATFORM_REVENUE_ACC, "Platform Revenue", AccountType.REVENUE, "USD"
        );
        assertThat(sysAcc1.getId()).isEqualTo(sysAcc2.getId());

        Account mchAcc1 = accountProvisioningService.getOrCreateMerchantAccount(merchant, AccountType.LIABILITY, "USD");
        Account mchAcc2 = accountProvisioningService.getOrCreateMerchantAccount(merchant, AccountType.LIABILITY, "USD");
        assertThat(mchAcc1.getId()).isEqualTo(mchAcc2.getId());
    }
}
