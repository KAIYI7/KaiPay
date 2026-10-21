package com.lky.kaipay.webhook.delivery;

public interface WebhookTransport extends AutoCloseable {
    record Response(WebhookResult result, Integer status) {}
    Response send(WebhookClaim claim, String signature);
    @Override default void close() {}
}
