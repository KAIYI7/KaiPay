package com.lky.kaipay.ledger.service;

import com.lky.kaipay.common.exception.InvalidJournalException;
import com.lky.kaipay.common.exception.UnbalancedJournalException;
import com.lky.kaipay.ledger.domain.Account;
import com.lky.kaipay.ledger.domain.AccountType;
import com.lky.kaipay.ledger.domain.EntryType;
import com.lky.kaipay.ledger.domain.Journal;
import com.lky.kaipay.ledger.domain.JournalSourceType;
import com.lky.kaipay.ledger.repository.JournalRepository;
import com.lky.kaipay.ledger.repository.LedgerEntryRepository;
import com.lky.kaipay.ledger.service.dto.LedgerEntryRequest;
import com.lky.kaipay.merchant.domain.Merchant;
import com.lky.kaipay.merchant.domain.MerchantStatus;
import com.lky.kaipay.payment.domain.Payment;
import com.lky.kaipay.refund.domain.Refund;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("LedgerService Unit Tests")
class LedgerServiceUnitTest {

    @Mock
    private JournalRepository journalRepository;

    @Mock
    private LedgerEntryRepository ledgerEntryRepository;

    @Mock
    private AccountProvisioningService accountProvisioningService;

    @InjectMocks
    private LedgerService ledgerService;

    private Merchant merchant;
    private Account receivableAccount;
    private Account payableAccount;
    private Account revenueAccount;

    @BeforeEach
    void setUp() {
        merchant = Merchant.builder()
                .id(UUID.randomUUID())
                .name("Acme Corp")
                .apiKeyHash("test-hash")
                .status(MerchantStatus.ACTIVE)
                .build();

        receivableAccount = Account.builder()
                .id(UUID.randomUUID())
                .accountNumber(AccountProvisioningService.SYS_CUSTOMER_RECEIVABLE_ACC)
                .accountName("Customer Funds Receivable")
                .accountType(AccountType.ASSET)
                .currency("USD")
                .build();

        payableAccount = Account.builder()
                .id(UUID.randomUUID())
                .merchant(merchant)
                .accountNumber("2000-MERCHANT-" + merchant.getId() + "-LIABILITY")
                .accountName("Acme Corp LIABILITY Account")
                .accountType(AccountType.LIABILITY)
                .currency("USD")
                .build();

        revenueAccount = Account.builder()
                .id(UUID.randomUUID())
                .accountNumber(AccountProvisioningService.SYS_PLATFORM_REVENUE_ACC)
                .accountName("Platform Fee Revenue")
                .accountType(AccountType.REVENUE)
                .currency("USD")
                .build();
    }

    @Nested
    @DisplayName("postJournal")
    class PostJournalTests {

        @Test
        @DisplayName("Should successfully post a balanced journal")
        void shouldPostBalancedJournal() {
            UUID eventId = UUID.randomUUID();
            String sourceId = "pay_12345";
            List<LedgerEntryRequest> entries = List.of(
                    new LedgerEntryRequest(receivableAccount, EntryType.DEBIT, 10000L, "USD"),
                    new LedgerEntryRequest(payableAccount, EntryType.CREDIT, 9680L, "USD"),
                    new LedgerEntryRequest(revenueAccount, EntryType.CREDIT, 320L, "USD")
            );

            when(journalRepository.findBySourceTypeAndSourceIdAndEventId(JournalSourceType.PAYMENT_CAPTURE, sourceId, eventId))
                    .thenReturn(Optional.empty());
            when(journalRepository.save(any(Journal.class))).thenAnswer(invocation -> invocation.getArgument(0));

            Journal result = ledgerService.postJournal(
                    JournalSourceType.PAYMENT_CAPTURE,
                    sourceId,
                    eventId,
                    merchant,
                    "Payment capture",
                    entries
            );

            assertThat(result).isNotNull();
            assertThat(result.getJournalNumber()).startsWith("JNL-");
            assertThat(result.getSourceType()).isEqualTo(JournalSourceType.PAYMENT_CAPTURE);
            assertThat(result.getSourceId()).isEqualTo(sourceId);
            assertThat(result.getEventId()).isEqualTo(eventId);
            assertThat(result.getMerchant()).isEqualTo(merchant);
            assertThat(result.getEntries()).hasSize(3);

            verify(journalRepository).save(any(Journal.class));
        }

        @Test
        @DisplayName("Should return existing journal when eventId matches for idempotency")
        void shouldReturnExistingJournalForIdempotentEvent() {
            UUID eventId = UUID.randomUUID();
            String sourceId = "pay_12345";
            Journal existingJournal = Journal.builder()
                    .journalNumber("JNL-EXISTING")
                    .sourceType(JournalSourceType.PAYMENT_CAPTURE)
                    .sourceId(sourceId)
                    .eventId(eventId)
                    .build();

            when(journalRepository.findBySourceTypeAndSourceIdAndEventId(JournalSourceType.PAYMENT_CAPTURE, sourceId, eventId))
                    .thenReturn(Optional.of(existingJournal));

            List<LedgerEntryRequest> entries = List.of(
                    new LedgerEntryRequest(receivableAccount, EntryType.DEBIT, 10000L, "USD"),
                    new LedgerEntryRequest(payableAccount, EntryType.CREDIT, 10000L, "USD")
            );

            Journal result = ledgerService.postJournal(
                    JournalSourceType.PAYMENT_CAPTURE,
                    sourceId,
                    eventId,
                    merchant,
                    "Duplicate request",
                    entries
            );

            assertThat(result).isSameAs(existingJournal);
            verify(journalRepository, never()).save(any(Journal.class));
        }

        @Test
        @DisplayName("Should throw UnbalancedJournalException when total debit does not match total credit")
        void shouldThrowUnbalancedJournalException() {
            UUID eventId = UUID.randomUUID();
            String sourceId = "pay_unbalanced";
            List<LedgerEntryRequest> entries = List.of(
                    new LedgerEntryRequest(receivableAccount, EntryType.DEBIT, 10000L, "USD"),
                    new LedgerEntryRequest(payableAccount, EntryType.CREDIT, 9000L, "USD")
            );

            when(journalRepository.findBySourceTypeAndSourceIdAndEventId(JournalSourceType.PAYMENT_CAPTURE, sourceId, eventId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> ledgerService.postJournal(
                    JournalSourceType.PAYMENT_CAPTURE,
                    sourceId,
                    eventId,
                    merchant,
                    "Unbalanced journal",
                    entries
            ))
                    .isInstanceOf(UnbalancedJournalException.class)
                    .hasMessageContaining("total debit (10000 cents) does not match total credit (9000 cents)");

            verify(journalRepository, never()).save(any(Journal.class));
        }

        @Test
        @DisplayName("Should throw InvalidJournalException when entries list is empty")
        void shouldThrowInvalidJournalExceptionForEmptyEntries() {
            UUID eventId = UUID.randomUUID();
            when(journalRepository.findBySourceTypeAndSourceIdAndEventId(JournalSourceType.PAYMENT_CAPTURE, "pay_1", eventId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> ledgerService.postJournal(
                    JournalSourceType.PAYMENT_CAPTURE,
                    "pay_1",
                    eventId,
                    merchant,
                    "Empty entries",
                    Collections.emptyList()
            )).isInstanceOf(InvalidJournalException.class);
        }

        @Test
        @DisplayName("Should throw InvalidJournalException when entry amount is zero or negative")
        void shouldThrowInvalidJournalExceptionForZeroOrNegativeAmount() {
            UUID eventId = UUID.randomUUID();
            when(journalRepository.findBySourceTypeAndSourceIdAndEventId(JournalSourceType.PAYMENT_CAPTURE, "pay_1", eventId))
                    .thenReturn(Optional.empty());

            List<LedgerEntryRequest> entries = List.of(
                    new LedgerEntryRequest(receivableAccount, EntryType.DEBIT, -100L, "USD"),
                    new LedgerEntryRequest(payableAccount, EntryType.CREDIT, -100L, "USD")
            );

            assertThatThrownBy(() -> ledgerService.postJournal(
                    JournalSourceType.PAYMENT_CAPTURE,
                    "pay_1",
                    eventId,
                    merchant,
                    "Negative amount entries",
                    entries
            )).isInstanceOf(InvalidJournalException.class);
        }

        @Test
        @DisplayName("Should throw InvalidJournalException when entry account is null")
        void shouldThrowInvalidJournalExceptionForNullAccount() {
            UUID eventId = UUID.randomUUID();
            when(journalRepository.findBySourceTypeAndSourceIdAndEventId(JournalSourceType.PAYMENT_CAPTURE, "pay_1", eventId))
                    .thenReturn(Optional.empty());

            List<LedgerEntryRequest> entries = List.of(
                    new LedgerEntryRequest(null, EntryType.DEBIT, 1000L, "USD"),
                    new LedgerEntryRequest(payableAccount, EntryType.CREDIT, 1000L, "USD")
            );

            assertThatThrownBy(() -> ledgerService.postJournal(
                    JournalSourceType.PAYMENT_CAPTURE,
                    "pay_1",
                    eventId,
                    merchant,
                    "Null account entry",
                    entries
            )).isInstanceOf(InvalidJournalException.class);
        }
    }

    @Nested
    @DisplayName("recordPaymentCapture")
    class RecordPaymentCaptureTests {

        @Test
        @DisplayName("Should record payment capture with correct 2.9% + 30c fee split and balanced entries")
        void shouldRecordPaymentCaptureWithFeeSplit() {
            UUID paymentId = UUID.randomUUID();
            UUID eventId = UUID.randomUUID();
            Payment payment = Payment.builder()
                    .id(paymentId)
                    .merchant(merchant)
                    .amountCents(10000L) // $100.00
                    .currency("USD")
                    .build();

            // fee = round(10000 * 0.029) + 30 = 290 + 30 = 320 cents ($3.20)
            // netMerchant = 10000 - 320 = 9680 cents ($96.80)

            when(accountProvisioningService.getOrCreateSystemAccount(
                    AccountProvisioningService.SYS_CUSTOMER_RECEIVABLE_ACC, "Customer Funds Receivable", AccountType.ASSET, "USD"))
                    .thenReturn(receivableAccount);
            when(accountProvisioningService.getOrCreateMerchantAccount(merchant, AccountType.LIABILITY, "USD"))
                    .thenReturn(payableAccount);
            when(accountProvisioningService.getOrCreateSystemAccount(
                    AccountProvisioningService.SYS_PLATFORM_REVENUE_ACC, "Platform Fee Revenue", AccountType.REVENUE, "USD"))
                    .thenReturn(revenueAccount);

            when(journalRepository.findBySourceTypeAndSourceIdAndEventId(JournalSourceType.PAYMENT_CAPTURE, paymentId.toString(), eventId))
                    .thenReturn(Optional.empty());
            when(journalRepository.save(any(Journal.class))).thenAnswer(invocation -> invocation.getArgument(0));

            Journal journal = ledgerService.recordPaymentCapture(payment, eventId);

            assertThat(journal).isNotNull();
            assertThat(journal.getSourceType()).isEqualTo(JournalSourceType.PAYMENT_CAPTURE);
            assertThat(journal.getSourceId()).isEqualTo(paymentId.toString());
            assertThat(journal.getEntries()).hasSize(3);

            // Verify DEBIT receivable = 10000
            // CREDIT payable = 9680
            // CREDIT revenue = 320
            long debitReceivable = journal.getEntries().stream()
                    .filter(e -> e.getAccount().equals(receivableAccount) && e.getEntryType() == EntryType.DEBIT)
                    .mapToLong(com.lky.kaipay.ledger.domain.LedgerEntry::getAmountCents)
                    .sum();
            long creditPayable = journal.getEntries().stream()
                    .filter(e -> e.getAccount().equals(payableAccount) && e.getEntryType() == EntryType.CREDIT)
                    .mapToLong(com.lky.kaipay.ledger.domain.LedgerEntry::getAmountCents)
                    .sum();
            long creditRevenue = journal.getEntries().stream()
                    .filter(e -> e.getAccount().equals(revenueAccount) && e.getEntryType() == EntryType.CREDIT)
                    .mapToLong(com.lky.kaipay.ledger.domain.LedgerEntry::getAmountCents)
                    .sum();

            assertThat(debitReceivable).isEqualTo(10000L);
            assertThat(creditPayable).isEqualTo(9680L);
            assertThat(creditRevenue).isEqualTo(320L);
            assertThat(creditPayable + creditRevenue).isEqualTo(debitReceivable);
        }
    }

    @Nested
    @DisplayName("recordPaymentRefund")
    class RecordPaymentRefundTests {

        @Test
        @DisplayName("Should record payment refund with proportional fee reversal and balanced entries")
        void shouldRecordPaymentRefundWithFeeReversal() {
            UUID paymentId = UUID.randomUUID();
            UUID refundId = UUID.randomUUID();
            UUID eventId = UUID.randomUUID();

            Payment payment = Payment.builder()
                    .id(paymentId)
                    .merchant(merchant)
                    .amountCents(10000L)
                    .currency("USD")
                    .build();

            Refund refund = Refund.builder()
                    .id(refundId)
                    .payment(payment)
                    .merchant(merchant)
                    .amountCents(5000L) // $50.00 refund
                    .currency("USD")
                    .build();

            // feeRefund = round(5000 * 0.029) = 145 cents ($1.45)
            // netMerchantRefund = 5000 - 145 = 4855 cents ($48.55)

            when(accountProvisioningService.getOrCreateSystemAccount(
                    AccountProvisioningService.SYS_CUSTOMER_RECEIVABLE_ACC, "Customer Funds Receivable", AccountType.ASSET, "USD"))
                    .thenReturn(receivableAccount);
            when(accountProvisioningService.getOrCreateMerchantAccount(merchant, AccountType.LIABILITY, "USD"))
                    .thenReturn(payableAccount);
            when(accountProvisioningService.getOrCreateSystemAccount(
                    AccountProvisioningService.SYS_PLATFORM_REVENUE_ACC, "Platform Fee Revenue", AccountType.REVENUE, "USD"))
                    .thenReturn(revenueAccount);

            when(journalRepository.findBySourceTypeAndSourceIdAndEventId(JournalSourceType.PAYMENT_REFUND, refundId.toString(), eventId))
                    .thenReturn(Optional.empty());
            when(journalRepository.save(any(Journal.class))).thenAnswer(invocation -> invocation.getArgument(0));

            Journal journal = ledgerService.recordPaymentRefund(payment, refund, eventId);

            assertThat(journal).isNotNull();
            assertThat(journal.getSourceType()).isEqualTo(JournalSourceType.PAYMENT_REFUND);
            assertThat(journal.getSourceId()).isEqualTo(refundId.toString());
            assertThat(journal.getEntries()).hasSize(3);

            // Verify DEBIT payable = 4855
            // DEBIT revenue = 145
            // CREDIT receivable = 5000
            long debitPayable = journal.getEntries().stream()
                    .filter(e -> e.getAccount().equals(payableAccount) && e.getEntryType() == EntryType.DEBIT)
                    .mapToLong(com.lky.kaipay.ledger.domain.LedgerEntry::getAmountCents)
                    .sum();
            long debitRevenue = journal.getEntries().stream()
                    .filter(e -> e.getAccount().equals(revenueAccount) && e.getEntryType() == EntryType.DEBIT)
                    .mapToLong(com.lky.kaipay.ledger.domain.LedgerEntry::getAmountCents)
                    .sum();
            long creditReceivable = journal.getEntries().stream()
                    .filter(e -> e.getAccount().equals(receivableAccount) && e.getEntryType() == EntryType.CREDIT)
                    .mapToLong(com.lky.kaipay.ledger.domain.LedgerEntry::getAmountCents)
                    .sum();

            assertThat(debitPayable).isEqualTo(4855L);
            assertThat(debitRevenue).isEqualTo(145L);
            assertThat(creditReceivable).isEqualTo(5000L);
            assertThat(debitPayable + debitRevenue).isEqualTo(creditReceivable);
        }
    }

    @Nested
    @DisplayName("getAccountBalance")
    class GetAccountBalanceTests {

        @Test
        @DisplayName("Should return net balance from ledgerEntryRepository")
        void shouldReturnCalculatedBalance() {
            UUID accountId = UUID.randomUUID();
            when(ledgerEntryRepository.calculateAccountBalance(accountId)).thenReturn(15000L);

            long balance = ledgerService.getAccountBalance(accountId);
            assertThat(balance).isEqualTo(15000L);
        }

        @Test
        @DisplayName("Should return 0 when repository returns null")
        void shouldReturnZeroWhenNull() {
            UUID accountId = UUID.randomUUID();
            when(ledgerEntryRepository.calculateAccountBalance(accountId)).thenReturn(null);

            long balance = ledgerService.getAccountBalance(accountId);
            assertThat(balance).isEqualTo(0L);
        }
    }
}
