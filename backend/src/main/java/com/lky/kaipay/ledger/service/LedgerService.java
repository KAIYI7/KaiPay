package com.lky.kaipay.ledger.service;

import com.lky.kaipay.common.exception.InvalidJournalException;
import com.lky.kaipay.common.exception.UnbalancedJournalException;
import com.lky.kaipay.ledger.domain.Account;
import com.lky.kaipay.ledger.domain.AccountType;
import com.lky.kaipay.ledger.domain.EntryType;
import com.lky.kaipay.ledger.domain.Journal;
import com.lky.kaipay.ledger.domain.JournalSourceType;
import com.lky.kaipay.ledger.domain.LedgerEntry;
import com.lky.kaipay.ledger.repository.JournalRepository;
import com.lky.kaipay.ledger.repository.LedgerEntryRepository;
import com.lky.kaipay.ledger.service.dto.LedgerEntryRequest;
import com.lky.kaipay.merchant.domain.Merchant;
import com.lky.kaipay.payment.domain.Payment;
import com.lky.kaipay.refund.domain.Refund;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class LedgerService {

    private final JournalRepository journalRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final AccountProvisioningService accountProvisioningService;

    @Transactional
    public Journal postJournal(
            JournalSourceType sourceType,
            String sourceId,
            UUID eventId,
            Merchant merchant,
            String description,
            List<LedgerEntryRequest> entries
    ) {
        if (eventId != null) {
            Optional<Journal> existing = journalRepository.findBySourceTypeAndSourceIdAndEventId(sourceType, sourceId, eventId);
            if (existing.isPresent()) {
                log.info("Idempotent journal request detected for sourceType={}, sourceId={}, eventId={}. Returning existing journal {}",
                        sourceType, sourceId, eventId, existing.get().getJournalNumber());
                return existing.get();
            }
        }

        if (entries == null || entries.isEmpty()) {
            throw new InvalidJournalException("Journal entries list cannot be null or empty");
        }

        for (LedgerEntryRequest entry : entries) {
            if (entry.account() == null) {
                throw new InvalidJournalException("Ledger entry must have an associated account");
            }
            if (entry.entryType() == null) {
                throw new InvalidJournalException("Ledger entry must have an entry type (DEBIT or CREDIT)");
            }
            if (entry.amountCents() <= 0) {
                throw new InvalidJournalException("Ledger entry amount must be strictly greater than zero cents");
            }
        }

        long totalDebit = entries.stream()
                .filter(e -> e.entryType() == EntryType.DEBIT)
                .mapToLong(LedgerEntryRequest::amountCents)
                .sum();

        long totalCredit = entries.stream()
                .filter(e -> e.entryType() == EntryType.CREDIT)
                .mapToLong(LedgerEntryRequest::amountCents)
                .sum();

        if (totalDebit != totalCredit) {
            throw new UnbalancedJournalException(
                    String.format("Unbalanced journal: total debit (%d cents) does not match total credit (%d cents)",
                            totalDebit, totalCredit)
            );
        }

        if (totalDebit <= 0) {
            throw new InvalidJournalException("Journal total debit and credit amounts must be strictly positive");
        }

        String journalNumber = "JNL-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        Journal journal = Journal.builder()
                .journalNumber(journalNumber)
                .sourceType(sourceType)
                .sourceId(sourceId)
                .eventId(eventId)
                .merchant(merchant)
                .description(description)
                .build();

        for (LedgerEntryRequest req : entries) {
            LedgerEntry entry = LedgerEntry.builder()
                    .account(req.account())
                    .entryType(req.entryType())
                    .amountCents(req.amountCents())
                    .currency(req.currency() != null ? req.currency() : "USD")
                    .build();
            journal.addEntry(entry);
        }

        Journal savedJournal = journalRepository.save(journal);
        log.info("Posted journal {} for sourceType={}, sourceId={}, eventId={}, totalAmountCents={}",
                savedJournal.getJournalNumber(), sourceType, sourceId, eventId, totalDebit);

        return savedJournal;
    }

    @Transactional
    public Journal recordPaymentCapture(Payment payment, UUID eventId) {
        long feeCents = Math.round(payment.getAmountCents() * 0.029) + 30;
        feeCents = Math.min(feeCents, payment.getAmountCents());
        long netMerchantCents = payment.getAmountCents() - feeCents;

        Account receivable = accountProvisioningService.getOrCreateSystemAccount(
                AccountProvisioningService.SYS_CUSTOMER_RECEIVABLE_ACC,
                "Customer Funds Receivable",
                AccountType.ASSET,
                payment.getCurrency()
        );

        Account payable = accountProvisioningService.getOrCreateMerchantAccount(
                payment.getMerchant(),
                AccountType.LIABILITY,
                payment.getCurrency()
        );

        Account revenue = accountProvisioningService.getOrCreateSystemAccount(
                AccountProvisioningService.SYS_PLATFORM_REVENUE_ACC,
                "Platform Fee Revenue",
                AccountType.REVENUE,
                payment.getCurrency()
        );

        List<LedgerEntryRequest> entries = new ArrayList<>();
        entries.add(new LedgerEntryRequest(receivable, EntryType.DEBIT, payment.getAmountCents(), payment.getCurrency()));
        if (netMerchantCents > 0) {
            entries.add(new LedgerEntryRequest(payable, EntryType.CREDIT, netMerchantCents, payment.getCurrency()));
        }
        if (feeCents > 0) {
            entries.add(new LedgerEntryRequest(revenue, EntryType.CREDIT, feeCents, payment.getCurrency()));
        }

        return postJournal(
                JournalSourceType.PAYMENT_CAPTURE,
                payment.getId().toString(),
                eventId,
                payment.getMerchant(),
                "Payment capture for " + payment.getId(),
                entries
        );
    }

    @Transactional
    public Journal recordPaymentRefund(Payment payment, Refund refund, UUID eventId) {
        long feeRefundCents = Math.round(refund.getAmountCents() * 0.029);
        feeRefundCents = Math.min(feeRefundCents, refund.getAmountCents());
        long netMerchantRefundCents = refund.getAmountCents() - feeRefundCents;

        Account receivable = accountProvisioningService.getOrCreateSystemAccount(
                AccountProvisioningService.SYS_CUSTOMER_RECEIVABLE_ACC,
                "Customer Funds Receivable",
                AccountType.ASSET,
                refund.getCurrency()
        );

        Account payable = accountProvisioningService.getOrCreateMerchantAccount(
                payment.getMerchant(),
                AccountType.LIABILITY,
                refund.getCurrency()
        );

        Account revenue = accountProvisioningService.getOrCreateSystemAccount(
                AccountProvisioningService.SYS_PLATFORM_REVENUE_ACC,
                "Platform Fee Revenue",
                AccountType.REVENUE,
                refund.getCurrency()
        );

        List<LedgerEntryRequest> entries = new ArrayList<>();
        if (netMerchantRefundCents > 0) {
            entries.add(new LedgerEntryRequest(payable, EntryType.DEBIT, netMerchantRefundCents, refund.getCurrency()));
        }
        if (feeRefundCents > 0) {
            entries.add(new LedgerEntryRequest(revenue, EntryType.DEBIT, feeRefundCents, refund.getCurrency()));
        }
        entries.add(new LedgerEntryRequest(receivable, EntryType.CREDIT, refund.getAmountCents(), refund.getCurrency()));

        return postJournal(
                JournalSourceType.PAYMENT_REFUND,
                refund.getId().toString(),
                eventId,
                payment.getMerchant(),
                "Payment refund for " + payment.getId() + ", refund " + refund.getId(),
                entries
        );
    }

    @Transactional(readOnly = true)
    public Optional<Journal> getJournal(UUID id) {
        return journalRepository.findById(id);
    }

    @Transactional(readOnly = true)
    public Page<Journal> getMerchantJournals(UUID merchantId, Pageable pageable) {
        return journalRepository.findByMerchantIdOrderByPostedAtDesc(merchantId, pageable);
    }

    @Transactional(readOnly = true)
    public Page<Journal> getAllJournals(Pageable pageable) {
        return journalRepository.findAllByOrderByPostedAtDesc(pageable);
    }

    @Transactional(readOnly = true)
    public long getAccountBalance(UUID accountId) {
        Long balance = ledgerEntryRepository.calculateAccountBalance(accountId);
        return balance != null ? balance : 0L;
    }
}
