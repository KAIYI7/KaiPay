package com.lky.kaipay.ledger.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.lky.kaipay.ledger.domain.AccountType;
import com.lky.kaipay.ledger.domain.EntryType;
import com.lky.kaipay.ledger.domain.LedgerEntry;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LedgerEntryResponse {

    private UUID id;
    private String accountNumber;
    private String accountName;
    private AccountType accountType;
    private EntryType entryType;
    private long amountCents;
    private String currency;

    public static LedgerEntryResponse fromEntity(LedgerEntry entry) {
        if (entry == null) {
            return null;
        }
        return LedgerEntryResponse.builder()
                .id(entry.getId())
                .accountNumber(entry.getAccount() != null ? entry.getAccount().getAccountNumber() : null)
                .accountName(entry.getAccount() != null ? entry.getAccount().getAccountName() : null)
                .accountType(entry.getAccount() != null ? entry.getAccount().getAccountType() : null)
                .entryType(entry.getEntryType())
                .amountCents(entry.getAmountCents())
                .currency(entry.getCurrency())
                .build();
    }
}
