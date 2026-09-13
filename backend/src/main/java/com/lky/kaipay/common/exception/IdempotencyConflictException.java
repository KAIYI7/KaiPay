package com.lky.kaipay.common.exception;

public class IdempotencyConflictException extends BusinessException {
    public IdempotencyConflictException(String message) {
        super(message);
    }
}
