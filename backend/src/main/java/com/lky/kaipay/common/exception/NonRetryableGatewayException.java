package com.lky.kaipay.common.exception;

public class NonRetryableGatewayException extends RuntimeException {
    public NonRetryableGatewayException(String message) {
        super(message);
    }

    public NonRetryableGatewayException(String message, Throwable cause) {
        super(message, cause);
    }
}
