# Experimental notification observation server

This standalone Java 21 / Spring Boot process supports synthetic checkouts and opt-in Android notification candidates. It has no dependency on KaiPay's payment backend, database, Kafka, refund services, payment transitions, or ledger. **An authenticated Android report is unverified client evidence, never settlement proof or proof of a particular order.** Real TNG classification and delivery on a fresh ordinary notification remain **UNVERIFIED** until separately observed on the receiver's phone.

The experiment is disabled by default and binds `127.0.0.1:28082`. Keep the receiver backend on laptop loopback. Optional public payer access must use the separate payer gateway below; never tunnel directly to the receiver backend. The Android debug app uses the USB development tunnel `adb reverse tcp:28082 tcp:28082` and its narrowly allowed `http://127.0.0.1:28082` destination. Remote configurations require actual HTTPS and normal certificate verification; forwarded headers never establish transport trust.

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

Provision the live credential and assigned UUID in the companion app privately. Never put receiver/device credentials in URLs, screenshots, diagnostics, logs, APKs or Git. Enter only the receiver and simulation credentials in separate password fields at `http://127.0.0.1:28082/`. The browser keeps them in memory and clears them on leaving or using its cleanup control.

## Private payer checkout

The receiver page at `/` creates an experimental request with an expected RM amount and a server-generated request UUID as its reference. **Get private payer link** issues a read capability for that request. For remote access, enter your approved HTTPS payer gateway origin before issuing the `/checkout#id=…&key=…` link. Anyone holding it can read that request; this is a bearer capability, not payer identity. Do not record it in screenshots, logs, fixtures or Git. Issuing a link does not start a tunnel or configure a payment destination.

The separate payer page shows only the reference, amount, payment/review deadlines, actual request status, review availability and a limited evidence label. It polls every three seconds, marks failed updates stale, and cannot list requests, read candidates, simulate, resolve, verify or confirm. It removes the fragment on load, uses the capability only in the Authorization header, and keeps the payer bearer out of local and session storage. Reloading requires reopening the original link. Links expire after 15 minutes, bounded by wall and elapsed process time. Reissuing replaces the previous link; **Revoke payer link** immediately revokes it. Backend restart revokes all links without changing persisted requests or replay history. Gateway restart alone does not revoke backend-held capabilities. Clear/leave controls erase page-held access and stop polling.

Without owner configuration the placeholder is nonpayable. To display the owner's genuine personal TNG receiving QR, privately supply its original PNG as `receiving.png` in a dedicated local directory outside source and build folders. Set `BRIDGE_QR_DIRECTORY` to that absolute directory before starting the process. Keep the directory private and Git-ignored; never put personal QR material in source, fixtures, logs or screenshots. Only this fixed filename is read, once at startup, with no upload or request-selected path. The server never rewrites the original bytes or calls an external service. Replace the file and restart to change it; clearing the setting and restarting disables it. Existing payer links are revoked by restart.

Only a static PNG of 128–2048 pixels on each side and at most 2,000,000 bytes is supported. Container checks, CRCs and image decoding reject malformed content, animations and trailing data. Missing/invalid configured files prevent startup with a sanitized message. Relative/traversing paths, network UNC paths and symbolic links/junctions are rejected. Image validation does not establish QR recognition, destination identity or wallet eligibility. An unsupported original format must be privately exported to a supported PNG and separately checked by the owner for recognition; do not generate an unofficial payment payload.

The authorized payer browser fetches `GET /api/checkout/{id}/qr` with its memory-only bearer header and displays an in-memory blob URL. Credentials never enter image URLs. Responses are no-store; receiver/device/simulator credentials cannot retrieve this payer asset. The server permits retrieval only for an awaiting-payment request with its payment window open and no selected or synthetic evidence. Detection, ambiguity, verification, confirmation, expiry and review expiry deny retrieval. The UI also hides it during unavailable status and after the displayed deadline using local time; local time can only hide a code, never authorize it. No public static QR route exists. A previously copied image cannot be revoked and these deadlines cannot prevent a wallet transfer. Do not capture actual QR images in browser tests or diagnostic evidence.

The QR may contain neither this amount nor the KaiPay reference. The payer must independently inspect the destination in the official TNG app, enter the exact expected RM amount manually unless already present, and authorize there. A separate screen may be needed to scan. QR recognition testing requires no transfer and must separately confirm the intended destination and amount-entry behavior. This private technical experiment does not establish eligibility for commercial use of a personal wallet; any future commercial use requires a separate terms/eligibility decision. No genuine payment test is authorized by configuration or synthetic success.

Candidate receipt only means **Potential payment detected — unverified**. Genuine confirmation policy is **ALWAYS_MANUAL**: independently inspect actual TNG history, find the incoming transaction and matching amount, identify the intended checkout, and resolve ambiguity/conflicts before attesting. One deliberate receiver submission runs the existing verification and confirmation endpoints in order; the server persists and validates the transitions separately. If only verification succeeds, the checkout remains awaiting receiver confirmation and may be retried. It never appears confirmed merely because the first step succeeded. Synthetic evidence uses a separate invented-scenario acknowledgment and never asserts a real history check or funds received. **SMART_CONFIRMATION is future/unavailable** and would require an independently authenticated settlement source plus reliable request association; notifications alone never enable it. No automatic-confirmation policy is added.

Amount/time matching cannot identify payer intent or prove settlement, durable payer identity or absence of reversal. Unmatched observations stay on the receiver page without confirming unrelated checkouts. New lifecycle-v2 requests close candidate admission ten minutes after creation. A unique automatic candidate admitted by the server before that cutoff receives an immutable receiver-review deadline, defaulting to 30 additional minutes after the payment cutoff. Set `BRIDGE_REVIEW_PERIOD_SECONDS` to an integer from 300 through 1800 before startup to use 5–30 additional minutes; invalid configuration prevents startup. Polling, retries, verification and restart never extend the deadline. A match stays unverified until the receiver explicitly checks and confirms it. Without a grant the request becomes `EXPIRED`; with an unconfirmed grant it becomes `REVIEW_EXPIRED` at the review deadline. Neither can be reopened or confirmed. Manual confirmation is an experimental receiver record, never a KaiPay financial transition or provider settlement proof.

API boundaries: receiver-only `POST /api/requests/{id}/payer-access` with JSON `{}` returns `{requestId, token}`; receiver-only `POST /api/requests/{id}/payer-revoke` revokes it and returns 204. Payer-only `GET /api/checkout/{id}` accepts exactly one request-scoped bearer header and no query string. Its JSON allowlist is `{id, amountMinor, createdAt, expiresAt, status, evidence, paymentWindowClosed, reviewExpiresAt, reviewAvailable}`. `expiresAt` remains the payment deadline. The evidence enum is `NONE`, `SYNTHETIC` or `NOTIFICATION_UNVERIFIED`; it does not establish presence or absence of money. Event/device IDs, admission times and grant internals never appear in the payer response. The capability is a random 256-bit process-local read grant with a fixed 15-minute lifetime and grants no receiver/simulator/device role. At most 500 grants are retained and none enter the snapshot.

The live credential and UUID are optional together: omitting both disables new routes and preserves the simulator. Supplying one, malformed values or reused credentials prevents enabled startup. Revoke/rotate by stopping the server, replacing the live credential or removing both live settings, and restarting. Credential rotation retains the assigned UUID and replay history. A replacement UUID establishes a new device scope; historical evidence remains in the private store.

After stopping the experiment, clear environment settings:

```powershell
Remove-Item Env:BRIDGE_ENABLED, Env:BRIDGE_RECEIVER_TOKEN, Env:BRIDGE_DEVICE_TOKEN, Env:BRIDGE_LIVE_DEVICE_TOKEN, Env:BRIDGE_LIVE_DEVICE_ID
```

## Synthetic end-to-end demonstration

1. Leave QR configuration empty for a no-transfer demo. Enter receiver/simulation credentials in the browser. Create an invented checkout such as `12.34`. Its placeholder contains no payment code. Creation starts three-second polling.
2. Use **Simulate candidate** for the existing browser path, or provision the Android debug companion, deliberately enable detection and experimental delivery, and trigger its labeled synthetic event. No wallet transfer is needed.
3. The page reports **Potential incoming payment — unverified**, showing evidence source and disposition. Android synthetic events are explicitly `SYNTHETIC` and cannot use a real notification profile.
4. As receiver, select the invented-scenario acknowledgment and confirm the demo. The UI executes verification then confirmation separately. Real evidence instead requires independent actual TNG history checking and explicit receiver attestation first.
5. Creating another checkout with an already-held amount is rejected. A second distinct same-amount candidate makes the unresolved checkout ambiguous and permanently revokes extended review. Fresh Android unmatched/ambiguous evidence can be explicitly resolved only before payment closes and only to a compatible request that never had a grant; separate verification and confirmation remain necessary. Resolution never grants extended review. Delayed, expired, changed, consumed or revoked-grant evidence cannot be resolved.

This is a single-owner experimental screen. Creation, receiver review and status polling require the receiver credential. Public payer access uses a request-scoped bearer capability, not a customer identity or settlement assertion. Dynamic rendering uses text nodes and a restrictive content-security policy.

## Optional remote payer gateway

The same artifact includes a separate loopback-only payer gateway. With the private backend running on 28082, start the gateway in its own terminal using JDK 21:

```powershell
java -Dloader.main=com.lky.experimental.bridge.PayerGateway -cp target/notification-simulator-0.1.0-SNAPSHOT.jar org.springframework.boot.loader.launch.PropertiesLauncher 28083 28082
```

The first port is the gateway; the second is the fixed loopback receiver backend. The gateway process needs no receiver, simulation or Android credentials. It serves only `GET /checkout`, `/checkout.js`, `/demo.css`, `/api/checkout/{lowercase UUID}` and `/api/checkout/{lowercase UUID}/qr`. The last two require the request's payer bearer. All other routes and methods, query strings, encoded paths, extra path segments and redirects are denied. Valid HTTP/1.1 absolute-form targets with matching Host use the same path allowlist; their authority never selects the upstream. The gateway forwards neither incoming proxy headers nor cookies. Receiver administration, issuance/revocation, confirmation, simulation and Android ingestion stay on the private backend.

Public HTTPS ingress requires a separately authorized operator setup pointing **only to the payer gateway**. No public ingress is enabled by building or starting this process. A temporary Cloudflare Quick Tunnel is a low-cost experimental option; its hostname changes and its availability is not guaranteed. Cloudflare terminates TLS and can process bearer headers and checkout/QR content. Tailscale Funnel is an alternative, but enabling it on the private Serve port would make that entire port public. Keep private Android Serve delivery on its existing separate port. Ordinary payer browsers require HTTPS; loopback HTTP is supported for local testing. Forwarded headers never establish authority or transport trust.

When HTTPS ingress uses a cookie-based access gate, payer status and QR fetches include same-origin credentials. The gateway strips cookies before forwarding; the private API still requires the payer bearer. No cross-origin credentials are enabled.

Enter only the controlled HTTPS gateway origin in the private receiver workspace before issuing a remote link. Share the fragment link privately. Capabilities are in-memory, read-only, request-bound and expire after 15 minutes; reissue/revoke/backend restart invalidates them. The receiver UI and payer page clear held credentials on cleanup or navigation. Reloading requires the original still-valid link. Requests and payment/review deadlines are unchanged; expiration of payer access neither extends nor confirms a checkout. No token belongs in a query, server URL, access log or browser storage.

The gateway bounds connections (64), worker threads (4), queued tasks (16), request line/headers (8192 bytes, 32 fields), idle/read/write waits (5 seconds) and upstream retrieval (4 seconds, 4096-byte status or 2 MB PNG). A shared burst-60/refill-10-per-second budget and per-capability burst-6/refill-one-per-two-seconds budget return 429 with Retry-After. Per-capability entries are bounded with LRU eviction; aggregate limits remain under random-token churn. These limits can reduce availability under sustained abuse. Three-second polling is intended for a small owner-managed experiment.

Keep no-store, strict CSP, no-referrer and access logging disabled throughout any ingress; add no analytics or third-party content. Do not cache payer status or QR responses at the edge. The gateway does not repair unattended Android listener reconnection: missing evidence must remain unconfirmed, and the awake laptop, connectivity and owner recovery are operational dependencies. Public internet reachability requires an actual separately approved network test; local loopback tests establish only the application boundary.

To stop, terminate the specific public connector first and verify its public host no longer serves checkout. Revoke payer access, clear the browser and stop the gateway. Stopping the backend revokes every grant; stopping only the gateway temporarily interrupts access. Preserve unrelated private Serve configuration and Android settings. Never publish a genuine private QR as part of a synthetic test.

## Private API contract

Send exactly one `Authorization: Bearer <credential>` header. No cookies, query credentials or cross-origin access are supported.

| Route | Credential | Behavior |
| --- | --- | --- |
| `GET /api/state` | Receiver | Requests and minimized observation evidence |
| `POST /api/requests` | Receiver | `{"amountMinor":1234}`; exact integer MYR cents |
| `POST /api/observations` | Simulation | Existing six-field `SIMULATED` schema/freshness |
| `POST /api/notification-candidates` | Live device | Strict minimized Android candidate below |
| `POST /api/notification-candidates/{eventId}/invalidate` | Live device | `{"reason":"NOTIFICATION_CHANGED"}`; invalidate lifecycle evidence |
| `POST /api/observations/{eventId}/resolve` | Receiver | `{"requestId":"<UUID>","historyChecked":true}`; explicit fresh Android resolution |
| `POST /api/requests/{id}/verify` | Receiver | `{"historyChecked":true,"expectedEventId":"<selected event UUID>"}`; begin candidate-bound verification |
| `POST /api/requests/{id}/confirm` | Receiver | Same candidate-bound attestation; separate manual confirmation |

Candidate fields are `eventId` (stable random UUID), `notificationId` (random local lifecycle UUID, never Android notification key), `sequence` (integer), `deviceId` (assigned UUID), `source`, `classification`, `classifierProfile`, `amountMinor` (integer MYR cents), `observedAt` (textual UTC ISO instant ending in `Z`) and `evidenceQuality`.

Exactly two reviewed tuples are accepted:

| Source | Classification | Classifier profile | Evidence quality |
| --- | --- | --- | --- |
| `TNG_NOTIFICATION_CANDIDATE` | `INCOMING_CANDIDATE` | `tng.receipt.en.observed.v1` | `RULE_MATCHED` |
| `SYNTHETIC` | `INCOMING_CANDIDATE` | `synthetic.debug.v1` | `SYNTHETIC` |

The live credential's configured UUID must equal the reported UUID. No raw title/body, sender, wallet details, OTP, screenshot, Android notification key or provider transaction reference is accepted or invented. Amounts range from 1 to 1,000,000 cents; sequences from 1 to 1,000,000. Unknown fields, duplicate keys, trailing JSON, string/decimal amounts, numeric sources and bodies over 4KB are rejected. Sanitized errors/logs contain neither rejected payloads nor credentials.

The old simulation route retains its six fields (`eventId`, `notificationId`, `sequence`, `observedAt`, `amountMinor`, exactly `SIMULATED`) and 120-second freshness / 30-second future tolerance. Android candidates can be up to 24 hours old, with the same future tolerance. Older than 120 seconds is persisted as `DELAYED`: never automatically matched or manually resolved. Older than 24 hours is rejected. Exact persisted retries are acknowledged after age expiry and process restart; altered event UUID reuse is rejected. Retry the identical complete payload and UUID when acknowledgement is lost.

## Tentative association and manual checks

Server admission time, sampled inside the serialized service operation, governs candidate eligibility. Device `observedAt` is untrusted: it can conservatively disqualify stale, future or pre-request evidence, but cannot prove when money was sent or override a closed payment window. An event first admitted at or after the payment cutoff cannot match, even if its device timestamp says otherwise. Successful admission/grant state persists together; a failed write does not reserve an earlier admission time. Exact retained event retries never rematch or reset a deadline.

Matching amount/time provides a tentative association only. Multiple matching legacy requests or competing candidates for an unconfirmed association become `AMBIGUOUS`; expiry never creates automatic certainty. A late same-amount candidate, changed lifecycle or invalidation revokes an unconfirmed review grant rather than replacing its candidate. The original review deadline and amount hold remain, including after revocation. The same amount cannot be used for another open checkout until the applicable deadline; different amounts remain independent. One retained event/lifecycle cannot confirm multiple requests. New identifiers or forged plausible device time cannot establish distinct real transactions; independent history and intended-checkout checking remain essential.

The receiver submits the selected event ID with both verification and confirmation. The server validates the current association, disposition, grant, deadlines and attestation separately for each operation. Verification before a deadline never reserves time for a later confirmation. If verification succeeded but confirmation failed, refresh and renew the unchecked attestation before retrying. A same-event already-confirmed retry returns the immutable recorded result even after its deadline or later evidence invalidation; another event ID is rejected. No automatic confirmation, reject/refund action or expired-request override is provided.

Increasing-sequence updates become `UPDATE_IGNORED`, cannot represent another payment and invalidate provisional associations. Repeated/lower sequences and backwards timestamps are rejected. An unsupported changed notification uses a separate device-authorized invalidation. This persists `INVALIDATED` evidence and makes provisional requests `AMBIGUOUS`. Invalidated evidence cannot resolve, verify or confirm. Repeated invalidation is idempotent. An original not yet received returns `404 OBSERVATION_NOT_FOUND`, allowing the companion to upload it first. Already manually confirmed experimental requests are retained if evidence later changes; no financial rollback is performed.

History checking is a human attestation. The prototype cannot authenticate TNG transaction history, notification provenance, transaction identity, settlement or order identity. Device authentication identifies the configured reporter only. Notifications never automatically confirm requests or mutate KaiPay financial state.

## Private persistence and bounds

The default `.local/observations.json` contains minimized evidence, including real amounts/timestamps when deliberately enabled. Keep it private and ignored. Credentials are never persisted. One process owns a sibling file lock. Each mutation forces a temporary file and atomically replaces the snapshot before exposing success. Replacement failure returns `503 STORE_UNAVAILABLE`, without consuming event identity or publishing in-memory changes. This supports process restart without claiming power-loss durability, multi-node operation or disaster recovery.

Bounds are 500 requests, 1,000 observations and two million snapshot bytes. Capacity refuses growth instead of deleting replay history. Reset deliberately with the process stopped; reset removes replay protection, so never reuse old event/lifecycle UUIDs. Neither these bounds nor synthetic success establishes immediate delivery, exactly-once transport or reliable background execution under all Android battery-saving modes.

Active receiver verification/confirmation also fails closed when observation capacity or the reserved snapshot headroom could prevent recording a competitor. Capacity recovery is an explicit offline operation; it never deletes replay history automatically. Already-confirmed same-event retries remain no-ops. Failed I/O cannot expose a successful transition, but the process does not claim knowledge of reports never durably admitted.

New requests are versioned v2; snapshots retain server admission times, immutable review grants and a payment-closed latch. A latched closed window and terminal states do not reopen after a clock rollback. Absolute timing still trusts the host clock. Existing requests without a lifecycle version retain the original ten-minute behavior and never gain review from historical device timestamps or MATCHED labels. Existing expired/confirmed states and evidence remain historical. Actionable v2 associations are validated on startup and invalid snapshots fail closed. Back up private stores before upgrading: the old binary cannot read new fields/states. Rolling back requires stopping the process and deliberately restoring a pre-upgrade private backup, with loss of later experimental changes; never silently discard new fields or replay history.

Real ordinary-payment validation remains a separate opt-in experiment: observe a fresh notification on the receiver's phone, verify the supported strict classification, inspect the unverified server/browser evidence, and independently check transaction history before receiver confirmation. Synthetic tests do not establish real TNG compatibility or production reliability.
