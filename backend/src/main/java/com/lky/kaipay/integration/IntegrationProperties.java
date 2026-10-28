package com.lky.kaipay.integration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/** Operator-owned bindings. There is deliberately no provisioning or rebinding API. */
@ConfigurationProperties("kaipay.integrations")
public record IntegrationProperties(boolean enabled, List<Binding> bindings) {
    public IntegrationProperties {
        bindings = bindings == null ? List.of() : List.copyOf(bindings);
        if (bindings.size() > 16 || (enabled && bindings.isEmpty())) invalid();
        var ids = new HashSet<UUID>();
        var merchants = new HashSet<UUID>();
        var keys = new HashSet<String>();
        for (Binding b : bindings) {
            if (b.integrationId() == null || b.merchantId() == null || b.customerId() == null
                    || !ids.add(b.integrationId()) || !merchants.add(b.merchantId())
                    || b.keys().isEmpty() || b.keys().size() > 2) invalid();
            for (Key k : b.keys()) {
                if (k.keyId() == null || !k.keyId().matches("[A-Za-z0-9_-]{1,32}")
                        || !keys.add(k.keyId()) || k.sha256() == null
                        || !k.sha256().matches("[a-f0-9]{64}")) invalid();
            }
        }
    }
    private static void invalid() { throw new IllegalArgumentException("Invalid machine integration configuration"); }
    public record Binding(UUID integrationId, UUID merchantId, UUID customerId, boolean enabled, List<Key> keys) {
        public Binding { keys = keys == null ? List.of() : List.copyOf(keys); }
    }
    public record Key(String keyId, String sha256) {
        @Override public String toString() { return "IntegrationKey[redacted]"; }
    }
}
