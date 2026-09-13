package com.lky.kaipay.ledger.service;

import com.lky.kaipay.common.exception.EntityNotFoundException;
import com.lky.kaipay.ledger.api.dto.MerchantBalanceResponse;
import com.lky.kaipay.ledger.domain.Account;
import com.lky.kaipay.ledger.domain.AccountType;
import com.lky.kaipay.ledger.repository.LedgerEntryRepository;
import com.lky.kaipay.merchant.domain.Merchant;
import com.lky.kaipay.merchant.repository.MerchantRepository;
import com.lky.kaipay.payment.repository.PaymentRepository;
import com.lky.kaipay.refund.repository.RefundRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class MerchantBalanceService {

    private final MerchantRepository merchantRepository;
    private final AccountProvisioningService accountProvisioningService;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;

    @Transactional(readOnly = true)
    public MerchantBalanceResponse getMerchantBalance(UUID merchantId) {
        log.info("Calculating merchant balance projection for merchant: {}", merchantId);

        Merchant merchant = merchantRepository.findById(merchantId)
                .orElseThrow(() -> new EntityNotFoundException("Merchant not found with ID: " + merchantId));

        Account payableAccount = accountProvisioningService.getOrCreateMerchantAccount(
                merchant, AccountType.LIABILITY, "USD"
        );

        Long balance = ledgerEntryRepository.calculateAccountBalance(payableAccount.getId());
        long availableBalanceCents = balance != null ? balance : 0L;

        Long pendingSettlement = paymentRepository.sumPendingSettlementByMerchantId(merchantId);
        long pendingSettlementCents = pendingSettlement != null ? pendingSettlement : 0L;

        Long volume = paymentRepository.sumSuccessfulVolumeByMerchantId(merchantId);
        long totalVolumeCents = volume != null ? volume : 0L;

        Long fees = ledgerEntryRepository.calculateMerchantFees(merchantId);
        long totalFeesCents = fees != null ? fees : 0L;

        Long refunds = refundRepository.sumRefundsByMerchantId(merchantId);
        long totalRefundsCents = refunds != null ? refunds : 0L;

        String currency = payableAccount.getCurrency() != null ? payableAccount.getCurrency() : "USD";

        log.debug("Merchant balance calculated: merchantId={}, availableBalance={}, pendingSettlement={}, totalVolume={}, totalFees={}, totalRefunds={}",
                merchantId, availableBalanceCents, pendingSettlementCents, totalVolumeCents, totalFeesCents, totalRefundsCents);

        return MerchantBalanceResponse.builder()
                .merchantId(merchant.getId())
                .availableBalanceCents(availableBalanceCents)
                .pendingSettlementCents(pendingSettlementCents)
                .totalVolumeCents(totalVolumeCents)
                .totalFeesCents(totalFeesCents)
                .totalRefundsCents(totalRefundsCents)
                .currency(currency)
                .build();
    }
}
