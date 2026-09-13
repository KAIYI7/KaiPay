package com.lky.kaipay.payment.service.acquirer;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.time.Instant;

@Getter
@Builder
@ToString
@EqualsAndHashCode
@NoArgsConstructor
@AllArgsConstructor
public class AcquirerAuthorizationResult {

    private boolean approved;
    private String authorizationCode;
    private String declineCode;
    private String declineMessage;
    private Instant timestamp;

    public static AcquirerAuthorizationResult approved(String authCode) {
        return AcquirerAuthorizationResult.builder()
                .approved(true)
                .authorizationCode(authCode)
                .timestamp(Instant.now())
                .build();
    }

    public static AcquirerAuthorizationResult declined(String declineCode, String message) {
        return AcquirerAuthorizationResult.builder()
                .approved(false)
                .declineCode(declineCode)
                .declineMessage(message)
                .timestamp(Instant.now())
                .build();
    }
}
