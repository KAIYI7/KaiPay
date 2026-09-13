package com.lky.kaipay.common.exception;

public class UnbalancedJournalException extends RuntimeException {

    public UnbalancedJournalException(String message) {
        super(message);
    }

    public UnbalancedJournalException(String message, Throwable cause) {
        super(message, cause);
    }
}
