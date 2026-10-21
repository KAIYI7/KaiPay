package com.lky.kaipay.webhook.delivery;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.UUID;

public final class WebhookSigner {
    private WebhookSigner() {}
    public static String sign(byte[] secret, UUID keyId, long timestamp, byte[] body) {
        if (secret.length != 32 || timestamp < 0 || body.length > 4096) throw new IllegalArgumentException("Invalid signing input");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            mac.update(("v1\n" + keyId + "\n" + timestamp + "\n").getBytes(StandardCharsets.US_ASCII));
            return "t=" + timestamp + ",kid=" + keyId + ",v1=" + HexFormat.of().formatHex(mac.doFinal(body));
        } catch (java.security.GeneralSecurityException e) { throw new IllegalStateException("Signing unavailable"); }
    }
}
