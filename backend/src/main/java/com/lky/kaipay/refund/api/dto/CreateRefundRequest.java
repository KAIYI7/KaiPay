package com.lky.kaipay.refund.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateRefundRequest {

    @NotNull(message = "Refund amount in cents is required")
    @Positive(message = "Refund amount must be strictly positive")
    private Long amountCents;

    private String reason;
}
