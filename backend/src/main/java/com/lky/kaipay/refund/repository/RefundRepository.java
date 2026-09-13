package com.lky.kaipay.refund.repository;

import com.lky.kaipay.refund.domain.Refund;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RefundRepository extends JpaRepository<Refund, UUID> {

    Optional<Refund> findByMerchantIdAndIdempotencyKey(UUID merchantId, String idempotencyKey);

    List<Refund> findByPaymentId(UUID paymentId);

    @Query("SELECT COALESCE(SUM(r.amountCents), 0L) FROM Refund r WHERE r.payment.id = :paymentId AND r.status = 'COMPLETED'")
    Long sumRefundedAmountByPaymentId(@Param("paymentId") UUID paymentId);

    @Query("SELECT COALESCE(SUM(r.amountCents), 0L) FROM Refund r WHERE r.merchant.id = :merchantId AND r.status = 'COMPLETED'")
    Long sumRefundsByMerchantId(@Param("merchantId") UUID merchantId);
}
