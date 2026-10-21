# Merchant webhooks

KaiPay produces simulated MYR lifecycle notifications for enrolled merchants:

| Type | Meaning |
| --- | --- |
| `payment.authorized` | Durable simulated authorization approved. |
| `payment.declined` | Durable simulated authorization declined. |
| `payment.failed` | Definitive simulator rejection without acceptance. A timeout or dead-letter event does not imply this result. |
| `payment.captured` | Capture and its journal committed. |
| `refund.completed` | One refund and its reversal journal committed; the snapshot includes the resulting partial/full refund payment status. |

Enrollment is forward-only. Existing merchant webhook URLs are inert. Events before enrollment are not backfilled, and replay of an already-completed financial operation does not generate another notification. New events use MYR integer minor units: `amount_cents` or `refund_amount_cents` denotes sen, with 100 sen = RM1.00.

Delivery is deliberately inactive in the application runtime in this release. `kaipay.webhooks.mode` accepts only `disabled`; other values fail startup. A test-only loopback receiver and scheduled harness exercise delivery without opening external destinations. External activation requires a separate operational change and verified destination ownership, certificate/secret configuration, approved public address pins and independent outbound network restrictions. This repository provides no arbitrary merchant URL editor, public test-send or manual redrive API.

## Payload and authenticity

The JSON envelope contains `id`, `type`, `version: 1`, `occurred_at`, `merchant_id` and `data`. Payment data contains payment ID, integer amount, currency and status. Refund data contains refund/payment IDs, refund amount, currency, refund status and payment status at that transition. The payload excludes customer details, metadata, idempotency keys, refund reasons and gateway messages.

Each delivery retains exact UTF-8 bytes and a stable event UUID. Capture/refund reuse the committed internal lifecycle event UUID; authorization outcomes have a distinct notification UUID. `occurred_at` is the transition hook time, not an exact database commit timestamp. Events may arrive late or out of order; fetch the current owned payment through the authenticated payment API when current state matters.

Requests use POST with `Content-Type: application/json` and these headers:

```text
Webhook-Id: <event UUID, also in signed body>
Webhook-Delivery-Id: <stable delivery UUID; diagnostic only>
Webhook-Signature: t=<Unix seconds>,kid=<key UUID>,v1=<lowercase hex MAC>
```

The signing input is the concatenation of this ASCII prefix and the unchanged raw body bytes:

```text
"v1\n" + kid + "\n" + t + "\n" + raw_body_bytes
```

`v1` is HMAC-SHA256 using the endpoint revision's independent random 32-byte secret. A retry keeps the same event/body/delivery IDs and uses a fresh timestamp/signature. Never verify against parsed-and-reserialized JSON.

Receivers must reject unsupported/ambiguous signature headers, verify the MAC in constant time with their configured merchant/key, enforce an absolute timestamp difference of at most 300 seconds (including future timestamps), and verify signed merchant ownership and Webhook-Id/body-ID agreement. The diagnostic delivery header is not authenticated and must not authorize processing.

After verification, atomically deduplicate by `(merchant_id, event_id)` together with receiver processing. Return 2xx for a previously accepted duplicate. Keep deduplication evidence through at least the 24-hour delivery horizon plus clock tolerance; longer retention is prudent. A timestamp window alone permits replay within that window. A valid signature authenticates a KaiPay notification; it does not authorize payments or establish real financial finality.

## Delivery and troubleshooting

A durable notification intent commits atomically with its financial transition. Local intent persistence failure rolls back that transition. HTTP runs later, without a database transaction, and its outcome cannot change payment/accounting state.

The delivery engine permits at most eight started attempts within 24 hours of the event. Backoff after failures is 5 seconds, 30 seconds, 2 minutes, 10 minutes, 30 minutes, 2 hours and 6 hours, plus 0–20% positive jitter. Due times and attempt evidence persist across restarts. Attempt claims expire after 30 seconds; abandoned work records UNKNOWN before a bounded retry. Each request has a 10-second absolute deadline, shorter connect/TLS and inactivity limits, bounded headers and no response-body consumption.

Any 2xx is an acknowledgement. Transport ambiguity, 408/425/429 and 5xx are retryable. Other 3xx/4xx, unsafe destinations, TLS validation and configuration errors are terminal; redirects and Retry-After are not followed. Endpoint retirement or merchant inactivity cancels unsent work. A request already dispatched cannot be recalled. No pending event is moved to a replacement endpoint/key.

Response loss or a crash after receiver acceptance can duplicate requests. Bounded durable attempts do not guarantee eventual delivery, ordering, exactly-once receiver effects or receiver durability. The merchant Webhooks page shows enrollment, delivery IDs/types/states, attempts, due/expiry/delivery times and safe failure categories. Its read-only endpoints are `/v1/webhooks/endpoint`, `/v1/webhooks/deliveries` and `/v1/webhooks/deliveries/{id}`, using the existing authenticated merchant session and membership checks. Lists support bounded pagination and state/payment filters. No raw payload, endpoint URL, pin, secret, signature or receiver response is exposed.

## Operator configuration boundary

`WebhookEndpointCli` is an explicit offline command, separate from Spring startup. Stop the application/dispatch before provisioning, retiring or rotating configuration, then restart with validated immutable mappings. Enrollment accepts only canonical HTTPS/443 hostnames with a fixed simple path and one to four public IPv4 pins. It never resolves a merchant-controlled URL or performs an ownership challenge. The transport uses only those address bytes, preserves the original hostname for TLS/SNI verification, validates its connected peer and disables redirects, proxies, hidden retries, cookies, authentication, decompression, connection reuse and HTTP/2 coalescing. DNS changes cannot change the selected connection address; pin changes require a new revision. IPv6 and nonpublic/special-use addresses are rejected.

Provide `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` and an ACL-restricted `WEBHOOK_SECRET_DIRECTORY` through the operator environment. Generate each secret file from 32 cryptographically random bytes outside the repository; its filename is an opaque alphanumeric/underscore/hyphen alias. Restrict both directory and file to the owning operator/service account (and system administrators where required). Never put secret material in command arguments, logs, public artifacts or browser storage. The alias and key UUID may be command arguments; the secret bytes may not.

```text
WebhookEndpointCli enroll <merchantUUID> <operatorId> <REASON_CODE> <canonicalHttpsUrl> <ipv4PinsCommaSeparated> <keyUUID> <secretAlias>
WebhookEndpointCli retire <endpointUUID> <operatorId> <REASON_CODE>
```

Configuration rows preserve actor/reason evidence. Aliases and key IDs are unique; startup rejects shared secret bytes across active bindings. Missing/insecure secrets make the endpoint unavailable and do not enable fallback. Secrets load once per process and are bound to merchant/revision/key. Rotation retires the old revision and cancels unsent events; new events use the new revision. Receivers may retain an old key briefly for already-dispatched requests, but compromise requires immediate revocation. There is no seamless drain, automatic redrive, secrets platform or automatic retention scheduler. Operator maintenance may remove terminal evidence after seven days, preserving live work and required reference records.

These commands do not enable network delivery. Tests generate disposable receiver keys/certificates and receipts at runtime; production has no allow-private or trust-all setting. Verify real deployment egress controls separately before any nonlocal activation.
