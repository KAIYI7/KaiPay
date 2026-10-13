# Experimental observation simulator

This standalone Java 21 / Spring Boot 3.4.3 process demonstrates a **synthetic** incoming-observation experience for KaiPay. It has no dependency on KaiPay's payment backend, PostgreSQL, Kafka, refund services, authorization transitions, or ledger. It is not a TNG integration or proof of settlement. Physical-device TNG notification compatibility is **UNVERIFIED**. Android probe networking and live event ingestion are deferred.

The feature is disabled by default, including the demo page and all APIs. Its default bind address is `127.0.0.1:28082`. Do not deploy this process or expose it with a tunnel, proxy, port forward, or public server. Both credentials below are newly generated experimental credentials; never reuse wallet, bank, or production credentials.

## Build and run locally

Use JDK 21 and Maven. From this directory:

```powershell
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-21'
$env:PATH = "$env:JAVA_HOME/bin;$env:PATH"
mvn -o verify
```

Offline verification requires the Maven dependencies to be cached. If they are absent, obtain them with `mvn verify` using your approved dependency network access. The tests include a real HTTP server on loopback and atomic file replacement; a Windows application sandbox may prohibit these operations. Run verification in a terminal permitted to make local loopback connections and atomic writes. Tests are never replaced with weaker persistence or mocked HTTP to bypass those limitations.

Generate two separate random tokens in a local PowerShell session without printing them into command history or logs:

```powershell
function New-ExperimentalToken {
    $bytes = New-Object byte[] 32
    $generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $generator.GetBytes($bytes) } finally { $generator.Dispose() }
    [BitConverter]::ToString($bytes).Replace('-', '')
}
$env:BRIDGE_RECEIVER_TOKEN = New-ExperimentalToken
$env:BRIDGE_DEVICE_TOKEN = New-ExperimentalToken
$env:BRIDGE_ENABLED = 'true'
java -jar target/notification-simulator-0.1.0-SNAPSHOT.jar
```

The server requires distinct tokens containing 32–256 ASCII letters, digits, underscores, or hyphens. Missing, short, malformed, or identical tokens prevent enabled startup. Configuration never supplies a built-in token. Use a secure local mechanism to copy your session's two values into the demo; do not save them in source files, screenshots, or shared logs. The device credential is a simulation ingestion credential in this batch, not an Android connection.

Open `http://127.0.0.1:28082/`. On exit, stop the process and remove the environment values:

```powershell
Remove-Item Env:BRIDGE_ENABLED, Env:BRIDGE_RECEIVER_TOKEN, Env:BRIDGE_DEVICE_TOKEN
```

No receiver endpoint is started within the existing KaiPay backend. Starting this separate process does not require Docker or change database schemas. The simulator binds loopback unless explicitly overridden. Any non-loopback bind requires configured server TLS; per-request transport checks require actual HTTPS outside loopback. Forwarded headers must remain disabled and never establish transport trust. Remote operation is outside this batch's delivery scope.

## Synthetic demonstration

1. Enter the receiver token and simulation device token in their separate password fields. The page keeps credentials only in memory. Clear them with the supplied control or leave the page.
2. Use the default synthetic `12.34` amount. The page displays a placeholder QR that contains no payment code. Click **Create synthetic request**.
3. Click **Simulate candidate**. The request becomes **Payment detected — awaiting verification**. Detection does not confirm payment.
4. Use **Refresh / start polling** to poll authenticated status every three seconds. Polling never resubmits observation events.
5. As receiver, select **Begin manual verification**, acknowledge the independent verification requirement, and then use **Confirm after independent history check**. These separate receiver-authenticated actions move the synthetic request through `AWAITING_VERIFICATION` to `MANUALLY_CONFIRMED`.
6. To demonstrate ambiguity, create two new requests with identical amounts and simulate a candidate. Both remain `AMBIGUOUS`, with no confirmation action. Requests expire after ten minutes. Unmatched and expired observations remain represented separately.

All browser-rendered dynamic values use text nodes, with a restrictive content-security policy. No notification text, personal QR, wallet identifier, sender identity, or credential is accepted or persisted. The status page is authenticated and never exposes real accounts.

## API and safety contract

Use `Authorization: Bearer <local token>`; tokens are never query parameters or cookies. No cross-origin access is configured.

| Route | Credential | Input / behavior |
| --- | --- | --- |
| `GET /api/state` | Receiver | Minimal synthetic requests and observation dispositions |
| `POST /api/requests` | Receiver | `{"amountMinor":1234}`; integer MYR cents, range 1–1,000,000 |
| `POST /api/observations` | Simulation device | Candidate schema below; never performs manual confirmation |
| `POST /api/requests/{id}/verify` | Receiver | `{"historyChecked":true}`; detected request only |
| `POST /api/requests/{id}/confirm` | Receiver | `{"historyChecked":true}`; awaiting-verification request only |

Candidate fields are `eventId` and `notificationId` (new random UUIDs for synthetic data), `sequence` (integer 1–1,000,000), `observedAt` (UTC ISO instant), `amountMinor` (integer cents), and `source` (**exactly** `"SIMULATED"`). No text fields are supported. Unknown fields, duplicate JSON keys, trailing JSON, numeric enums, string or decimal amounts, and bodies over 4096 bytes are rejected. Error bodies and application logs do not contain rejected payloads or tokens.

New events must be no more than 120 seconds old and no more than 30 seconds in the future. A candidate predating a request cannot match it. Exact retries with the same event UUID and identical payload return their existing result, including after restart or when acknowledgement was lost; reusing an event UUID with altered fields is rejected. This does not assume exactly-once transport delivery. The browser does not implement background ingestion retries; local network errors are shown to the operator. A future bridge must retain the same event identity on retry.

A notification UUID identifies one notification lifecycle. Increasing-sequence updates are recorded as `UPDATE_IGNORED`; they cannot match a second request and invalidate an unconfirmed match to `AMBIGUOUS`. Lower/repeated sequences and backwards notification timestamps are rejected. Amount/time matching supplies only a candidate and never proves payment. Ambiguity cannot become eligible for confirmation because one competing request expires. Expired, unmatched, or ambiguous candidates cannot be manually confirmed through these APIs.

## Local persistence and limitations

The default `.local/observations.json` snapshot contains only the synthetic model. Keep `.local/` ignored and private. Credentials are never stored there. One process owns a sibling lock file. Each mutation writes and forces a temporary file, then atomically replaces the snapshot before exposing success. Unsupported atomic replacement or write failure returns `503 STORE_UNAVAILABLE` and preserves in-memory state and replay eligibility. This is a process-restart prototype; it is not a database and does not claim power-loss durability, a distributed lock, multiple-node operation, or disaster recovery. Local files require normal operating-system access protection.

Storage is bounded at 500 requests, 1000 observations, and two million snapshot bytes. The process refuses additional growth instead of evicting replay history. Stop the process and deliberately reset the private synthetic store to start a new experiment; reset removes duplicate history and is never automated. Do not reuse previous event UUIDs after a reset.

There is one configured receiver role for this experiment. Its bearer token grants manual confirmation authority; a future multi-receiver, multi-tenant design needs separate review. Verification is the receiver's attestation after checking independently; the prototype cannot authenticate TNG history. A spoofed Android notification, matching amount, or notification text would still not establish settlement. This batch accepts simulations only and never posts Android events here.

Before any live batch: obtain physical-device evidence of actual Android system notification fields, distinguish incoming transfers from unrelated events without storing personal samples in Git, review package/signature and spoofing limitations, design authenticated HTTPS device onboarding and persistent retries, and independently review all live matching and confirmation changes. Keep live events disconnected from financial state.
