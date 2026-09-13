package com.lky.kaipay.ledger.api;

import com.lky.kaipay.common.api.ApiResponse;
import com.lky.kaipay.common.exception.EntityNotFoundException;
import com.lky.kaipay.ledger.api.dto.JournalResponse;
import com.lky.kaipay.ledger.api.dto.MerchantBalanceResponse;
import com.lky.kaipay.ledger.domain.Journal;
import com.lky.kaipay.ledger.service.LedgerService;
import com.lky.kaipay.ledger.service.MerchantBalanceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/v1/ledger")
@RequiredArgsConstructor
public class LedgerController {

    private final MerchantBalanceService merchantBalanceService;
    private final LedgerService ledgerService;

    @GetMapping("/balance")
    public ResponseEntity<ApiResponse<MerchantBalanceResponse>> getBalance(
            @RequestHeader(name = "X-Merchant-Id") UUID merchantId
    ) {
        log.info("Received request for merchant balance for merchant {}", merchantId);
        MerchantBalanceResponse response = merchantBalanceService.getMerchantBalance(merchantId);
        return ResponseEntity.ok(ApiResponse.success("Merchant balance retrieved successfully", response));
    }

    @GetMapping("/journals")
    public ResponseEntity<ApiResponse<Page<JournalResponse>>> listJournals(
            @RequestHeader(name = "X-Merchant-Id", required = false) UUID merchantId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "10") int size,
            @RequestParam(name = "sortBy", defaultValue = "postedAt") String sortBy,
            @RequestParam(name = "direction", defaultValue = "desc") String direction
    ) {
        log.info("Listing journals: merchantId={}, page={}, size={}, sortBy={}, direction={}",
                merchantId, page, size, sortBy, direction);

        Sort sort = direction.equalsIgnoreCase("asc")
                ? Sort.by(sortBy).ascending()
                : Sort.by(sortBy).descending();
        Pageable pageable = PageRequest.of(page, size, sort);

        Page<Journal> journals = (merchantId != null)
                ? ledgerService.getMerchantJournals(merchantId, pageable)
                : ledgerService.getAllJournals(pageable);

        Page<JournalResponse> response = journals.map(JournalResponse::fromEntity);
        return ResponseEntity.ok(ApiResponse.success("Journals retrieved successfully", response));
    }

    @GetMapping("/journals/{id}")
    public ResponseEntity<ApiResponse<JournalResponse>> getJournal(
            @PathVariable("id") UUID journalId,
            @RequestHeader(name = "X-Merchant-Id", required = false) UUID merchantId
    ) {
        log.info("Fetching journal {} with optional merchant filter {}", journalId, merchantId);

        Journal journal = ledgerService.getJournal(journalId)
                .orElseThrow(() -> new EntityNotFoundException("Journal not found with ID: " + journalId));

        if (merchantId != null && journal.getMerchant() != null && !journal.getMerchant().getId().equals(merchantId)) {
            throw new EntityNotFoundException(String.format("Journal %s not found for merchant %s", journalId, merchantId));
        }

        return ResponseEntity.ok(ApiResponse.success(JournalResponse.fromEntity(journal)));
    }
}
