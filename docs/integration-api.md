# Synthetic authorization API

This optional server-to-server API creates and observes **simulated authorizations only**. It does not capture, refund, reimburse or settle money. It reuses the ordinary payment transaction, outbox, Kafka consumer and simulator. Browser payment APIs keep their existing session and CSRF contracts.

## Configuration and credentials

The feature defaults disabled. An operator can supply `kaipay.integrations.enabled=true` and a `kaipay.integrations.bindings` list in private server configuration. Each entry contains:

| Property | Meaning |
| --- | --- |
| `enabled` | Explicit activation of this binding |
| `integration-id` | Stable installation/integration UUID, retained across restart and credential rotation |
| `merchant-id` | Existing active dedicated synthetic merchant, with no browser memberships |
| `customer-id` | Existing synthetic customer owned by that merchant |
| `keys` | One or two entries containing `key-id` and `sha256` |

Configuration supports up to 16 bindings; integration IDs, merchant IDs and key IDs must be unique. Missing enabled configuration or malformed binding data fails closed. No API provisions accounts or credentials. Never rebind an integration to another merchant/customer. Retained namespace records with another binding are rejected.

Send `Authorization: Bearer kp_int_<keyId>.<secret>`. `keyId` is 1–32 ASCII letters, digits, `_` or `-`. Generate 32 cryptographically random secret bytes and encode them as canonical base64url without padding (43 characters). `sha256` is the lowercase hexadecimal SHA-256 digest of the **decoded 32 bytes**, not the encoded text. Only the verifier belongs in KaiPay's private configuration; the client holds the raw credential. Secret comparison is constant time. Two verifier slots allow bounded overlap during rotation; apply configuration with a controlled restart, preserving the integration ID and binding. Disable/remove a verifier to revoke it.

Keep configuration and credentials out of source, browser bundles, URLs and logs. Provision dedicated synthetic fixtures separately. Configure a fixed trusted destination, no redirects or ambient proxy forwarding; use HTTPS with normal certificate and hostname checks off-host. Plain HTTP is suitable only for a supervised loopback demonstration. This feature does not configure ingress or authorize public exposure.

## Requests and responses

`PUT /v1/integrations/payments/{clientReference}` creates or replays one immutable request. `clientReference` is a canonical lowercase UUID. Send `Content-Type: application/json` and exactly:

```json
{"amountCents":1234,"currency":"MYR"}
```

`amountCents` means integer MYR sen and must be 1–1,000,000,000. Fractions, numeric strings, zero, unknown/duplicate fields and bodies above 1 KiB are rejected. Do not send merchant/customer headers, a separate idempotency header, payment methods, metadata, participant credentials or personal data. The server fixes merchant/customer from the binding and fixes method to null and metadata to an empty object.

`GET /v1/integrations/payments/{clientReference}` reads the current retained payment and never creates one. Both methods return 200 on success, including first creation and concurrent equivalent replay:

```json
{
  "success": true,
  "data": {
    "clientReference": "27b7b67c-4b28-5980-9590-323b95d1fe33",
    "paymentId": "250d3eba-40eb-4f48-afcf-5a13131ac544",
    "amountCents": 1234,
    "currency": "MYR",
    "status": "CREATED",
    "version": 0,
    "simulation": true,
    "updatedAt": "2026-10-28T04:00:00Z"
  }
}
```

The response also includes the standard success message/timestamp. Cache headers are `no-store`. Status and version come from retained payment state, including after the ordinary 24-hour response cache expires. Store and compare payment identity and version when applying observations; reject a changed identity or equal-version conflicting state, and ignore older versions. `AUTHORIZED`, `DECLINED` and definitive `FAILED` are distinct payment outcomes. `CREATED`/`PROCESSING` may remain unresolved; a timeout or exhausted delivery is not evidence of rejection. Unexpected capture/refund states require investigation, not a settlement update.

The retained key is `int1:<integrationId>:<clientReference>`. Equivalent requests return the same payment ID; an immutable mismatch returns 409 without a second payment. Payment/cache existence is read in one consistent PostgreSQL statement. Recognized concurrent creation losers are recovered only after transaction rollback; every successful core return is followed by a fresh retained-state validation. Stable orphan-cache state fails closed. No external exactly-once guarantee or cross-project atomic transaction is implied.

| HTTP | `code` | Meaning |
| --- | --- | --- |
| 400 | `INVALID_REQUEST` | Malformed or unsupported request |
| 401 | `UNAUTHORIZED` | Missing/invalid/disabled credential (includes `WWW-Authenticate: Bearer`) |
| 403 | `FORBIDDEN` | Inactive binding or disallowed route/method |
| 404 | `NOT_FOUND` | No own-reference payment on GET |
| 409 | `IDEMPOTENCY_CONFLICT` | Immutable request differs |
| 409 | `RECOVERY_REQUIRED` | Retained identity/cache requires operator recovery |
| 429 | `RATE_LIMITED` | Honor `Retry-After` |
| 503 | `TEMPORARILY_UNAVAILABLE` | Dependency or transaction uncertainty; preserve reference/body |

Errors contain `success:false`, numeric `status`, stable `code` and a sanitized `message`. Network uncertainty must be recovered with GET or an explicit identical PUT; never generate a fresh reference to bypass it. After a database restore, disable writes until both sides' retained identities and namespace have been reconciled. A 404 cannot establish that a payment never existed after history loss.

## Security and operational bounds

A separate stateless security chain accepts only these two methods and loads/saves no browser session context. Cookie and Origin headers are rejected; there is no browser CORS access. Machine credentials cannot borrow browser authority or call list, capture/refund, ledger, diagnostics, webhook or reconciliation APIs. Browser sessions alone cannot call this API.

Per-process admission allows 30 PUT and 120 GET requests per binding per minute, four simultaneous machine requests, 240 admitted requests per peer per minute and 2,000 total, with at most 4,096 retained peer budgets. Limits reset on restart and are not a distributed quota. Snapshot queries have a two-second database statement timeout. Apply normal ingress connection/body time bounds before any separately authorized hosting. Poll with a bounded schedule, stop after terminal outcomes and distinguish stale observations from new evidence. Keep payment/simulator identities and namespace together for retention and recovery; do not purge/reuse reference keys.
