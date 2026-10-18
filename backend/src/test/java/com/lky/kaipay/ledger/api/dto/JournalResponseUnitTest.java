package com.lky.kaipay.ledger.api.dto;

import com.lky.kaipay.ledger.domain.Account;
import com.lky.kaipay.ledger.domain.AccountType;
import com.lky.kaipay.ledger.domain.EntryType;
import com.lky.kaipay.ledger.domain.Journal;
import com.lky.kaipay.ledger.domain.JournalSourceType;
import com.lky.kaipay.ledger.domain.LedgerEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("JournalResponse DTO Unit Tests")
class JournalResponseUnitTest {

    @Test
    void mismatchedStoredAccountKeepsEntriesButOmitsUnusableTotalsFromJson() throws Exception {
        Account usd = Account.builder().accountNumber("old-usd").currency("USD").build();
        Journal journal = Journal.builder().journalNumber("mismatch").build();
        journal.addEntry(LedgerEntry.builder().account(usd).entryType(EntryType.DEBIT)
                .amountCents(100).currency("MYR").build());
        journal.addEntry(LedgerEntry.builder().account(usd).entryType(EntryType.CREDIT)
                .amountCents(100).currency("MYR").build());
        JournalResponse response = JournalResponse.fromEntity(journal);
        assertThat(response.getCurrency()).isNull();
        assertThat(response.getTotalDebitCents()).isNull();
        assertThat(response.getTotalCreditCents()).isNull();
        var json = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(response);
        assertThat(json.has("currency")).isFalse();
        assertThat(json.has("totalDebitCents")).isFalse();
        assertThat(json.has("totalCreditCents")).isFalse();
        assertThat(json.path("entries")).hasSize(2);
        assertThat(json.path("entries").get(0).path("amountCents").asLong()).isEqualTo(100);
    }

    @Test
    @DisplayName("Should correctly map Journal entity to JournalResponse with computed totals")
    void shouldMapJournalToResponse() {
        Account account1 = Account.builder()
                .id(UUID.randomUUID())
                .accountNumber("1000-CUSTOMER-RECEIVABLE")
                .accountName("Receivable")
                .accountType(AccountType.ASSET)
                .currency("MYR")
                .build();

        Account account2 = Account.builder()
                .id(UUID.randomUUID())
                .accountNumber("2000-MERCHANT-LIABILITY")
                .accountName("Payable")
                .accountType(AccountType.LIABILITY)
                .currency("MYR")
                .build();

        Account account3 = Account.builder()
                .id(UUID.randomUUID())
                .accountNumber("4000-PLATFORM-REVENUE")
                .accountName("Revenue")
                .accountType(AccountType.REVENUE)
                .currency("MYR")
                .build();

        Journal journal = Journal.builder()
                .id(UUID.randomUUID())
                .journalNumber("JNL-TEST-001")
                .sourceType(JournalSourceType.PAYMENT_CAPTURE)
                .sourceId("pay_123")
                .eventId(UUID.randomUUID())
                .description("Test Capture")
                .postedAt(Instant.now())
                .build();

        LedgerEntry entry1 = LedgerEntry.builder()
                .id(UUID.randomUUID())
                .journal(journal)
                .account(account1)
                .entryType(EntryType.DEBIT)
                .amountCents(10000L)
                .currency("MYR")
                .build();

        LedgerEntry entry2 = LedgerEntry.builder()
                .id(UUID.randomUUID())
                .journal(journal)
                .account(account2)
                .entryType(EntryType.CREDIT)
                .amountCents(9680L)
                .currency("MYR")
                .build();

        LedgerEntry entry3 = LedgerEntry.builder()
                .id(UUID.randomUUID())
                .journal(journal)
                .account(account3)
                .entryType(EntryType.CREDIT)
                .amountCents(320L)
                .currency("MYR")
                .build();

        journal.addEntry(entry1);
        journal.addEntry(entry2);
        journal.addEntry(entry3);

        JournalResponse response = JournalResponse.fromEntity(journal);

        assertThat(response).isNotNull();
        assertThat(response.getId()).isEqualTo(journal.getId());
        assertThat(response.getJournalNumber()).isEqualTo("JNL-TEST-001");
        assertThat(response.getSourceType()).isEqualTo(JournalSourceType.PAYMENT_CAPTURE);
        assertThat(response.getSourceId()).isEqualTo("pay_123");
        assertThat(response.getDescription()).isEqualTo("Test Capture");
        assertThat(response.getTotalDebitCents()).isEqualTo(10000L);
        assertThat(response.getTotalCreditCents()).isEqualTo(10000L);
        assertThat(response.getEntries()).hasSize(3);

        LedgerEntryResponse entryResp1 = response.getEntries().get(0);
        assertThat(entryResp1.getAccountNumber()).isEqualTo("1000-CUSTOMER-RECEIVABLE");
        assertThat(entryResp1.getEntryType()).isEqualTo(EntryType.DEBIT);
        assertThat(entryResp1.getAmountCents()).isEqualTo(10000L);
    }

    @Test
    @DisplayName("Should handle null Journal entity gracefully")
    void shouldHandleNullJournal() {
        JournalResponse response = JournalResponse.fromEntity(null);
        assertThat(response).isNull();
    }
}
