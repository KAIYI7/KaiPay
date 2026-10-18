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

    @NotNull(message = "Refund amount in minor units (MYR sen) is required")
    @Positive(message = "Refund amount must be strictly positive")
    @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = com.lky.kaipay.common.api.MinorUnitsDeserializer.class)
    private Long amountCents;

    private String reason;
}
