package com.lky.kaipay.payment.repository;

import com.lky.kaipay.payment.domain.PaymentMethod;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface PaymentMethodRepository extends JpaRepository<PaymentMethod, UUID> {
    Optional<PaymentMethod> findByIdAndCustomerId(UUID id, UUID customerId);
    Optional<PaymentMethod> findByToken(String token);
}
