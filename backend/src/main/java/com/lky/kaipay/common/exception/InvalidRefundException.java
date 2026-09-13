package com.lky.kaipay.common.exception;

public class InvalidRefundException extends BusinessException {

    public InvalidRefundException(String message) {
        super(message);
    }

    public InvalidRefundException(String message, Throwable cause) {
        super(message, cause);
    }
}
