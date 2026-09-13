package com.lky.kaipay.ledger.repository;

import com.lky.kaipay.AbstractPostgresIntegrationTest;
import com.lky.kaipay.ledger.domain.Account;
import com.lky.kaipay.ledger.domain.AccountType;
import com.lky.kaipay.ledger.domain.EntryType;
import com.lky.kaipay.ledger.domain.Journal;
import com.lky.kaipay.ledger.domain.JournalSourceType;
import com.lky.kaipay.ledger.domain.LedgerEntry;
import com.lky.kaipay.merchant.domain.Merchant;
import com.lky.kaipay.merchant.domain.MerchantStatus;
import com.lky.kaipay.merchant.repository.MerchantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Ledger Repositories Integration Tests")
class LedgerRepositoryIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private JournalRepository journalRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private MerchantRepository merchantRepository;

    private Merchant merchant;

    @BeforeEach
    void setUp() {
        cleanup();

        merchant = merchantRepository.save(
                Merchant.builder()
                        .name("Ledger Test Merchant " + UUID.randomUUID())
                        .apiKeyHash("hash_" + UUID.randomUUID())
                        .status(MerchantStatus.ACTIVE)
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
        ledgerEntryRepository.deleteAll();
        journalRepository.deleteAll();
        accountRepository.deleteAll();
    }

    @Test
    @DisplayName("Persist and query accounts by number, merchant, and account type")
    void testSaveAndFindAccounts() {
        Account merchantAccount = accountRepository.save(
                Account.builder()
                        .merchant(merchant)
                        .accountNumber("ACCT-MERCHANT-" + UUID.randomUUID())
                        .accountName("Merchant Payable Account")
                        .accountType(AccountType.LIABILITY)
                        .currency("USD")
                        .build()
        );

        Account systemAccount = accountRepository.save(
                Account.builder()
                        .merchant(null)
                        .accountNumber("ACCT-SYS-" + UUID.randomUUID())
                        .accountName("System Clearing Account")
                        .accountType(AccountType.ASSET)
                        .currency("USD")
                        .build()
        );

        Optional<Account> foundByNum = accountRepository.findByAccountNumber(merchantAccount.getAccountNumber());
        assertThat(foundByNum).isPresent();
        assertThat(foundByNum.get().getAccountName()).isEqualTo("Merchant Payable Account");
        assertThat(foundByNum.get().getAccountType()).isEqualTo(AccountType.LIABILITY);

        Optional<Account> foundByMerchantAndType = accountRepository.findByMerchantIdAndAccountType(
                merchant.getId(),
                AccountType.LIABILITY
        );
        assertThat(foundByMerchantAndType).isPresent();
        assertThat(foundByMerchantAndType.get().getId()).isEqualTo(merchantAccount.getId());

        List<Account> merchantAccounts = accountRepository.findByMerchantId(merchant.getId());
        assertThat(merchantAccounts).hasSize(1);
        assertThat(merchantAccounts.get(0).getId()).isEqualTo(merchantAccount.getId());

        Optional<Account> foundSystemAccount = accountRepository.findByAccountNumber(systemAccount.getAccountNumber());
        assertThat(foundSystemAccount).isPresent();
        assertThat(foundSystemAccount.get().getMerchant()).isNull();
        assertThat(foundSystemAccount.get().getAccountType()).isEqualTo(AccountType.ASSET);
    }

    @Test
    @DisplayName("Account number unique constraint is enforced")
    void testAccountUniqueConstraint() {
        String accountNumber = "ACCT-UNIQUE-" + UUID.randomUUID();

        accountRepository.save(
                Account.builder()
                        .merchant(merchant)
                        .accountNumber(accountNumber)
                        .accountName("Account 1")
                        .accountType(AccountType.LIABILITY)
                        .currency("USD")
                        .build()
        );

        assertThatThrownBy(() -> {
            accountRepository.saveAndFlush(
                    Account.builder()
                            .merchant(merchant)
                            .accountNumber(accountNumber)
                            .accountName("Account 2")
                            .accountType(AccountType.EXPENSE)
                            .currency("USD")
                            .build()
            );
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Journal cascade persists LedgerEntry entities and supports findBySourceTypeAndSourceIdAndEventId")
    void testJournalWithCascadeEntries() {
        Account clearingAccount = accountRepository.save(
                Account.builder()
                        .accountNumber("ACCT-CLEARING-" + UUID.randomUUID())
                        .accountName("Clearing")
                        .accountType(AccountType.ASSET)
                        .currency("USD")
                        .build()
        );

        Account merchantAccount = accountRepository.save(
                Account.builder()
                        .merchant(merchant)
                        .accountNumber("ACCT-PAYABLE-" + UUID.randomUUID())
                        .accountName("Payable")
                        .accountType(AccountType.LIABILITY)
                        .currency("USD")
                        .build()
        );

        UUID eventId = UUID.randomUUID();
        String sourceId = "pay_" + UUID.randomUUID();

        Journal journal = Journal.builder()
                .journalNumber("JRN-" + UUID.randomUUID())
                .sourceType(JournalSourceType.PAYMENT_CAPTURE)
                .sourceId(sourceId)
                .eventId(eventId)
                .merchant(merchant)
                .description("Payment capture for " + sourceId)
                .build();

        LedgerEntry debitEntry = LedgerEntry.builder()
                .journal(journal)
                .account(clearingAccount)
                .entryType(EntryType.DEBIT)
                .amountCents(10000L)
                .currency("USD")
                .build();

        LedgerEntry creditEntry = LedgerEntry.builder()
                .journal(journal)
                .account(merchantAccount)
                .entryType(EntryType.CREDIT)
                .amountCents(10000L)
                .currency("USD")
                .build();

        journal.addEntry(debitEntry);
        journal.addEntry(creditEntry);

        Journal savedJournal = journalRepository.save(journal);
        assertThat(savedJournal.getId()).isNotNull();
        assertThat(savedJournal.getPostedAt()).isNotNull();

        Optional<Journal> foundJournal = journalRepository.findBySourceTypeAndSourceIdAndEventId(
                JournalSourceType.PAYMENT_CAPTURE,
                sourceId,
                eventId
        );
        assertThat(foundJournal).isPresent();
        assertThat(foundJournal.get().getJournalNumber()).isEqualTo(savedJournal.getJournalNumber());

        List<LedgerEntry> entries = ledgerEntryRepository.findByJournalId(savedJournal.getId());
        assertThat(entries).hasSize(2);
    }

    @Test
    @DisplayName("Journal pagination ordered by postedAt descending")
    void testJournalPagination() {
        for (int i = 0; i < 5; i++) {
            journalRepository.save(
                    Journal.builder()
                            .journalNumber("JRN-PAGE-" + i + "-" + UUID.randomUUID())
                            .sourceType(JournalSourceType.PAYMENT_CAPTURE)
                            .sourceId("pay_" + i)
                            .eventId(UUID.randomUUID())
                            .merchant(merchant)
                            .description("Journal description " + i)
                            .build()
            );
        }

        Page<Journal> merchantJournals = journalRepository.findByMerchantIdOrderByPostedAtDesc(
                merchant.getId(),
                PageRequest.of(0, 3)
        );
        assertThat(merchantJournals.getContent()).hasSize(3);
        assertThat(merchantJournals.getTotalElements()).isEqualTo(5);

        Page<Journal> allJournals = journalRepository.findAllByOrderByPostedAtDesc(PageRequest.of(0, 10));
        assertThat(allJournals.getContent()).hasSize(5);
    }

    @Test
    @DisplayName("Journal unique constraint on source_type, source_id, and event_id")
    void testJournalUniqueConstraintOnSourceAndEvent() {
        UUID eventId = UUID.randomUUID();
        String sourceId = "pay_unique_test";

        journalRepository.save(
                Journal.builder()
                        .journalNumber("JRN-1-" + UUID.randomUUID())
                        .sourceType(JournalSourceType.PAYMENT_CAPTURE)
                        .sourceId(sourceId)
                        .eventId(eventId)
                        .merchant(merchant)
                        .description("First journal")
                        .build()
        );

        assertThatThrownBy(() -> {
            journalRepository.saveAndFlush(
                    Journal.builder()
                            .journalNumber("JRN-2-" + UUID.randomUUID())
                            .sourceType(JournalSourceType.PAYMENT_CAPTURE)
                            .sourceId(sourceId)
                            .eventId(eventId)
                            .merchant(merchant)
                            .description("Duplicate journal")
                            .build()
            );
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("calculateAccountBalance computes net balance (CREDIT - DEBIT) correctly")
    void testCalculateAccountBalance() {
        Account account = accountRepository.save(
                Account.builder()
                        .merchant(merchant)
                        .accountNumber("ACCT-BAL-" + UUID.randomUUID())
                        .accountName("Merchant Balance Account")
                        .accountType(AccountType.LIABILITY)
                        .currency("USD")
                        .build()
        );

        // Balance with no entries should be 0
        Long initialBalance = ledgerEntryRepository.calculateAccountBalance(account.getId());
        assertThat(initialBalance).isEqualTo(0L);

        // Add credit entry (+5000)
        Journal journal1 = journalRepository.save(
                Journal.builder()
                        .journalNumber("JRN-BAL-1-" + UUID.randomUUID())
                        .sourceType(JournalSourceType.PAYMENT_CAPTURE)
                        .sourceId("pay_bal_1")
                        .merchant(merchant)
                        .description("Credit journal")
                        .build()
        );
        ledgerEntryRepository.save(
                LedgerEntry.builder()
                        .journal(journal1)
                        .account(account)
                        .entryType(EntryType.CREDIT)
                        .amountCents(5000L)
                        .currency("USD")
                        .build()
        );

        Long balanceAfterCredit = ledgerEntryRepository.calculateAccountBalance(account.getId());
        assertThat(balanceAfterCredit).isEqualTo(5000L);

        // Add debit entry (-1500)
        Journal journal2 = journalRepository.save(
                Journal.builder()
                        .journalNumber("JRN-BAL-2-" + UUID.randomUUID())
                        .sourceType(JournalSourceType.PAYMENT_REFUND)
                        .sourceId("pay_bal_2")
                        .merchant(merchant)
                        .description("Debit journal")
                        .build()
        );
        ledgerEntryRepository.save(
                LedgerEntry.builder()
                        .journal(journal2)
                        .account(account)
                        .entryType(EntryType.DEBIT)
                        .amountCents(1500L)
                        .currency("USD")
                        .build()
        );

        Long balanceAfterDebit = ledgerEntryRepository.calculateAccountBalance(account.getId());
        assertThat(balanceAfterDebit).isEqualTo(3500L);

        // Add another credit (+2000) -> 5500
        ledgerEntryRepository.save(
                LedgerEntry.builder()
                        .journal(journal1)
                        .account(account)
                        .entryType(EntryType.CREDIT)
                        .amountCents(2000L)
                        .currency("USD")
                        .build()
        );
        assertThat(ledgerEntryRepository.calculateAccountBalance(account.getId())).isEqualTo(5500L);

        // Add larger debit (-6000) -> -500
        ledgerEntryRepository.save(
                LedgerEntry.builder()
                        .journal(journal2)
                        .account(account)
                        .entryType(EntryType.DEBIT)
                        .amountCents(6000L)
                        .currency("USD")
                        .build()
        );
        assertThat(ledgerEntryRepository.calculateAccountBalance(account.getId())).isEqualTo(-500L);
    }
}
