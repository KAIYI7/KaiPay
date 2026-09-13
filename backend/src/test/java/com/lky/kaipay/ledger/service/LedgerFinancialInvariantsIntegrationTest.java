package com.lky.kaipay.ledger.service;

import com.lky.kaipay.AbstractPostgresIntegrationTest;
import com.lky.kaipay.common.exception.InvalidRefundException;
import com.lky.kaipay.customer.domain.Customer;
import com.lky.kaipay.customer.repository.CustomerRepository;
import com.lky.kaipay.ledger.api.dto.MerchantBalanceResponse;
import com.lky.kaipay.ledger.domain.AccountType;
import com.lky.kaipay.ledger.domain.EntryType;
import com.lky.kaipay.ledger.domain.Journal;
import com.lky.kaipay.ledger.domain.LedgerEntry;
import com.lky.kaipay.ledger.repository.AccountRepository;
import com.lky.kaipay.ledger.repository.JournalRepository;
import com.lky.kaipay.ledger.repository.LedgerEntryRepository;
import com.lky.kaipay.merchant.domain.Merchant;
import com.lky.kaipay.merchant.domain.MerchantStatus;
import com.lky.kaipay.merchant.repository.MerchantRepository;
import com.lky.kaipay.outbox.repository.PaymentEventOutboxRepository;
import com.lky.kaipay.payment.domain.Payment;
import com.lky.kaipay.payment.domain.PaymentStatus;
import com.lky.kaipay.payment.repository.PaymentRepository;
import com.lky.kaipay.payment.service.PaymentService;
import com.lky.kaipay.refund.api.dto.CreateRefundRequest;
import com.lky.kaipay.refund.api.dto.RefundResponse;
import com.lky.kaipay.refund.domain.Refund;
import com.lky.kaipay.refund.domain.RefundStatus;
import com.lky.kaipay.refund.repository.RefundRepository;
import com.lky.kaipay.refund.service.RefundService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Ledger Financial Invariants & Concurrency Integration Tests")
class LedgerFinancialInvariantsIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private RefundService refundService;

    @Autowired
    private MerchantBalanceService merchantBalanceService;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private RefundRepository refundRepository;

    @Autowired
    private JournalRepository journalRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private MerchantRepository merchantRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private PaymentEventOutboxRepository paymentEventOutboxRepository;

    private Merchant merchantA;
    private Merchant merchantB;
    private Customer customerA;
    private Customer customerB;

    @BeforeEach
    void setUp() {
        cleanup();

        merchantA = merchantRepository.save(
                Merchant.builder()
                        .name("Invariant Test Merchant A")
                        .apiKeyHash("hash_inv_a_" + UUID.randomUUID())
                        .status(MerchantStatus.ACTIVE)
                        .build()
        );

        merchantB = merchantRepository.save(
                Merchant.builder()
                        .name("Invariant Test Merchant B")
                        .apiKeyHash("hash_inv_b_" + UUID.randomUUID())
                        .status(MerchantStatus.ACTIVE)
                        .build()
        );

        customerA = customerRepository.save(
                Customer.builder()
                        .merchant(merchantA)
                        .email("cust_a_" + UUID.randomUUID() + "@example.com")
                        .fullName("Customer A")
                        .build()
        );

        customerB = customerRepository.save(
                Customer.builder()
                        .merchant(merchantB)
                        .email("cust_b_" + UUID.randomUUID() + "@example.com")
                        .fullName("Customer B")
                        .build()
        );
    }

    @AfterEach
    void tearDown() {
        cleanup();
        if (customerA != null && customerA.getId() != null) {
            customerRepository.deleteById(customerA.getId());
        }
        if (customerB != null && customerB.getId() != null) {
            customerRepository.deleteById(customerB.getId());
        }
        if (merchantA != null && merchantA.getId() != null) {
            merchantRepository.deleteById(merchantA.getId());
        }
        if (merchantB != null && merchantB.getId() != null) {
            merchantRepository.deleteById(merchantB.getId());
        }
    }

    private void cleanup() {
        refundRepository.deleteAll();
        ledgerEntryRepository.deleteAll();
        journalRepository.deleteAll();
        accountRepository.deleteAll();
        paymentEventOutboxRepository.deleteAll();
        paymentRepository.deleteAll();
    }

    private Payment createAuthorizedPayment(Merchant merchant, Customer customer, long amountCents) {
        return paymentRepository.save(
                Payment.builder()
                        .merchant(merchant)
                        .customer(customer)
                        .amountCents(amountCents)
                        .currency("USD")
                        .status(PaymentStatus.AUTHORIZED)
                        .gatewayReference("AUTH-INV-" + UUID.randomUUID().toString().substring(0, 8))
                        .idempotencyKey("pay_inv_" + UUID.randomUUID())
                        .build()
        );
    }

    @Test
    @DisplayName("1. Every journal in the database is strictly balanced (totalDebit == totalCredit > 0)")
    void testEveryJournalInDatabaseIsStrictlyBalanced() {
        // Merchant A Payment 1: $100.00 -> Captured -> Partial Refund $30.00
        Payment pA1 = createAuthorizedPayment(merchantA, customerA, 10000L);
        paymentService.capturePayment(merchantA.getId(), pA1.getId(), "cap-a1-" + UUID.randomUUID());
        refundService.createRefund(
                merchantA.getId(),
                pA1.getId(),
                "ref-a1-1-" + UUID.randomUUID(),
                CreateRefundRequest.builder().amountCents(3000L).reason("Partial return").build()
        );

        // Merchant A Payment 2: $50.00 -> Captured -> Full Refund $50.00
        Payment pA2 = createAuthorizedPayment(merchantA, customerA, 5000L);
        paymentService.capturePayment(merchantA.getId(), pA2.getId(), "cap-a2-" + UUID.randomUUID());
        refundService.createRefund(
                merchantA.getId(),
                pA2.getId(),
                "ref-a2-1-" + UUID.randomUUID(),
                CreateRefundRequest.builder().amountCents(5000L).reason("Full return").build()
        );

        // Merchant A Payment 3: $250.00 -> Captured (no refund)
        Payment pA3 = createAuthorizedPayment(merchantA, customerA, 25000L);
        paymentService.capturePayment(merchantA.getId(), pA3.getId(), "cap-a3-" + UUID.randomUUID());

        // Merchant B Payment 1: $80.00 -> Captured -> Partial Refund $20.00 -> Partial Refund $30.00
        Payment pB1 = createAuthorizedPayment(merchantB, customerB, 8000L);
        paymentService.capturePayment(merchantB.getId(), pB1.getId(), "cap-b1-" + UUID.randomUUID());
        refundService.createRefund(
                merchantB.getId(),
                pB1.getId(),
                "ref-b1-1-" + UUID.randomUUID(),
                CreateRefundRequest.builder().amountCents(2000L).reason("Partial 1").build()
        );
        refundService.createRefund(
                merchantB.getId(),
                pB1.getId(),
                "ref-b1-2-" + UUID.randomUUID(),
                CreateRefundRequest.builder().amountCents(3000L).reason("Partial 2").build()
        );

        // Merchant B Payment 2: $120.00 -> Captured
        Payment pB2 = createAuthorizedPayment(merchantB, customerB, 12000L);
        paymentService.capturePayment(merchantB.getId(), pB2.getId(), "cap-b2-" + UUID.randomUUID());

        // Load all journals and entries from database
        List<Journal> journals = journalRepository.findAll();
        List<LedgerEntry> allEntries = ledgerEntryRepository.findAll();

        assertThat(journals).isNotEmpty();
        assertThat(journals).hasSize(9); // 5 captures + 4 refunds = 9 journals
        assertThat(allEntries).isNotEmpty();

        for (Journal journal : journals) {
            List<LedgerEntry> entries = ledgerEntryRepository.findByJournalId(journal.getId());
            assertThat(entries).isNotEmpty();

            long totalDebit = entries.stream()
                    .filter(e -> e.getEntryType() == EntryType.DEBIT)
                    .mapToLong(LedgerEntry::getAmountCents)
                    .sum();

            long totalCredit = entries.stream()
                    .filter(e -> e.getEntryType() == EntryType.CREDIT)
                    .mapToLong(LedgerEntry::getAmountCents)
                    .sum();

            assertThat(totalDebit)
                    .as("Journal %s debit amount must be strictly positive", journal.getJournalNumber())
                    .isGreaterThan(0L);

            assertThat(totalCredit)
                    .as("Journal %s credit amount must be strictly positive", journal.getJournalNumber())
                    .isGreaterThan(0L);

            assertThat(totalDebit)
                    .as("Journal %s must be balanced: totalDebit (%d) == totalCredit (%d)",
                            journal.getJournalNumber(), totalDebit, totalCredit)
                    .isEqualTo(totalCredit);
        }

        // Global double-entry balance invariant across all accounts
        long globalSumDebit = allEntries.stream()
                .filter(e -> e.getEntryType() == EntryType.DEBIT)
                .mapToLong(LedgerEntry::getAmountCents)
                .sum();

        long globalSumCredit = allEntries.stream()
                .filter(e -> e.getEntryType() == EntryType.CREDIT)
                .mapToLong(LedgerEntry::getAmountCents)
                .sum();

        assertThat(globalSumDebit).isEqualTo(globalSumCredit);
    }

    @Test
    @DisplayName("2. Over-refund prevention and merchant balance consistency")
    void testOverRefundPreventionAndBalanceConsistency() {
        // Creates and captures $100.00 payment
        Payment payment = createAuthorizedPayment(merchantA, customerA, 10000L);
        paymentService.capturePayment(merchantA.getId(), payment.getId(), "cap-" + UUID.randomUUID());

        // Partial refund 1: $60.00 (6000 cents) -> succeeds, status is PARTIALLY_REFUNDED
        RefundResponse ref1 = refundService.createRefund(
                merchantA.getId(),
                payment.getId(),
                "ref-over-1-" + UUID.randomUUID(),
                CreateRefundRequest.builder().amountCents(6000L).reason("Partial refund 1").build()
        );
        assertThat(ref1.getStatus()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(ref1.getAmountCents()).isEqualTo(6000L);

        Payment paymentAfterRef1 = paymentRepository.findById(payment.getId()).orElseThrow();
        assertThat(paymentAfterRef1.getStatus()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);

        // Attempts partial refund 2: $45.00 (4500 cents) -> throws InvalidRefundException (only $40 remaining)
        assertThatThrownBy(() -> refundService.createRefund(
                merchantA.getId(),
                payment.getId(),
                "ref-over-2-" + UUID.randomUUID(),
                CreateRefundRequest.builder().amountCents(4500L).reason("Over refund attempt").build()
        )).isInstanceOf(InvalidRefundException.class)
                .hasMessageContaining("Refund amount exceeds refundable balance");

        // Processes partial refund 3: $40.00 (4000 cents) -> succeeds, status is REFUNDED
        RefundResponse ref3 = refundService.createRefund(
                merchantA.getId(),
                payment.getId(),
                "ref-over-3-" + UUID.randomUUID(),
                CreateRefundRequest.builder().amountCents(4000L).reason("Remaining refund").build()
        );
        assertThat(ref3.getStatus()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(ref3.getAmountCents()).isEqualTo(4000L);

        Payment paymentAfterRef3 = paymentRepository.findById(payment.getId()).orElseThrow();
        assertThat(paymentAfterRef3.getStatus()).isEqualTo(PaymentStatus.REFUNDED);

        // Attempts partial refund 4: $1.00 (100 cents) -> throws InvalidRefundException
        assertThatThrownBy(() -> refundService.createRefund(
                merchantA.getId(),
                payment.getId(),
                "ref-over-4-" + UUID.randomUUID(),
                CreateRefundRequest.builder().amountCents(100L).reason("Beyond full refund").build()
        )).isInstanceOf(InvalidRefundException.class);

        // Asserts MerchantBalanceService computes exact available balance, volume, fees, and refund totals
        // Capture $100: Fee = round(10000 * 0.029) + 30 = 320 cents. Merchant Liability = +9680. Revenue = +320.
        // Refund 1 ($60): Fee reversal = round(6000 * 0.029) = 174. Net debit = 5826. Revenue debit = 174.
        // Refund 3 ($40): Fee reversal = round(4000 * 0.029) = 116. Net debit = 3884. Revenue debit = 116.
        // Available balance: 9680 - (5826 + 3884) = 9680 - 9710 = -30 cents (platform retained $0.30 fixed fee).
        // Total Volume: $100.00 (10000 cents).
        // Total Fees: 320 - (174 + 116) = 30 cents ($0.30).
        // Total Refunds: 6000 + 4000 = 10000 cents ($100.00).
        MerchantBalanceResponse balance = merchantBalanceService.getMerchantBalance(merchantA.getId());
        assertThat(balance.getMerchantId()).isEqualTo(merchantA.getId());
        assertThat(balance.getAvailableBalanceCents()).isEqualTo(-30L);
        assertThat(balance.getPendingSettlementCents()).isEqualTo(0L);
        assertThat(balance.getTotalVolumeCents()).isEqualTo(10000L);
        assertThat(balance.getTotalFeesCents()).isEqualTo(30L);
        assertThat(balance.getTotalRefundsCents()).isEqualTo(10000L);
        assertThat(balance.getCurrency()).isEqualTo("USD");
    }

    @Test
    @DisplayName("3. Concurrent refunds never exceed captured amount")
    void testConcurrentRefundsNeverExceedCapturedAmount() throws Exception {
        // Creates and captures $100.00 payment (10,000 cents)
        Payment payment = createAuthorizedPayment(merchantA, customerA, 10000L);
        paymentService.capturePayment(merchantA.getId(), payment.getId(), "cap-conc-" + UUID.randomUUID());

        // Launches 10 concurrent threads each attempting to refund $20.00 (2,000 cents) using distinct idempotency keys
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            final int threadIndex = i;
            final String idempotencyKey = "ref-conc-idem-" + threadIndex + "-" + UUID.randomUUID();
            futures.add(executor.submit(() -> {
                startLatch.await();
                int maxRetries = 50;
                for (int attempt = 0; attempt < maxRetries; attempt++) {
                    try {
                        refundService.createRefund(
                                merchantA.getId(),
                                payment.getId(),
                                idempotencyKey,
                                CreateRefundRequest.builder()
                                        .amountCents(2000L)
                                        .reason("Concurrent partial refund " + threadIndex)
                                        .build()
                        );
                        return true;
                    } catch (InvalidRefundException e) {
                        // Max refundable amount reached or payment already fully refunded
                        return false;
                    } catch (OptimisticLockingFailureException | DataIntegrityViolationException e) {
                        Thread.sleep(10 + (long) (Math.random() * 20));
                    } catch (Exception e) {
                        if (e.getCause() instanceof org.hibernate.StaleObjectStateException
                                || (e.getMessage() != null && e.getMessage().contains("Row was updated or deleted by another transaction"))) {
                            Thread.sleep(10 + (long) (Math.random() * 20));
                        } else {
                            throw e;
                        }
                    }
                }
                return false;
            }));
        }

        // Trigger all threads simultaneously
        startLatch.countDown();

        // Wait for all threads to complete
        int successfulRefunds = 0;
        for (Future<Boolean> future : futures) {
            if (future.get()) {
                successfulRefunds++;
            }
        }
        executor.shutdown();

        // Asserts sumRefundedAmountByPaymentId is strictly <= 10,000 cents
        Long totalRefundedAmount = refundRepository.sumRefundedAmountByPaymentId(payment.getId());
        assertThat(totalRefundedAmount).isNotNull();
        assertThat(totalRefundedAmount).isLessThanOrEqualTo(10000L);
        assertThat(totalRefundedAmount).isEqualTo(10000L);

        // Asserts successful refund count is exactly 5
        assertThat(successfulRefunds).isEqualTo(5);
        List<Refund> completedRefunds = refundRepository.findByPaymentId(payment.getId());
        assertThat(completedRefunds).hasSize(5);

        // Verify payment is in REFUNDED status
        Payment updatedPayment = paymentRepository.findById(payment.getId()).orElseThrow();
        assertThat(updatedPayment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);

        // Asserts all posted journals remain strictly balanced
        List<Journal> journals = journalRepository.findAll();
        assertThat(journals).isNotEmpty();
        assertThat(journals).hasSize(6); // 1 capture + 5 refunds = 6 journals

        for (Journal journal : journals) {
            List<LedgerEntry> entries = ledgerEntryRepository.findByJournalId(journal.getId());
            assertThat(entries).isNotEmpty();

            long totalDebit = entries.stream()
                    .filter(e -> e.getEntryType() == EntryType.DEBIT)
                    .mapToLong(LedgerEntry::getAmountCents)
                    .sum();

            long totalCredit = entries.stream()
                    .filter(e -> e.getEntryType() == EntryType.CREDIT)
                    .mapToLong(LedgerEntry::getAmountCents)
                    .sum();

            assertThat(totalDebit).isGreaterThan(0L);
            assertThat(totalCredit).isGreaterThan(0L);
            assertThat(totalDebit).isEqualTo(totalCredit);
        }
    }

    @Test
    @DisplayName("4. Multi-payment capture and full refund cumulative fee retention")
    void testMultiPaymentCaptureAndFullRefundCumulativeFeeRetention() {
        // Merchant creates Payment 1 ($150.00 / 15,000 cents) -> Captures -> Fully Refunds ($150.00)
        Payment p1 = createAuthorizedPayment(merchantA, customerA, 15000L);
        paymentService.capturePayment(merchantA.getId(), p1.getId(), "cap-p1-" + UUID.randomUUID());
        RefundResponse r1 = refundService.createRefund(
                merchantA.getId(),
                p1.getId(),
                "ref-p1-" + UUID.randomUUID(),
                CreateRefundRequest.builder().amountCents(15000L).reason("Full refund P1").build()
        );
        assertThat(r1.getStatus()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(r1.getAmountCents()).isEqualTo(15000L);

        // Merchant creates Payment 2 ($120.00 / 12,000 cents) -> Captures -> Fully Refunds ($120.00 across partial $40.00 + $80.00)
        Payment p2 = createAuthorizedPayment(merchantA, customerA, 12000L);
        paymentService.capturePayment(merchantA.getId(), p2.getId(), "cap-p2-" + UUID.randomUUID());

        RefundResponse r2_1 = refundService.createRefund(
                merchantA.getId(),
                p2.getId(),
                "ref-p2-1-" + UUID.randomUUID(),
                CreateRefundRequest.builder().amountCents(4000L).reason("Partial refund P2 1").build()
        );
        assertThat(r2_1.getStatus()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(r2_1.getAmountCents()).isEqualTo(4000L);

        RefundResponse r2_2 = refundService.createRefund(
                merchantA.getId(),
                p2.getId(),
                "ref-p2-2-" + UUID.randomUUID(),
                CreateRefundRequest.builder().amountCents(8000L).reason("Partial refund P2 2").build()
        );
        assertThat(r2_2.getStatus()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(r2_2.getAmountCents()).isEqualTo(8000L);

        // Asserts for Payment 1: Capture fee = $4.65 (30 cents fixed + $4.35 variable). Refund fee reversal = $4.35. Retained fee = $0.30. Net merchant position for P1 = -$0.30 (-30 cents).
        Journal p1CaptureJournal = journalRepository.findAll().stream()
                .filter(j -> j.getSourceId().equals(p1.getId().toString()))
                .findFirst().orElseThrow();
        List<LedgerEntry> p1CapEntries = ledgerEntryRepository.findByJournalId(p1CaptureJournal.getId());
        long p1CapFee = p1CapEntries.stream()
                .filter(e -> e.getAccount().getAccountType() == AccountType.REVENUE)
                .mapToLong(LedgerEntry::getAmountCents)
                .findFirst().orElse(0L);
        assertThat(p1CapFee).isEqualTo(465L); // $4.65 (30 cents fixed + $4.35 variable)

        Journal p1RefundJournal = journalRepository.findAll().stream()
                .filter(j -> j.getSourceId().equals(r1.getId().toString()))
                .findFirst().orElseThrow();
        List<LedgerEntry> p1RefEntries = ledgerEntryRepository.findByJournalId(p1RefundJournal.getId());
        long p1RefFeeReversal = p1RefEntries.stream()
                .filter(e -> e.getAccount().getAccountType() == AccountType.REVENUE)
                .mapToLong(LedgerEntry::getAmountCents)
                .findFirst().orElse(0L);
        assertThat(p1RefFeeReversal).isEqualTo(435L); // $4.35
        long p1RetainedFee = p1CapFee - p1RefFeeReversal;
        assertThat(p1RetainedFee).isEqualTo(30L); // Retained fee = $0.30

        long p1MerchantCapCredit = p1CapEntries.stream()
                .filter(e -> e.getAccount().getAccountType() == AccountType.LIABILITY && e.getEntryType() == EntryType.CREDIT)
                .mapToLong(LedgerEntry::getAmountCents)
                .findFirst().orElse(0L);
        long p1MerchantRefDebit = p1RefEntries.stream()
                .filter(e -> e.getAccount().getAccountType() == AccountType.LIABILITY && e.getEntryType() == EntryType.DEBIT)
                .mapToLong(LedgerEntry::getAmountCents)
                .findFirst().orElse(0L);
        long p1NetMerchantPosition = p1MerchantCapCredit - p1MerchantRefDebit;
        assertThat(p1NetMerchantPosition).isEqualTo(-30L); // Net merchant position for P1 = -$0.30 (-30 cents)

        // Asserts for Payment 2: Capture fee = $3.78 (30 cents fixed + $3.48 variable). Refund fee reversal = $3.48 ($1.16 + $2.32). Retained fee = $0.30. Net merchant position for P2 = -$0.30 (-30 cents).
        Journal p2CaptureJournal = journalRepository.findAll().stream()
                .filter(j -> j.getSourceId().equals(p2.getId().toString()))
                .findFirst().orElseThrow();
        List<LedgerEntry> p2CapEntries = ledgerEntryRepository.findByJournalId(p2CaptureJournal.getId());
        long p2CapFee = p2CapEntries.stream()
                .filter(e -> e.getAccount().getAccountType() == AccountType.REVENUE)
                .mapToLong(LedgerEntry::getAmountCents)
                .findFirst().orElse(0L);
        assertThat(p2CapFee).isEqualTo(378L); // $3.78 (30 cents fixed + $3.48 variable)

        Journal p2Ref1Journal = journalRepository.findAll().stream()
                .filter(j -> j.getSourceId().equals(r2_1.getId().toString()))
                .findFirst().orElseThrow();
        List<LedgerEntry> p2Ref1Entries = ledgerEntryRepository.findByJournalId(p2Ref1Journal.getId());
        long p2Ref1FeeReversal = p2Ref1Entries.stream()
                .filter(e -> e.getAccount().getAccountType() == AccountType.REVENUE)
                .mapToLong(LedgerEntry::getAmountCents)
                .findFirst().orElse(0L);
        assertThat(p2Ref1FeeReversal).isEqualTo(116L); // $1.16

        Journal p2Ref2Journal = journalRepository.findAll().stream()
                .filter(j -> j.getSourceId().equals(r2_2.getId().toString()))
                .findFirst().orElseThrow();
        List<LedgerEntry> p2Ref2Entries = ledgerEntryRepository.findByJournalId(p2Ref2Journal.getId());
        long p2Ref2FeeReversal = p2Ref2Entries.stream()
                .filter(e -> e.getAccount().getAccountType() == AccountType.REVENUE)
                .mapToLong(LedgerEntry::getAmountCents)
                .findFirst().orElse(0L);
        assertThat(p2Ref2FeeReversal).isEqualTo(232L); // $2.32

        long p2TotalFeeReversal = p2Ref1FeeReversal + p2Ref2FeeReversal;
        assertThat(p2TotalFeeReversal).isEqualTo(348L); // $3.48 ($1.16 + $2.32)
        long p2RetainedFee = p2CapFee - p2TotalFeeReversal;
        assertThat(p2RetainedFee).isEqualTo(30L); // Retained fee = $0.30

        long p2MerchantCapCredit = p2CapEntries.stream()
                .filter(e -> e.getAccount().getAccountType() == AccountType.LIABILITY && e.getEntryType() == EntryType.CREDIT)
                .mapToLong(LedgerEntry::getAmountCents)
                .findFirst().orElse(0L);
        long p2Ref1MerchantDebit = p2Ref1Entries.stream()
                .filter(e -> e.getAccount().getAccountType() == AccountType.LIABILITY && e.getEntryType() == EntryType.DEBIT)
                .mapToLong(LedgerEntry::getAmountCents)
                .findFirst().orElse(0L);
        long p2Ref2MerchantDebit = p2Ref2Entries.stream()
                .filter(e -> e.getAccount().getAccountType() == AccountType.LIABILITY && e.getEntryType() == EntryType.DEBIT)
                .mapToLong(LedgerEntry::getAmountCents)
                .findFirst().orElse(0L);
        long p2NetMerchantPosition = p2MerchantCapCredit - (p2Ref1MerchantDebit + p2Ref2MerchantDebit);
        assertThat(p2NetMerchantPosition).isEqualTo(-30L); // Net merchant position for P2 = -$0.30 (-30 cents)

        // Asserts MerchantBalanceService.getMerchantBalance:
        MerchantBalanceResponse balance = merchantBalanceService.getMerchantBalance(merchantA.getId());
        assertThat(balance.getMerchantId()).isEqualTo(merchantA.getId());
        assertThat(balance.getAvailableBalanceCents()).isEqualTo(-60L); // -$0.60 cumulative net balance
        assertThat(balance.getTotalVolumeCents()).isEqualTo(27000L); // $270.00 total volume
        assertThat(balance.getTotalRefundsCents()).isEqualTo(27000L); // $270.00 total refunds
        assertThat(balance.getTotalFeesCents()).isEqualTo(60L); // $0.60 cumulative platform fees retained
        assertThat(balance.getPendingSettlementCents()).isEqualTo(0L);
        assertThat(balance.getCurrency()).isEqualTo("USD");

        // Asserts every single journal in database is strictly balanced (totalDebit == totalCredit > 0)
        List<Journal> journals = journalRepository.findAll();
        assertThat(journals).hasSize(5); // 2 captures + 3 refunds = 5 journals
        List<LedgerEntry> allEntries = ledgerEntryRepository.findAll();
        assertThat(allEntries).isNotEmpty();

        for (Journal journal : journals) {
            List<LedgerEntry> entries = ledgerEntryRepository.findByJournalId(journal.getId());
            assertThat(entries).isNotEmpty();

            long totalDebit = entries.stream()
                    .filter(e -> e.getEntryType() == EntryType.DEBIT)
                    .mapToLong(LedgerEntry::getAmountCents)
                    .sum();

            long totalCredit = entries.stream()
                    .filter(e -> e.getEntryType() == EntryType.CREDIT)
                    .mapToLong(LedgerEntry::getAmountCents)
                    .sum();

            assertThat(totalDebit)
                    .as("Journal %s debit amount must be strictly positive", journal.getJournalNumber())
                    .isGreaterThan(0L);

            assertThat(totalCredit)
                    .as("Journal %s credit amount must be strictly positive", journal.getJournalNumber())
                    .isGreaterThan(0L);

            assertThat(totalDebit)
                    .as("Journal %s must be balanced: totalDebit (%d) == totalCredit (%d)",
                            journal.getJournalNumber(), totalDebit, totalCredit)
                    .isEqualTo(totalCredit);
        }

        // Global double-entry balance invariant across all accounts
        long globalSumDebit = allEntries.stream()
                .filter(e -> e.getEntryType() == EntryType.DEBIT)
                .mapToLong(LedgerEntry::getAmountCents)
                .sum();

        long globalSumCredit = allEntries.stream()
                .filter(e -> e.getEntryType() == EntryType.CREDIT)
                .mapToLong(LedgerEntry::getAmountCents)
                .sum();

        assertThat(globalSumDebit).isEqualTo(globalSumCredit);
    }
}
