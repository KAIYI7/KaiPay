package com.lky.kaipay.security;

import com.lky.kaipay.common.exception.EntityNotFoundException;
import com.lky.kaipay.ledger.api.dto.JournalResponse;
import com.lky.kaipay.ledger.domain.Journal;
import com.lky.kaipay.ledger.service.LedgerService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class JournalApplicationService {
    private final ResourceAccess access;
    private final LedgerService ledger;

    @Transactional(readOnly = true)
    public Page<JournalResponse> list(UUID merchantId, Pageable pageable) {
        if (access.isAdmin()) {
            globalOnly(merchantId);
            return ledger.getAllJournals(pageable).map(JournalResponse::fromEntity);
        }
        access.merchant(merchantId);
        return ledger.getMerchantJournals(merchantId, pageable).map(journal -> merchantProjection(journal, merchantId));
    }
    @Transactional(readOnly = true)
    public JournalResponse get(UUID journalId, UUID merchantId) {
        if (access.isAdmin()) globalOnly(merchantId); else access.merchant(merchantId);
        Journal journal = ledger.getJournal(journalId).orElseThrow(() -> new EntityNotFoundException("Journal not found"));
        return access.isAdmin() ? JournalResponse.fromEntity(journal) : merchantProjection(journal, merchantId);
    }
    private void globalOnly(UUID merchantId) {
        access.admin();
        if (merchantId != null) throw new AccessDeniedException("Access denied");
    }
    private JournalResponse merchantProjection(Journal journal, UUID merchantId) {
        if (journal.getMerchant() == null || !merchantId.equals(journal.getMerchant().getId())
                || journal.getEntries().stream().anyMatch(entry -> entry.getAccount().getMerchant() != null
                    && !merchantId.equals(entry.getAccount().getMerchant().getId()))) {
            throw new EntityNotFoundException("Journal not found");
        }
        // System balancing lines in this merchant's journal preserve the established journal contract.
        return JournalResponse.fromEntity(journal);
    }
}
