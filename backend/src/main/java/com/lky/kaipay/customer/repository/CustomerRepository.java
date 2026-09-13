package com.lky.kaipay.customer.repository;

import com.lky.kaipay.customer.domain.Customer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface CustomerRepository extends JpaRepository<Customer, UUID> {
    Optional<Customer> findByIdAndMerchantId(UUID id, UUID merchantId);
    Optional<Customer> findByMerchantIdAndEmail(UUID merchantId, String email);
}
