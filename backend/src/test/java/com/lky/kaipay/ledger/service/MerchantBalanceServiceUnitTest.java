package com.lky.kaipay.ledger.service;

import com.lky.kaipay.common.exception.EntityNotFoundException;
import com.lky.kaipay.ledger.api.dto.MerchantBalanceResponse;
import com.lky.kaipay.ledger.domain.Account;
import com.lky.kaipay.ledger.domain.AccountType;
import com.lky.kaipay.ledger.repository.LedgerEntryRepository;
import com.lky.kaipay.merchant.domain.Merchant;
import com.lky.kaipay.merchant.domain.MerchantStatus;
import com.lky.kaipay.merchant.repository.MerchantRepository;
import com.lky.kaipay.payment.repository.PaymentRepository;
import com.lky.kaipay.refund.repository.RefundRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("MerchantBalanceService Unit Tests")
class MerchantBalanceServiceUnitTest {

    @Mock
    private MerchantRepository merchantRepository;

    @Mock
    private AccountProvisioningService accountProvisioningService;

    @Mock
    private LedgerEntryRepository ledgerEntryRepository;

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private RefundRepository refundRepository;

    @InjectMocks
    private MerchantBalanceService merchantBalanceService;

    private UUID merchantId;
    private Merchant merchant;
    private Account payableAccount;

    @BeforeEach
    void setUp() {
        merchantId = UUID.randomUUID();
        merchant = Merchant.builder()
                .id(merchantId)
                .name("Acme Corp")
                .apiKeyHash("hash_" + UUID.randomUUID())
                .status(MerchantStatus.ACTIVE)
                .build();

        payableAccount = Account.builder()
                .id(UUID.randomUUID())
                .merchant(merchant)
                .accountNumber("2000-MERCHANT-" + merchantId + "-LIABILITY")
                .accountName("Acme Corp LIABILITY Account")
                .accountType(AccountType.LIABILITY)
                .currency("USD")
                .build();
    }

    @Nested
    @DisplayName("getMerchantBalance")
    class GetMerchantBalanceTests {

        @Test
        @DisplayName("Should return complete and accurate merchant balance projection")
        void shouldReturnCompleteMerchantBalanceProjection() {
            when(merchantRepository.findById(merchantId)).thenReturn(Optional.of(merchant));
            when(accountProvisioningService.getOrCreateMerchantAccount(merchant, AccountType.LIABILITY, "USD"))
                    .thenReturn(payableAccount);
            when(ledgerEntryRepository.calculateAccountBalance(payableAccount.getId())).thenReturn(9680L);
            when(paymentRepository.sumPendingSettlementByMerchantId(merchantId)).thenReturn(5000L);
            when(paymentRepository.sumSuccessfulVolumeByMerchantId(merchantId)).thenReturn(10000L);
            when(ledgerEntryRepository.calculateMerchantFees(merchantId)).thenReturn(320L);
            when(refundRepository.sumRefundsByMerchantId(merchantId)).thenReturn(0L);

            MerchantBalanceResponse response = merchantBalanceService.getMerchantBalance(merchantId);

            assertThat(response).isNotNull();
            assertThat(response.getMerchantId()).isEqualTo(merchantId);
            assertThat(response.getAvailableBalanceCents()).isEqualTo(9680L);
            assertThat(response.getPendingSettlementCents()).isEqualTo(5000L);
            assertThat(response.getTotalVolumeCents()).isEqualTo(10000L);
            assertThat(response.getTotalFeesCents()).isEqualTo(320L);
            assertThat(response.getTotalRefundsCents()).isEqualTo(0L);
            assertThat(response.getCurrency()).isEqualTo("USD");

            verify(merchantRepository).findById(merchantId);
            verify(accountProvisioningService).getOrCreateMerchantAccount(merchant, AccountType.LIABILITY, "USD");
            verify(ledgerEntryRepository).calculateAccountBalance(payableAccount.getId());
            verify(paymentRepository).sumPendingSettlementByMerchantId(merchantId);
            verify(paymentRepository).sumSuccessfulVolumeByMerchantId(merchantId);
            verify(ledgerEntryRepository).calculateMerchantFees(merchantId);
            verify(refundRepository).sumRefundsByMerchantId(merchantId);
        }

        @Test
        @DisplayName("Should default null repository aggregates to 0L")
        void shouldDefaultNullAggregatesToZero() {
            when(merchantRepository.findById(merchantId)).thenReturn(Optional.of(merchant));
            when(accountProvisioningService.getOrCreateMerchantAccount(merchant, AccountType.LIABILITY, "USD"))
                    .thenReturn(payableAccount);
            when(ledgerEntryRepository.calculateAccountBalance(payableAccount.getId())).thenReturn(null);
            when(paymentRepository.sumPendingSettlementByMerchantId(merchantId)).thenReturn(null);
            when(paymentRepository.sumSuccessfulVolumeByMerchantId(merchantId)).thenReturn(null);
            when(ledgerEntryRepository.calculateMerchantFees(merchantId)).thenReturn(null);
            when(refundRepository.sumRefundsByMerchantId(merchantId)).thenReturn(null);

            MerchantBalanceResponse response = merchantBalanceService.getMerchantBalance(merchantId);

            assertThat(response).isNotNull();
            assertThat(response.getMerchantId()).isEqualTo(merchantId);
            assertThat(response.getAvailableBalanceCents()).isEqualTo(0L);
            assertThat(response.getPendingSettlementCents()).isEqualTo(0L);
            assertThat(response.getTotalVolumeCents()).isEqualTo(0L);
            assertThat(response.getTotalFeesCents()).isEqualTo(0L);
            assertThat(response.getTotalRefundsCents()).isEqualTo(0L);
            assertThat(response.getCurrency()).isEqualTo("USD");
        }

        @Test
        @DisplayName("Should throw EntityNotFoundException when merchant is not found")
        void shouldThrowEntityNotFoundExceptionWhenMerchantMissing() {
            UUID unknownMerchantId = UUID.randomUUID();
            when(merchantRepository.findById(unknownMerchantId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> merchantBalanceService.getMerchantBalance(unknownMerchantId))
                    .isInstanceOf(EntityNotFoundException.class)
                    .hasMessageContaining(unknownMerchantId.toString());
        }

        @Test
        @DisplayName("Should preserve account custom currency")
        void shouldPreserveAccountCustomCurrency() {
            Account eurAccount = Account.builder()
                    .id(UUID.randomUUID())
                    .merchant(merchant)
                    .accountNumber("2000-MERCHANT-" + merchantId + "-LIABILITY")
                    .accountName("Acme Corp EUR Account")
                    .accountType(AccountType.LIABILITY)
                    .currency("EUR")
                    .build();

            when(merchantRepository.findById(merchantId)).thenReturn(Optional.of(merchant));
            when(accountProvisioningService.getOrCreateMerchantAccount(merchant, AccountType.LIABILITY, "USD"))
                    .thenReturn(eurAccount);
            when(ledgerEntryRepository.calculateAccountBalance(eurAccount.getId())).thenReturn(25000L);

            MerchantBalanceResponse response = merchantBalanceService.getMerchantBalance(merchantId);

            assertThat(response.getCurrency()).isEqualTo("EUR");
            assertThat(response.getAvailableBalanceCents()).isEqualTo(25000L);
        }
    }
}
