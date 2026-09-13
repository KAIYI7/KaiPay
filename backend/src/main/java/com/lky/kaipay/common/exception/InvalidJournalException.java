package com.lky.kaipay.common.exception;

public class InvalidJournalException extends RuntimeException {

    public InvalidJournalException(String message) {
        super(message);
    }

    public InvalidJournalException(String message, Throwable cause) {
        super(message, cause);
    }
}
