package com.lky.kaipay.ledger.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.lky.kaipay.ledger.domain.EntryType;
import com.lky.kaipay.ledger.domain.Journal;
import com.lky.kaipay.ledger.domain.JournalSourceType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class JournalResponse {

    private UUID id;
    private String journalNumber;
    private JournalSourceType sourceType;
    private String sourceId;
    private UUID eventId;
    private String description;
    private Instant postedAt;
    private long totalDebitCents;
    private long totalCreditCents;
    private List<LedgerEntryResponse> entries;

    public static JournalResponse fromEntity(Journal journal) {
        if (journal == null) {
            return null;
        }
        List<LedgerEntryResponse> entryResponses = journal.getEntries() != null
                ? journal.getEntries().stream().map(LedgerEntryResponse::fromEntity).toList()
                : List.of();

        long totalDebitCents = entryResponses.stream()
                .filter(e -> e.getEntryType() == EntryType.DEBIT)
                .mapToLong(LedgerEntryResponse::getAmountCents)
                .sum();

        long totalCreditCents = entryResponses.stream()
                .filter(e -> e.getEntryType() == EntryType.CREDIT)
                .mapToLong(LedgerEntryResponse::getAmountCents)
                .sum();

        return JournalResponse.builder()
                .id(journal.getId())
                .journalNumber(journal.getJournalNumber())
                .sourceType(journal.getSourceType())
                .sourceId(journal.getSourceId())
                .eventId(journal.getEventId())
                .description(journal.getDescription())
                .postedAt(journal.getPostedAt())
                .totalDebitCents(totalDebitCents)
                .totalCreditCents(totalCreditCents)
                .entries(entryResponses)
                .build();
    }
}
