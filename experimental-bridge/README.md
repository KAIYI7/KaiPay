# Experimental notification observation server

For private synthetic startup, health interpretation, exact-process shutdown and supervised recovery, see [local operations](OPERATIONS.md).

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

An optional fourth credential, `BRIDGE_INTEGRATION_TOKEN`, enables [synthetic integration mode](#synthetic-integration-mode). It must follow the same format and differ from the other three.

Provision the live credential and assigned UUID in the companion app privately. Never put receiver/device credentials in URLs, screenshots, diagnostics, logs, APKs or Git. Enter only the receiver and simulation credentials in separate password fields at `http://127.0.0.1:28082/`. The browser keeps them in memory and clears them on leaving or using its cleanup control.

## Private payer checkout

The receiver page at `/` creates an experimental request with an expected RM amount and a server-generated request UUID as its reference. **Issue checkout code** returns separate shareable items: a clean `/checkout` URL and a private `KP1.<request UUID>.<bearer>` code. For remote access, enter only your approved HTTPS payer gateway origin first. Send the clean URL first: the payer completes any provider warning/sign-in, reaches the real KaiPay page, then explicitly pastes the code into **Enter/Paste checkout code**. Never append the code to a URL or paste it into a provider page. Anyone holding it can read that request; this is a bearer capability, not payer identity. Do not record it in screenshots, logs, fixtures or Git. Issuing a code does not start a tunnel or configure a payment destination.

The payer page re-checks status immediately when it becomes visible again, gains focus or comes back online, in every mode, besides its three-second polling. Every awaited step of a status check is bounded by the same 10-second abort, so a stalled step cannot stop later checks. The "Updated" line shows when status was last read.

The separate payer page shows only the reference, amount, payment/review deadlines, actual request status, review availability and a limited evidence label. The code contains the existing request UUID and unchanged 256-bit, 43-character bearer; it is not a shorter numeric PIN or a new exchange/session API. Submitting clears the password field immediately. Payer access stays in a JavaScript closure and travels only in the Authorization header, never in URL paths, queries, fragments, cookies, browser storage or telemetry. Reloading, leaving or returning from browser history clears access and requires code entry again. Invalid, expired or revoked access stops polling and asks for a new code; temporary network/backend failure hides the QR and retries using memory only. An active checkout polls every three seconds and cannot list requests, read candidates, simulate, resolve, verify or confirm. Codes expire after 15 minutes, bounded by wall and elapsed process time. **Replace checkout code**, **Revoke checkout code** and backend restart invalidate prior access without changing persisted requests or replay history. Gateway restart alone does not revoke backend-held capabilities. Clear/leave controls erase page-held access and stop polling.

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
Remove-Item Env:BRIDGE_ENABLED, Env:BRIDGE_RECEIVER_TOKEN, Env:BRIDGE_DEVICE_TOKEN, Env:BRIDGE_LIVE_DEVICE_TOKEN, Env:BRIDGE_LIVE_DEVICE_ID, Env:BRIDGE_INTEGRATION_TOKEN, Env:BRIDGE_INTEGRATION_OBSERVED_AMOUNT_MINOR -ErrorAction SilentlyContinue
```

## Synthetic end-to-end demonstration

1. Leave QR configuration empty for a no-transfer demo. Enter receiver/simulation credentials in the browser. Create an invented checkout such as `12.34`. Its placeholder contains no payment code. Creation starts three-second polling.
2. Use **Simulate synthetic candidate at this checkout's amount** on the checkout's card for the browser path. It sends a `SIMULATED` candidate at that checkout's amount with the simulation credential, and appears only while the checkout is open (not terminal, payment window not closed) and has no genuine notification evidence. The developer-tools button instead uses the amount typed in the create field. Alternatively, provision the Android debug companion, deliberately enable detection and experimental delivery, and trigger its labeled synthetic event. No wallet transfer is needed.
3. The page reports **Potential incoming payment — unverified**, showing evidence source and disposition. Android synthetic events are explicitly `SYNTHETIC` and cannot use a real notification profile.
4. As receiver, select the invented-scenario acknowledgment and confirm the demo. The UI executes verification then confirmation separately. Real evidence instead requires independent actual TNG history checking and explicit receiver attestation first.
5. Creating another checkout with an already-held amount is rejected. A second distinct same-amount candidate makes the unresolved checkout ambiguous and permanently revokes extended review. Fresh Android unmatched/ambiguous evidence can be explicitly resolved only before payment closes and only to a compatible request that never had a grant; separate verification and confirmation remain necessary. Resolution never grants extended review. Delayed, expired, changed, consumed or revoked-grant evidence cannot be resolved.

This is a single-owner experimental screen. Creation, receiver review and status polling require the receiver credential. Public payer access uses a request-scoped bearer capability, not a customer identity or settlement assertion. Dynamic rendering uses text nodes and a restrictive content-security policy.

## Synthetic integration mode

Integration mode lets one separate local application, such as a bill-splitting demo, create a **synthetic, nonpayable** checkout by its own reference and read the outcome back. The bridge never calls that application: there is no webhook or push. Nothing in this mode moves money or changes any financial record, in KaiPay or in the client. Confirmation stays a manual receiver action on the private receiver page; the client only observes it.

### What changes when `BRIDGE_INTEGRATION_TOKEN` is set

- **Synthetic only, process-wide.** Startup fails if `BRIDGE_QR_DIRECTORY` is also set, so no payable QR exists. Every `TNG_NOTIFICATION_CANDIDATE` is rejected with `422 GENUINE_DISABLED` after validation and the device-scope check, and before any expiry, disqualification or other state change. Nothing is stored, so a genuine candidate cannot match, compete with, make ambiguous or disqualify any checkout. The only exception is an exact retry of a genuine event already stored before integration mode was enabled: it is acknowledged with its stored result and changes nothing. The companion marks a `422` event permanently `FAILED` and does not retry it. The labelled Android `SYNTHETIC` profile and browser `SIMULATED` candidates remain accepted.
- **Resolve guard.** As defence in depth, `resolve` refuses (`409 RESOLUTION_NOT_ELIGIBLE`) any evidence other than the labelled `SYNTHETIC` profile for a checkout created through integration.
- **Payer page copy.** The private backend's `/checkout` page describes a synthetic demo. It never fetches a QR and does not describe genuine wallets, payment codes or history checks. If a synthetic-only process is asked for a checkout with genuine evidence, the page stops and says it serves synthetic demo checkouts only. The separate payer gateway is not integration-aware; do not run it in this mode. The receiver page keeps its usual wording; for a checkout created through integration, its card also shows the client's reference labelled **Integration reference (SettleTable demo)**, so it can be matched with the client's own demo reference. That reference is receiver-only: the payer page and the payer checkout response (`/api/checkout/{id}`) never carry it.
- **A narrow fourth credential.** It can create a checkout by reference and read the checkouts it created, nothing else. Every other credentialed route refuses it, including `/api/health`, `/api/state`, review, verify, confirm, resolve, payer access and the QR (`401`, or the route's own `404` when that route is disabled). The public pages need no credential. The receiver, simulation, live and payer credentials are refused on the integration routes. Never give the receiver credential to the integration client.

### Integration API

The reference is the client's canonical **lowercase** UUID in the path. Any other spelling, an extra segment, an encoded path or a query string is an unlisted route (`404 NOT_FOUND`). Without an integration credential configured, both routes return `404 INTEGRATION_DISABLED`. Other methods return `405 METHOD_INVALID`.

`PUT /api/integrations/checkouts/{reference}` takes exactly `{"amountMinor":863,"currency":"MYR"}`. The body rules match the other JSON routes: `application/json` (`415 JSON_REQUIRED` otherwise), at most 4KB (`413 BODY_TOO_LARGE`), and no unknown fields, duplicate keys, trailing JSON, string or decimal amounts (`400 INVALID_REQUEST`). An invalid amount returns `422 AMOUNT_INVALID`; a currency other than the exact string `MYR` returns `422 CURRENCY_INVALID`. The reference is looked up **before** any hold or capacity check, so a replay is never blocked by its own hold:

| Situation | Response |
| --- | --- |
| Known reference, same amount | `200` with the bound checkout, whatever its status |
| Known reference, different amount | `409 REFERENCE_CONFLICT` |
| New reference, amount held by any open checkout (integration or not) | `409 SAME_AMOUNT_HELD`; nothing is created. A hold can last up to 40 minutes. |
| New reference, request capacity reached | `409 REQUEST_CAPACITY` |
| New reference otherwise | `201`; the checkout and its reference binding persist in one snapshot write |
| Snapshot write failed | `503 STORE_UNAVAILABLE`; nothing is bound |

`GET /api/integrations/checkouts/{reference}` returns `200` with the same body, or `404` with exactly `{"error":"REFERENCE_NOT_FOUND"}` for an unknown reference. That body differs from the filter's `NOT_FOUND`, `INTEGRATION_DISABLED` and `EXPERIMENT_DISABLED` 404s.

Both routes return this allowlist and nothing else: `reference`, `checkoutId`, `amountMinor`, `status`, `evidence` (`NONE`, `SYNTHETIC` or `NOTIFICATION_UNVERIFIED`), `createdAt`, `expiresAt` (payment deadline), `reviewExpiresAt` (`null` until a review grant exists) and `revision`. [Observed-evidence mode](#observed-evidence-integration-mode) adds one key, `evidenceMode`. Timestamps are UTC ISO-8601 strings. Observation, device and candidate identifiers, grant internals and payer material never appear.

`revision` is a persisted change counter. A new checkout starts at `1`, and every later persisted change increments it: match, ambiguity, disqualification, resolution, verification, confirmation and lazy expiry during reads. Expiry also sets an internal payment-closed latch on terminal checkouts, so the revision can rise with no visible change in the allowlist; treat that as a newer observation, not a conflict. A repeated read or an idempotent retry that changes nothing leaves it unchanged. Requests stored before revisions existed load as `0`.

### Native startup

The supervised `ops.ps1` helper stays synthetic-receiver-only and does not start this mode. Start integration mode manually from this directory with JDK 21, in a terminal you keep open. Use a fresh private store for the demo:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
# New-ExperimentalToken is defined above; these commands never print the values.
$env:BRIDGE_RECEIVER_TOKEN = New-ExperimentalToken
$env:BRIDGE_DEVICE_TOKEN = New-ExperimentalToken
$env:BRIDGE_INTEGRATION_TOKEN = New-ExperimentalToken
# Optional, only for the companion's labelled synthetic event. Keep real notification capture off on the phone.
$env:BRIDGE_LIVE_DEVICE_TOKEN = New-ExperimentalToken
$env:BRIDGE_LIVE_DEVICE_ID = [guid]::NewGuid().ToString()
Remove-Item Env:BRIDGE_QR_DIRECTORY -ErrorAction SilentlyContinue
$env:BRIDGE_STORE = '<absolute path to a fresh private synthetic store>.json'
$env:BRIDGE_ENABLED = 'true'
java -jar target/notification-simulator-0.1.0-SNAPSHOT.jar
```

Give the integration credential privately to the client application, which reads it from its own configuration. Stop with Ctrl+C in that terminal, then clear the environment as shown above together with `Env:BRIDGE_STORE`. A restart keeps checkouts, reference bindings and revisions, and revokes payer codes, so the receiver issues a new one. A diner's browser on the owner's test phone can reach the private checkout at `http://127.0.0.1:28082/checkout` through `adb reverse tcp:28082 tcp:28082`; keep the checkout code off any recorded screen.

## Observed-evidence integration mode

An owner-started variant of integration mode in which **one** integration checkout, at **one** owner-pinned amount (or, in [multi-checkout observed mode](#multi-checkout-observed-mode), a configured number of checkouts), may be matched by a genuine `TNG_NOTIFICATION_CANDIDATE` from the owner's own phone. The single-checkout form exists for one supervised own-wallet test. A notification stays UNVERIFIED evidence: it never proves who sent money, that money arrived, or settlement. Confirmation stays a manual receiver action after checking actual wallet history, exactly as in the other modes. Nothing in this mode moves money or changes any financial record. It is off unless configured, and synthetic integration mode stays the default.

### Startup

Set `BRIDGE_INTEGRATION_OBSERVED_AMOUNT_MINOR` (`bridge.integration.observed-amount-minor`) to a whole number of sen from `1` to `100`. The RM1.00 cap is compiled in; no setting can raise it. The process then refuses to start unless all of these hold:

- `BRIDGE_INTEGRATION_TOKEN` is set, so the process is in integration mode.
- `BRIDGE_LIVE_DEVICE_TOKEN` and `BRIDGE_LIVE_DEVICE_ID` are set.
- `BRIDGE_QR_DIRECTORY` is empty (the integration-mode QR refusal).
- The store is absent or empty, or holds exactly one checkout created in observed mode at the same amount, plus only genuine notification candidates. Any `SIMULATED` or `SYNTHETIC` observation, any receiver-created request, or a checkout from another mode or amount is refused. Same-store restarts exist only to finish a crashed run; use a fresh store for every attempt.

The reverse guard also holds: a process that is not in observed mode, whether synthetic integration or non-integration, refuses any store holding an observed-mode checkout, whatever its status. The observed checkout persists `"evidenceMode":"OBSERVED_NOTIFICATION"`; other requests carry no such field, so their snapshots keep their exact bytes. An older binary refuses an observed snapshot.

Once serving, every enabled process prints one text-free line from its effective configuration, for example `BRIDGE_MODE mode=OBSERVED amount=1 qr=none live=true integration=true`. The other modes print `mode=SYNTHETIC` or `mode=NON_INTEGRATION` with `amount=none`. Check this line before any real step.

### What changes

| Route | Observed mode |
| --- | --- |
| `PUT /api/integrations/checkouts/{reference}`, known reference | Unchanged: `200` replay, or `409 REFERENCE_CONFLICT` for another amount. The reference lookup still comes first. |
| `PUT`, new reference, amount other than the pinned one | `409 OBSERVED_AMOUNT_MISMATCH`; nothing is created |
| `PUT`, new reference, the store already holds its checkout | `409 OBSERVED_CHECKOUT_SPENT`; nothing is created. One checkout per store, whatever its status. |
| `PUT`, new reference otherwise | The existing capacity and `SAME_AMOUNT_HELD` checks, then `201` |
| `PUT` and `GET` success bodies | The usual allowlist **plus** `"evidenceMode":"OBSERVED_NOTIFICATION"`. Synthetic-mode bodies are unchanged, byte for byte, so a client can tell the modes apart and fail closed. |
| `POST /api/notification-candidates`, `TNG_NOTIFICATION_CANDIDATE` | After validation and the device-scope check, an exact retry of a stored event is acknowledged unchanged. Otherwise the candidate is admitted into normal matching **only if** its amount equals the pinned amount and the store's checkout exists, is not terminal and still holds its amount (payment window open, or an unconfirmed review grant running, including a revoked one after ambiguity). Every other candidate gets `422 GENUINE_DISABLED` with no state change and is never stored. Admission during the review hold is intended: a second same-amount notification makes the checkout `AMBIGUOUS` under the usual rule. |
| `POST /api/notification-candidates`, companion `SYNTHETIC` | `422 SYNTHETIC_DISABLED`, no state change |
| `POST /api/observations` (browser `SIMULATED`) | `422 SYNTHETIC_DISABLED`, no state change |
| `POST /api/requests` (receiver create) | `409 RECEIVER_CREATE_DISABLED` |
| `resolve`, verify, confirm, invalidate, payer access | Unchanged. `resolve` still refuses any non-`SYNTHETIC` evidence onto an integration checkout, so genuine evidence is never attached by hand; a missed automatic match ends the run. |

The integration credential is as narrow as before: it cannot choose the mode, the amount, the evidence or review.

For every rejected `POST` on the two live-device routes, an observed-mode process prints one line `LIVE_REJECTED code=<CODE>`. The code comes only from a compiled allowlist (the service's codes plus the filter's `UNAUTHORIZED`, `LIVE_DELIVERY_DISABLED`, `JSON_REQUIRED`, `BODY_TOO_LARGE`, `INVALID_REQUEST` and `TLS_REQUIRED`); anything else prints `UNLISTED`. The line never carries request text, an exception message, a path, a header, an event ID or an amount. At most 20 lines are printed per minute; the rest are counted and reported as `LIVE_REJECTED_SUPPRESSED count=<n>` at the next rejection after the minute, or when the bridge stops. `GENUINE_DISABLED` lines still reveal **when** unrelated incoming transfers were observed, so keep this output private to the run and delete it afterwards.

The receiver page and the payer page are rewritten by the server with startup-checked replacement tables and `data-bridge-mode="observed"`. Their copy describes an observed, unverified notification and a manual confirmation, and never claims a payment. The payer page never fetches a QR. The receiver page hides the create panel, the developer tools, the remote payer origin field, the per-card simulate control and manual resolve.

### Native startup

As for [synthetic integration mode](#native-startup), with a fresh store, the live-device credential and UUID, and:

```powershell
$env:BRIDGE_INTEGRATION_OBSERVED_AMOUNT_MINOR = '1'
```

Never start the separate payer gateway with this mode: it is not integration-aware and would serve the genuine-payment payer page in front of an observed backend. Nothing in the gateway enforces this. An observed process starts only on an empty store or its own observed store; reusing a store from any other mode, including one holding only notification candidates, refuses startup. Stop with Ctrl+C and clear the environment, including `Env:BRIDGE_INTEGRATION_OBSERVED_AMOUNT_MINOR` and `Env:BRIDGE_STORE`.

### Multi-checkout observed mode

A variant of observed-evidence mode for several payers in one supervised session. Instead of one checkout at a pinned amount, the store accepts an owner-configured **total** number of integration checkouts, each single-use, at any valid amount. Everything else in observed-evidence mode applies unchanged: notifications stay UNVERIFIED, confirmation stays a manual receiver action after checking actual wallet history, and nothing moves money or changes a financial record.

Set `BRIDGE_INTEGRATION_OBSERVED_CHECKOUT_COUNT` (`bridge.integration.observed-checkout-count`) to a positive whole number of at most nine digits, **instead of** `BRIDGE_INTEGRATION_OBSERVED_AMOUNT_MINOR`; startup refuses both together. This mode adds no cap of its own on the count or the amounts beyond the bridge-wide limits: the count must parse as above, each amount is the usual `1` to `1000000` sen, and the store's capacity still applies (`409 REQUEST_CAPACITY` at 500 requests). The other startup conditions are those of observed-evidence mode: the integration credential, the live-device credential and UUID, and no receiving QR. The store must be absent or empty, or hold from one up to the configured number of observed-mode checkouts, at any amounts, plus only genuine notification candidates; candidates without a checkout are refused. Same-store restarts exist only to finish a crashed run: use a fresh store for every session. A single-checkout store has the same marker and loads in this mode, and restarting with a larger count lets a spent store take more checkouts. The process prints `BRIDGE_MODE mode=OBSERVED amount=unpinned checkouts=<count> qr=none live=true integration=true`.

| Route | Multi-checkout observed mode |
| --- | --- |
| `PUT`, known reference | Unchanged: `200` replay, or `409 REFERENCE_CONFLICT`. The reference lookup still comes first. |
| `PUT`, new reference, the store already holds the configured number of checkouts | `409 OBSERVED_CHECKOUT_SPENT`; nothing is created. Every checkout counts, whatever its status, so a slot is never reused. Checked before lazy expiry, so the refusal writes nothing. |
| `PUT`, new reference otherwise | In order: the existing `409 REQUEST_CAPACITY` check; `409 SAME_AMOUNT_HELD` (unchanged) when another checkout holds the amount, creating nothing and using no slot, so no two checkouts hold the same amount at once; then `201` with the observed-mode body. `OBSERVED_AMOUNT_MISMATCH` never occurs in this mode. |
| `PUT` and `GET` success bodies | As in observed-evidence mode: the usual allowlist plus `"evidenceMode":"OBSERVED_NOTIFICATION"`. Each checkout is read by its own reference. |
| `POST /api/notification-candidates`, `TNG_NOTIFICATION_CANDIDATE` | An exact retry of a stored event is acknowledged unchanged. Otherwise the candidate is admitted into normal matching **only if** its amount equals that of at least one checkout that is not terminal and still holds its amount. Every other candidate gets `422 GENUINE_DISABLED` with no state change. An admitted candidate can match only a checkout of its own amount; a second same-amount notification makes that checkout `AMBIGUOUS` under the usual rule. An update of an earlier notification is admitted if its amount is held by any checkout; it never matches, but it can make the checkout its notification matched `AMBIGUOUS`, and once stored it competes with later notifications of its amount (fails safe). |
| Every other route | As in observed-evidence mode, including `resolve`, which never attaches genuine evidence to an integration checkout. |

The receiver page and its script describe several checkouts instead of one; the single-checkout mode's pages and script are unchanged. Stop with Ctrl+C and clear `Env:BRIDGE_INTEGRATION_OBSERVED_CHECKOUT_COUNT` with the rest of the environment.


## Optional remote payer gateway

The same artifact includes a separate loopback-only payer gateway. With the private backend running on 28082, start the gateway in its own terminal using JDK 21:

```powershell
java -Dloader.main=com.lky.experimental.bridge.PayerGateway -cp target/notification-simulator-0.1.0-SNAPSHOT.jar org.springframework.boot.loader.launch.PropertiesLauncher 28083 28082
```

The first port is the gateway; the second is the fixed loopback receiver backend. The gateway process needs no receiver, simulation or Android credentials. It serves only `GET /checkout`, `/checkout.js`, `/demo.css`, `/api/checkout/{lowercase UUID}` and `/api/checkout/{lowercase UUID}/qr`. The last two require the request's payer bearer. All other routes and methods, query strings, encoded paths, extra path segments and redirects are denied. Valid HTTP/1.1 absolute-form targets with matching Host use the same path allowlist; their authority never selects the upstream. The gateway forwards neither incoming proxy headers nor cookies. Receiver administration, issuance/revocation, confirmation, simulation and Android ingestion stay on the private backend.

Public HTTPS ingress requires a separately authorized operator setup pointing **only to the payer gateway**. No public ingress is enabled by building or starting this process. A temporary Cloudflare Quick Tunnel is a low-cost experimental option; its hostname changes and its availability is not guaranteed. Cloudflare terminates TLS and can process bearer headers and checkout/QR content. Tailscale Funnel is an alternative, but enabling it on the private Serve port would make that entire port public. Keep private Android Serve delivery on its existing separate port. Ordinary payer browsers require HTTPS; loopback HTTP is supported for local testing. Forwarded headers never establish authority or transport trust.

When HTTPS ingress uses a cookie-based access gate, payer status and QR fetches include same-origin credentials. The gateway strips cookies before forwarding; the private API still requires the payer bearer. No cross-origin credentials are enabled.

Enter only the controlled HTTPS gateway origin in the private receiver workspace before issuing access. **Copy checkout URL** copies the clean destination; **Copy checkout code** separately copies the private code after an explicit click. The receiver workspace masks the code by default and offers **Show checkout code** for deliberate manual copying when clipboard access is unavailable. Copying deliberately places the code on the device clipboard; keep that clipboard private. The application never reads the clipboard automatically or clears unrelated clipboard contents. The payer enters the code only after provider authentication and arrival at the trusted KaiPay page. Requests and payment/review deadlines are unchanged; expiration of payer access neither extends nor confirms a checkout.

The public gateway serves a code-entry-only checkout, including when tested on loopback: it clears and refuses legacy secret-bearing fragments without authorizing a payer request. Clearing at final arrival cannot repair exposure on an earlier provider page. Revoke any potentially exposed old link and issue a new separate code. The receiver UI never creates fragment links. Historical fragment links remain supported only by the direct private backend checkout, with an explicit warning; keep that backend private and never route those old links through public ingress. Do not infer safety from a private-looking hostname.

The real checkout loads only its own same-origin script/style under strict CSP; do not add third-party scripts, analytics or telemetry. An external script executing in the same document could read a pasted code or JavaScript state. Clean post-authentication entry avoids carrying the secret through ordinary provider navigation, but does not cryptographically authenticate the page or protect against a malicious TLS-terminating provider, compromised origin, browser extension or device. Use only the receiver's approved trusted HTTPS destination; the provider still processes bearer headers on subsequent authorized requests. No token belongs in a URL, access log or browser storage.

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
| `PUT /api/integrations/checkouts/{reference}` | Integration | `{"amountMinor":863,"currency":"MYR"}`; idempotent synthetic create by reference ([integration mode](#synthetic-integration-mode)) |
| `GET /api/integrations/checkouts/{reference}` | Integration | Allowlisted read of that reference only |

Candidate fields are `eventId` (stable random UUID), `notificationId` (random local lifecycle UUID, never Android notification key), `sequence` (integer), `deviceId` (assigned UUID), `source`, `classification`, `classifierProfile`, `amountMinor` (integer MYR cents), `observedAt` (textual UTC ISO instant ending in `Z`) and `evidenceQuality`.

Exactly three reviewed tuples are accepted:

| Source | Classification | Classifier profile | Evidence quality |
| --- | --- | --- | --- |
| `TNG_NOTIFICATION_CANDIDATE` | `INCOMING_CANDIDATE` | `tng.receipt.en.observed.v1` | `RULE_MATCHED` |
| `TNG_NOTIFICATION_CANDIDATE` | `INCOMING_CANDIDATE` | `tng.receipt.kaching.en.observed.v1` | `RULE_MATCHED` |
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

New requests are versioned v2; snapshots retain server admission times, immutable review grants and a payment-closed latch. A latched closed window and terminal states do not reopen after a clock rollback. Absolute timing still trusts the host clock. Existing requests without a lifecycle version retain the original ten-minute behavior and never gain review from historical device timestamps or MATCHED labels. Existing expired/confirmed states and evidence remain historical. Actionable v2 associations are validated on startup and invalid snapshots fail closed. Requests also persist a `revision` counter and, for integration checkouts, their reference binding; startup refuses a reused reference, an integration binding outside lifecycle v2 or one carrying genuine evidence. A process without the integration credential refuses to start while any integration checkout is still open (payment window or review deadline running), because only integration mode keeps genuine candidates away from it. Back up private stores before upgrading: the old binary cannot read new fields/states. Rolling back requires stopping the process and deliberately restoring a pre-upgrade private backup, with loss of later experimental changes; never silently discard new fields or replay history.

Real ordinary-payment validation remains a separate opt-in experiment: observe a fresh notification on the receiver's phone, verify the supported strict classification, inspect the unverified server/browser evidence, and independently check transaction history before receiver confirmation. Synthetic tests do not establish real TNG compatibility or production reliability.
