package com.lky.kaipay.ledger.repository;

import com.lky.kaipay.ledger.domain.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

    List<LedgerEntry> findByJournalId(UUID journalId);

    @Query("SELECT COALESCE(SUM(CASE WHEN e.entryType = 'CREDIT' THEN e.amountCents ELSE -e.amountCents END), 0L) FROM LedgerEntry e WHERE e.account.id = :accountId")
    Long calculateAccountBalance(@Param("accountId") UUID accountId);

    @Query("SELECT COALESCE(SUM(CASE WHEN e.entryType = 'CREDIT' THEN e.amountCents ELSE -e.amountCents END), 0L) FROM LedgerEntry e WHERE e.journal.merchant.id = :merchantId AND e.account.accountType = com.lky.kaipay.ledger.domain.AccountType.REVENUE")
    Long calculateMerchantFees(@Param("merchantId") UUID merchantId);
}
