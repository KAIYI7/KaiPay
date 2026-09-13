package com.lky.kaipay.ledger.service.dto;

import com.lky.kaipay.ledger.domain.Account;
import com.lky.kaipay.ledger.domain.EntryType;

public record LedgerEntryRequest(
        Account account,
        EntryType entryType,
        long amountCents,
        String currency
) {
}
