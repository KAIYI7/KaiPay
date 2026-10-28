package com.lky.kaipay.integration;

public final class IntegrationFailure extends RuntimeException {
    final int status;
    final String code;
    public IntegrationFailure(int status, String code) {
        super(code);
        this.status = status;
        this.code = code;
    }
}
