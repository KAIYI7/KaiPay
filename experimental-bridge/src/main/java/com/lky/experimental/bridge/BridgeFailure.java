package com.lky.experimental.bridge;

final class BridgeFailure extends RuntimeException {
    final int status;
    final String code;
    BridgeFailure(int status, String code) {
        super(code);
        this.status = status;
        this.code = code;
    }
}
