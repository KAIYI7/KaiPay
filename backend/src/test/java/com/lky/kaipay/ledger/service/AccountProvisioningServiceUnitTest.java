package com.lky.kaipay.ledger.service;

import com.lky.kaipay.ledger.domain.Account;
import com.lky.kaipay.ledger.domain.AccountType;
import com.lky.kaipay.ledger.repository.AccountRepository;
import com.lky.kaipay.merchant.domain.Merchant;
import com.lky.kaipay.merchant.domain.MerchantStatus;
import com.lky.kaipay.merchant.repository.MerchantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AccountProvisioningService Unit Tests")
class AccountProvisioningServiceUnitTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private MerchantRepository merchantRepository;

    @InjectMocks
    private AccountProvisioningService accountProvisioningService;

    private Merchant merchant;

    @BeforeEach
    void setUp() {
        merchant = Merchant.builder()
                .id(UUID.randomUUID())
                .name("Test Merchant")
                .apiKeyHash("test-hash")
                .status(MerchantStatus.ACTIVE)
                .build();
    }

    @Test
    @DisplayName("Should return existing system account when found")
    void shouldReturnExistingSystemAccount() {
        Account existing = Account.builder()
                .id(UUID.randomUUID())
                .accountNumber(AccountProvisioningService.SYS_CUSTOMER_RECEIVABLE_ACC)
                .accountName("Customer Funds Receivable")
                .accountType(AccountType.ASSET)
                .currency("USD")
                .build();

        when(accountRepository.findByAccountNumber(AccountProvisioningService.SYS_CUSTOMER_RECEIVABLE_ACC))
                .thenReturn(Optional.of(existing));

        Account result = accountProvisioningService.getOrCreateSystemAccount(
                AccountProvisioningService.SYS_CUSTOMER_RECEIVABLE_ACC,
                "Customer Funds Receivable",
                AccountType.ASSET,
                "USD"
        );

        assertThat(result).isSameAs(existing);
        verify(accountRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should create and save new system account when not found")
    void shouldCreateNewSystemAccount() {
        when(accountRepository.findByAccountNumber(AccountProvisioningService.SYS_CUSTOMER_RECEIVABLE_ACC))
                .thenReturn(Optional.empty());
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Account result = accountProvisioningService.getOrCreateSystemAccount(
                AccountProvisioningService.SYS_CUSTOMER_RECEIVABLE_ACC,
                "Customer Funds Receivable",
                AccountType.ASSET,
                "USD"
        );

        assertThat(result.getAccountNumber()).isEqualTo(AccountProvisioningService.SYS_CUSTOMER_RECEIVABLE_ACC);
        assertThat(result.getAccountName()).isEqualTo("Customer Funds Receivable");
        assertThat(result.getAccountType()).isEqualTo(AccountType.ASSET);
        assertThat(result.getCurrency()).isEqualTo("USD");
        assertThat(result.getMerchant()).isNull();
        verify(accountRepository).save(any(Account.class));
    }

    @Test
    @DisplayName("Should return existing merchant account when found")
    void shouldReturnExistingMerchantAccount() {
        Account existing = Account.builder()
                .id(UUID.randomUUID())
                .merchant(merchant)
                .accountNumber("2000-MERCHANT-" + merchant.getId() + "-LIABILITY")
                .accountName("Test Merchant LIABILITY Account")
                .accountType(AccountType.LIABILITY)
                .currency("USD")
                .build();

        when(accountRepository.findByMerchantIdAndAccountType(merchant.getId(), AccountType.LIABILITY))
                .thenReturn(Optional.of(existing));

        Account result = accountProvisioningService.getOrCreateMerchantAccount(merchant, AccountType.LIABILITY, "USD");

        assertThat(result).isSameAs(existing);
        verify(accountRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should create and save new merchant account when not found")
    void shouldCreateNewMerchantAccount() {
        when(accountRepository.findByMerchantIdAndAccountType(merchant.getId(), AccountType.LIABILITY))
                .thenReturn(Optional.empty());
        when(merchantRepository.findById(merchant.getId())).thenReturn(Optional.of(merchant));
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Account result = accountProvisioningService.getOrCreateMerchantAccount(merchant, AccountType.LIABILITY, "USD");

        assertThat(result.getAccountNumber()).isEqualTo("2000-MERCHANT-" + merchant.getId() + "-LIABILITY");
        assertThat(result.getAccountName()).isEqualTo("Test Merchant LIABILITY Account");
        assertThat(result.getAccountType()).isEqualTo(AccountType.LIABILITY);
        assertThat(result.getCurrency()).isEqualTo("USD");
        assertThat(result.getMerchant()).isEqualTo(merchant);
        verify(accountRepository).save(any(Account.class));
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException when merchant is null")
    void shouldThrowExceptionWhenMerchantIsNull() {
        assertThatThrownBy(() -> accountProvisioningService.getOrCreateMerchantAccount(null, AccountType.LIABILITY, "USD"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Merchant cannot be null");
    }
}
