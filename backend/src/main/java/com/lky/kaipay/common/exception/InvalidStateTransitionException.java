package com.lky.kaipay.common.exception;

public class InvalidStateTransitionException extends BusinessException {
    public InvalidStateTransitionException(String message) {
        super(message);
    }
}
