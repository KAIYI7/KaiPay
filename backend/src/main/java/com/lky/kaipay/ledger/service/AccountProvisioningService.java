package com.lky.kaipay.ledger.service;

import com.lky.kaipay.ledger.domain.Account;
import com.lky.kaipay.ledger.domain.AccountType;
import com.lky.kaipay.ledger.repository.AccountRepository;
import com.lky.kaipay.merchant.domain.Merchant;
import com.lky.kaipay.merchant.repository.MerchantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AccountProvisioningService {

    public static final String SYS_CUSTOMER_RECEIVABLE_ACC = "1000-CUSTOMER-RECEIVABLE";
    public static final String SYS_PLATFORM_REVENUE_ACC = "4000-PLATFORM-FEE-REVENUE";

    private final AccountRepository accountRepository;
    private final MerchantRepository merchantRepository;

    @Transactional
    public Account getOrCreateSystemAccount(String accountNumber, String accountName, AccountType accountType, String currency) {
        return accountRepository.findByAccountNumber(accountNumber)
                .orElseGet(() -> {
                    log.info("Provisioning new system account: accountNumber={}, accountName={}, accountType={}, currency={}",
                            accountNumber, accountName, accountType, currency);
                    Account account = Account.builder()
                            .merchant(null)
                            .accountNumber(accountNumber)
                            .accountName(accountName)
                            .accountType(accountType)
                            .currency(currency != null ? currency : "USD")
                            .build();
                    return accountRepository.save(account);
                });
    }

    @Transactional
    public Account getOrCreateMerchantAccount(Merchant merchant, AccountType accountType, String currency) {
        if (merchant == null) {
            throw new IllegalArgumentException("Merchant cannot be null when provisioning merchant account");
        }
        if (merchant.getId() == null) {
            throw new IllegalArgumentException("Merchant ID cannot be null when provisioning merchant account");
        }
        return accountRepository.findByMerchantIdAndAccountType(merchant.getId(), accountType)
                .orElseGet(() -> {
                    Merchant attachedMerchant = merchantRepository.findById(merchant.getId()).orElse(merchant);
                    String merchantName = (attachedMerchant.getName() != null) ? attachedMerchant.getName() : "Merchant";
                    String accountNumber = "2000-MERCHANT-" + attachedMerchant.getId() + "-" + accountType.name();
                    String rawName = merchantName + " " + accountType.name() + " Account";
                    String accountName = rawName.length() > 100 ? rawName.substring(0, 100) : rawName;
                    log.info("Provisioning new merchant account: merchantId={}, accountNumber={}, accountName={}, accountType={}, currency={}",
                            attachedMerchant.getId(), accountNumber, accountName, accountType, currency);
                    Account account = Account.builder()
                            .merchant(attachedMerchant)
                            .accountNumber(accountNumber)
                            .accountName(accountName)
                            .accountType(accountType)
                            .currency(currency != null ? currency : "USD")
                            .build();
                    return accountRepository.save(account);
                });
    }
}
