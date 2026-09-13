package com.lky.kaipay.payment.repository;

import com.lky.kaipay.payment.domain.Payment;
import com.lky.kaipay.payment.domain.PaymentStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, UUID> {
    Optional<Payment> findByIdAndMerchantId(UUID id, UUID merchantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Payment p WHERE p.id = :id AND p.merchant.id = :merchantId")
    Optional<Payment> findByIdAndMerchantIdForUpdate(@Param("id") UUID id, @Param("merchantId") UUID merchantId);
    Optional<Payment> findByMerchantIdAndIdempotencyKey(UUID merchantId, String idempotencyKey);
    Page<Payment> findByMerchantId(UUID merchantId, Pageable pageable);
    Page<Payment> findByMerchantIdAndStatus(UUID merchantId, PaymentStatus status, Pageable pageable);

    @Query("SELECT COALESCE(SUM(p.amountCents), 0L) FROM Payment p WHERE p.merchant.id = :merchantId AND p.status IN ('CAPTURED', 'PARTIALLY_REFUNDED', 'REFUNDED')")
    Long sumSuccessfulVolumeByMerchantId(@Param("merchantId") UUID merchantId);

    @Query("SELECT COALESCE(SUM(p.amountCents), 0L) FROM Payment p WHERE p.merchant.id = :merchantId AND p.status = 'AUTHORIZED'")
    Long sumPendingSettlementByMerchantId(@Param("merchantId") UUID merchantId);
}
