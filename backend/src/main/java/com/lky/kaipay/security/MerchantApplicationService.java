package com.lky.kaipay.security;

import com.lky.kaipay.ledger.api.dto.MerchantBalanceResponse;
import com.lky.kaipay.ledger.service.MerchantBalanceService;
import com.lky.kaipay.payment.api.dto.CreatePaymentRequest;
import com.lky.kaipay.payment.api.dto.PaymentResponse;
import com.lky.kaipay.payment.domain.PaymentStatus;
import com.lky.kaipay.payment.service.PaymentService;
import com.lky.kaipay.refund.api.dto.CreateRefundRequest;
import com.lky.kaipay.refund.api.dto.RefundResponse;
import com.lky.kaipay.refund.service.RefundService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/** Browser business operations. Checks precede cached replies and all financial work. */
@Service
@RequiredArgsConstructor
public class MerchantApplicationService {
    private final ResourceAccess access;
    private final PaymentService payments;
    private final RefundService refunds;
    private final MerchantBalanceService balances;
    public PaymentResponse createPayment(UUID merchant, String key, CreatePaymentRequest request) {
        access.merchant(merchant);
        return payments.createPayment(merchant, key, request);
    }
    public Page<PaymentResponse> listPayments(UUID merchant, PaymentStatus status, Pageable page) {
        access.merchant(merchant);
        return payments.listPayments(merchant, status, page);
    }
    public PaymentResponse getPayment(UUID merchant, UUID payment) {
        access.merchant(merchant);
        return payments.getPayment(merchant, payment);
    }
    public PaymentResponse capturePayment(UUID merchant, UUID payment, String key) {
        access.merchant(merchant);
        return payments.capturePayment(merchant, payment, key);
    }
    public RefundResponse createRefund(UUID merchant, UUID payment, String key, CreateRefundRequest request) {
        access.merchant(merchant);
        return refunds.createRefund(merchant, payment, key, request);
    }
    public List<RefundResponse> getPaymentRefunds(UUID merchant, UUID payment) {
        access.merchant(merchant);
        return refunds.getPaymentRefunds(merchant, payment);
    }
    public MerchantBalanceResponse getMerchantBalance(UUID merchant) {
        access.merchant(merchant);
        return balances.getMerchantBalance(merchant);
    }
}
