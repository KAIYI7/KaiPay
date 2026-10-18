package com.lky.kaipay.common.exception;

/** Retry a fresh transaction; this says nothing about simulator acceptance. */
public class AuthorizationPersistenceException extends RuntimeException {
    public AuthorizationPersistenceException(Throwable cause) {
        super("Authorization persistence temporarily unavailable", cause);
    }
}
