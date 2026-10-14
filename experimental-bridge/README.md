# Experimental notification observation server

This standalone Java 21 / Spring Boot process supports synthetic checkouts and opt-in Android notification candidates. It has no dependency on KaiPay's payment backend, database, Kafka, refund services, payment transitions, or ledger. **An authenticated Android report is unverified client evidence, never settlement proof or proof of a particular order.** Real TNG classification and delivery on a fresh ordinary notification remain **UNVERIFIED** until separately observed on the receiver's phone.

The experiment is disabled by default and binds `127.0.0.1:28082`. Keep it on laptop loopback: no public hosting, inbound ports or third-party tunnels. The Android debug app uses the USB development tunnel `adb reverse tcp:28082 tcp:28082` and its narrowly allowed `http://127.0.0.1:28082` destination. Remote configurations require actual HTTPS and normal certificate verification; forwarded headers never establish transport trust.

## Build and local startup

Use JDK 21 and run `mvn -o verify` from this directory. Cached dependencies are required. Tests exercise actual loopback HTTP and atomic snapshot replacement; use a terminal permitted to perform these operations. Do not replace assertions to bypass sandbox restrictions.

Provision three separate random experimental credentials outside source/build files. Never reuse wallet, bank or production credentials. The receiver credential authorizes manual verification, the simulation credential authorizes the browser simulator, and the live credential authorizes only Android candidates and invalidations. Each must contain 32–256 ASCII letters, digits, underscores or hyphens and be distinct.

```powershell
function New-ExperimentalToken {
    $bytes = New-Object byte[] 32
    $generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $generator.GetBytes($bytes) } finally { $generator.Dispose() }
    [BitConverter]::ToString($bytes).Replace('-', '')
}
$env:BRIDGE_RECEIVER_TOKEN = New-ExperimentalToken
$env:BRIDGE_DEVICE_TOKEN = New-ExperimentalToken
$env:BRIDGE_LIVE_DEVICE_TOKEN = New-ExperimentalToken
$env:BRIDGE_LIVE_DEVICE_ID = [guid]::NewGuid().ToString()
$env:BRIDGE_ENABLED = 'true'
java -jar target/notification-simulator-0.1.0-SNAPSHOT.jar
```

Provision the live credential and assigned UUID in the companion app privately. Never put credentials in URLs, screenshots, diagnostics, logs, APKs or Git. Enter only the receiver and simulation credentials in separate password fields at `http://127.0.0.1:28082/`. The browser keeps them in memory and clears them on leaving or using its cleanup control.

The live credential and UUID are optional together: omitting both disables new routes and preserves the simulator. Supplying one, malformed values or reused credentials prevents enabled startup. Revoke/rotate by stopping the server, replacing the live credential or removing both live settings, and restarting. Credential rotation retains the assigned UUID and replay history. A replacement UUID establishes a new device scope; historical evidence remains in the private store.

After stopping the experiment, clear environment settings:

```powershell
Remove-Item Env:BRIDGE_ENABLED, Env:BRIDGE_RECEIVER_TOKEN, Env:BRIDGE_DEVICE_TOKEN, Env:BRIDGE_LIVE_DEVICE_TOKEN, Env:BRIDGE_LIVE_DEVICE_ID
```

## Synthetic end-to-end demonstration

1. Enter receiver/simulation credentials in the browser. Create an invented checkout such as `12.34`. Its placeholder QR contains no payment code. Creation starts three-second polling.
2. Use **Simulate candidate** for the existing browser path, or provision the Android debug companion, deliberately enable detection and experimental delivery, and trigger its labeled synthetic event. No wallet transfer is needed.
3. The page reports **Potential incoming payment — unverified**, showing evidence source and disposition. Android synthetic events are explicitly `SYNTHETIC` and cannot use a real notification profile.
4. As receiver, begin manual verification and confirm separately. For a real notification, independently check actual TNG transaction history and the correct request first. For invented evidence, acknowledge the synthetic scenario.
5. Identical pending amounts remain ambiguous. Fresh Android unmatched/ambiguous evidence can be explicitly resolved to an eligible request after receiver history checking; separate verification and confirmation remain necessary. Delayed, expired, changed or consumed evidence cannot be resolved.

This is a single-owner experimental screen. Creation, receiver review and status polling require the receiver credential. It does not provide a public customer authentication flow or contain real account/QR details. Dynamic rendering uses text nodes and a restrictive content-security policy.

## API contract

Send exactly one `Authorization: Bearer <credential>` header. No cookies, query credentials or cross-origin access are supported.

| Route | Credential | Behavior |
| --- | --- | --- |
| `GET /api/state` | Receiver | Requests and minimized observation evidence |
| `POST /api/requests` | Receiver | `{"amountMinor":1234}`; exact integer MYR cents |
| `POST /api/observations` | Simulation | Existing six-field `SIMULATED` schema/freshness |
| `POST /api/notification-candidates` | Live device | Strict minimized Android candidate below |
| `POST /api/notification-candidates/{eventId}/invalidate` | Live device | `{"reason":"NOTIFICATION_CHANGED"}`; invalidate lifecycle evidence |
| `POST /api/observations/{eventId}/resolve` | Receiver | `{"requestId":"<UUID>","historyChecked":true}`; explicit fresh Android resolution |
| `POST /api/requests/{id}/verify` | Receiver | `{"historyChecked":true}`; begin verification |
| `POST /api/requests/{id}/confirm` | Receiver | Same attestation; separate manual confirmation |

Candidate fields are `eventId` (stable random UUID), `notificationId` (random local lifecycle UUID, never Android notification key), `sequence` (integer), `deviceId` (assigned UUID), `source`, `classification`, `classifierProfile`, `amountMinor` (integer MYR cents), `observedAt` (textual UTC ISO instant ending in `Z`) and `evidenceQuality`.

Exactly two reviewed tuples are accepted:

| Source | Classification | Classifier profile | Evidence quality |
| --- | --- | --- | --- |
| `TNG_NOTIFICATION_CANDIDATE` | `INCOMING_CANDIDATE` | `tng.receipt.en.observed.v1` | `RULE_MATCHED` |
| `SYNTHETIC` | `INCOMING_CANDIDATE` | `synthetic.debug.v1` | `SYNTHETIC` |

The live credential's configured UUID must equal the reported UUID. No raw title/body, sender, wallet details, OTP, screenshot, Android notification key or provider transaction reference is accepted or invented. Amounts range from 1 to 1,000,000 cents; sequences from 1 to 1,000,000. Unknown fields, duplicate keys, trailing JSON, string/decimal amounts, numeric sources and bodies over 4KB are rejected. Sanitized errors/logs contain neither rejected payloads nor credentials.

The old simulation route retains its six fields (`eventId`, `notificationId`, `sequence`, `observedAt`, `amountMinor`, exactly `SIMULATED`) and 120-second freshness / 30-second future tolerance. Android candidates can be up to 24 hours old, with the same future tolerance. Older than 120 seconds is persisted as `DELAYED`: never automatically matched or manually resolved. Older than 24 hours is rejected. Exact persisted retries are acknowledged after age expiry and process restart; altered event UUID reuse is rejected. Retry the identical complete payload and UUID when acknowledgement is lost.

## Tentative association and manual checks

Requests expire ten minutes after creation. Matching amount/time provides a tentative association only. Evidence predating a request or outside its time window cannot match. Multiple matching active requests or competing candidates for an unconfirmed association become `AMBIGUOUS`. Expiry never creates automatic certainty. Resolution requires fresh Android evidence, exact amount/time compatibility, an active unassigned request and an unconsumed lifecycle. One candidate/lifecycle cannot confirm multiple requests.

Increasing-sequence updates become `UPDATE_IGNORED`, cannot represent another payment and invalidate provisional associations. Repeated/lower sequences and backwards timestamps are rejected. An unsupported changed notification uses a separate device-authorized invalidation. This persists `INVALIDATED` evidence and makes provisional requests `AMBIGUOUS`. Invalidated evidence cannot resolve, verify or confirm. Repeated invalidation is idempotent. An original not yet received returns `404 OBSERVATION_NOT_FOUND`, allowing the companion to upload it first. Already manually confirmed experimental requests are retained if evidence later changes; no financial rollback is performed.

History checking is a human attestation. The prototype cannot authenticate TNG transaction history, notification provenance, transaction identity, settlement or order identity. Device authentication identifies the configured reporter only. Notifications never automatically confirm requests or mutate KaiPay financial state.

## Private persistence and bounds

The default `.local/observations.json` contains minimized evidence, including real amounts/timestamps when deliberately enabled. Keep it private and ignored. Credentials are never persisted. One process owns a sibling file lock. Each mutation forces a temporary file and atomically replaces the snapshot before exposing success. Replacement failure returns `503 STORE_UNAVAILABLE`, without consuming event identity or publishing in-memory changes. This supports process restart without claiming power-loss durability, multi-node operation or disaster recovery.

Bounds are 500 requests, 1,000 observations and two million snapshot bytes. Capacity refuses growth instead of deleting replay history. Reset deliberately with the process stopped; reset removes replay protection, so never reuse old event/lifecycle UUIDs. Neither these bounds nor synthetic success establishes immediate delivery, exactly-once transport or reliable background execution under all Android battery-saving modes.

Real ordinary-payment validation remains a separate opt-in experiment: observe a fresh notification on the receiver's phone, verify the supported strict classification, inspect the unverified server/browser evidence, and independently check transaction history before receiver confirmation. Synthetic tests do not establish real TNG compatibility or production reliability.
