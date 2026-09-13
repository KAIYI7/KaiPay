package com.lky.kaipay.payment.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreatePaymentRequest {

    @NotNull(message = "amountCents is required")
    @Positive(message = "amountCents must be greater than zero")
    private Long amountCents;

    @NotBlank(message = "currency is required")
    @Pattern(regexp = "^[A-Z]{3}$", message = "currency must be a valid 3-letter ISO code (e.g., USD, EUR)")
    private String currency;

    @NotNull(message = "customerId is required")
    private UUID customerId;

    private UUID paymentMethodId;

    private Map<String, Object> metadata;
}
