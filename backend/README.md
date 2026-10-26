# Backend authentication and local operation

KaiPay processes simulated payments. Browser API calls require individually provisioned accounts and server-side sessions. There is no public signup, shared default password, anonymous payment access, or administrator impersonation of merchants.

Use JDK 21. The committed Maven wrapper supplies Maven 3.9.16; a separate Maven installation is optional. Confirm its reported Java runtime is 21, setting `JAVA_HOME` if needed. Docker must be available for the PostgreSQL/Kafka integration tests. From `backend/` on Windows PowerShell:

```powershell
.\mvnw.cmd -version
.\mvnw.cmd verify
```

Run commands in `backend/`. Maven verification packages `target/KaiPay-0.0.1-SNAPSHOT.jar`. The current migration inventory is V1–V11; V7 introduces identities required for account provisioning, V8 durable simulator operations, V9 MYR defaults, V10 webhooks and V11 authorization reports. Starting the configured application applies pending migrations, so use a fresh disposable database for a demonstration and review compatibility before upgrading retained data. Migrations and application startup create no user accounts.

On Bash use `sh ./mvnw -version` and `sh ./mvnw verify`. For setup without the regression suite, `clean package -DskipTests` also builds the executable JAR; `spring-boot:run` alone does not.

## Configuration

Outside local development, inject `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, and `KAFKA_BOOTSTRAP_SERVERS` through the environment. Missing database/broker configuration prevents application startup. Never put deployment secrets in Git, command arguments, build artifacts, or logs.

The root `.env.example` is a reference, not an automatically loaded Spring configuration file. Neither Java/Maven nor the account CLI sources `.env`; Compose's own `.env` handling does not export variables into your terminal. Set variables separately in every terminal that needs them. No ignored secret file is required for the shipped local profile.

The default session cookie is Secure, HttpOnly, host-only, and SameSite=Strict. Idle expiry defaults to 20 minutes; `SESSION_IDLE_TIMEOUT` can change it to a positive duration of at least one minute. Only cookie session tracking is supported. Sessions are in memory: restarting the backend logs everyone out; there is no multi-instance session guarantee. Account disablement, deletion, password rotation, or role changes invalidate existing sessions on their next request. Merchant memberships and active merchant status are checked for each business operation.

For local HTTP development only, explicitly enable the `local` profile:

```powershell
$env:SPRING_PROFILES_ACTIVE = 'local'
java -jar target/KaiPay-0.0.1-SNAPSHOT.jar
```

This profile binds to loopback, permits an HTTP session cookie, and supplies development database/broker connection defaults. Authentication and CSRF remain mandatory. The application rejects a non-loopback local bind, insecure cookies outside local development, or mixing the local profile with deployment profiles. Do not deploy the local profile.

Prefer frontend and API on one HTTPS origin. `CORS_ALLOWED_ORIGINS`, when needed, is a comma-separated list of exact HTTPS origins without paths, wildcard hosts, credentials, or query strings. Its default is empty. The local profile allows the Vite origins `http://localhost:28081` and `http://127.0.0.1:28081`. A trusted reverse proxy, HTTPS forwarding configuration, infrastructure access controls and other hosting prerequisites require a separate deployment review; see [security guidance](../docs/security.md).

## Operator provisioning

Run the packaged account CLI from a trusted interactive terminal with `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD` already supplied in the environment. The CLI starts no application server or workers. The operator needs database permission to manage identities and memberships. Password entry and confirmation are hidden; passwords are never accepted in command arguments. Choose an individual password of at least 12 characters and at most 72 UTF-8 bytes, without NUL characters. Only BCrypt hashes with cost 12 are stored. Login enforces the same byte and NUL restrictions; unsupported inputs cannot authenticate by matching a truncated or terminated password prefix.

First start the backend against your fresh database and wait for migration/startup completion. In a second interactive terminal, change to `backend/`. For the unmodified local Compose database, set the following in PowerShell 7 (enter the database password configured in your Compose file, not a browser account password):

```powershell
$env:DB_URL = 'jdbc:postgresql://localhost:25432/kaipay'
$env:DB_USERNAME = 'kaipay'
$env:DB_PASSWORD = Read-Host 'Local Compose database password' -MaskInput
```

Or on Bash:

```bash
export DB_URL=jdbc:postgresql://localhost:25432/kaipay
export DB_USERNAME=kaipay
read -r -s -p 'Local Compose database password: ' DB_PASSWORD
export DB_PASSWORD
```

Use your actual connection values if you changed Compose. The CLI deliberately requires explicit database variables even when the server uses the local profile. Do not pipe passwords or run it from an IDE/output-only console: it requires an interactive terminal for hidden account-password entry. Close the provisioning terminal afterwards to discard its environment credentials.

V2 seeds these active synthetic examples on a new database; no API key hash or legacy `webhook_url` in that migration is a browser credential or an enrolled webhook endpoint:

| Merchant | Merchant UUID | Owned customer UUID |
| --- | --- | --- |
| Acme E-Commerce Corp | `11111111-1111-1111-1111-111111111111` | `22222222-2222-2222-2222-222222222221` |
| Global Retailers Ltd | `33333333-3333-3333-3333-333333333333` | `44444444-4444-4444-4444-444444444441` |

Replace the example username and merchant UUID below with the intended individual account and an existing active synthetic merchant. Keep the quoted JVM property intact in PowerShell:

```powershell
java "-Dloader.main=com.lky.kaipay.security.DemoAccountCli" -cp target/KaiPay-0.0.1-SNAPSHOT.jar org.springframework.boot.loader.launch.PropertiesLauncher provision demo.alice MERCHANT 11111111-1111-1111-1111-111111111111
```

Append additional merchant UUIDs for explicitly assigned memberships. Invalid or inactive merchants, duplicate accounts/memberships, and database errors roll back the entire provisioning operation. Administrators must have no merchant memberships:

```powershell
java "-Dloader.main=com.lky.kaipay.security.DemoAccountCli" -cp target/KaiPay-0.0.1-SNAPSHOT.jar org.springframework.boot.loader.launch.PropertiesLauncher provision operator.alice ADMIN
java "-Dloader.main=com.lky.kaipay.security.DemoAccountCli" -cp target/KaiPay-0.0.1-SNAPSHOT.jar org.springframework.boot.loader.launch.PropertiesLauncher rotate demo.alice
java "-Dloader.main=com.lky.kaipay.security.DemoAccountCli" -cp target/KaiPay-0.0.1-SNAPSHOT.jar org.springframework.boot.loader.launch.PropertiesLauncher disable demo.alice
```

Rotation replaces the hash and increments the credential version. Disablement also increments that version. Rotation does not re-enable a disabled account. Never share administrator credentials or expose this provisioning capability through an HTTP endpoint.

## Browser contract

1. GET `/v1/auth/csrf` with session credentials. Its `data` contains the masked `token` and `headerName` (`X-CSRF-TOKEN`). Keep the token in memory.
2. POST `/v1/auth/login` using `application/x-www-form-urlencoded` fields `username` and `password`, the returned CSRF header, and session credentials. Successful login returns 204 and changes the session ID.
3. Fetch a fresh CSRF token after login. GET `/v1/auth/me` returns `data: {id, username, roles, merchants: [{id, name}]}`. Only server-provided merchant memberships may be selected.
4. Business calls send the selected `X-Merchant-Id` as requested context. Every mutation also sends the current CSRF header. The server independently checks membership and resource ownership before financial work or idempotent replies.
5. POST `/v1/auth/logout` with CSRF and session credentials. It returns 204 and invalidates the session. Clear identity-scoped frontend state and fetch a new CSRF token before a subsequent login.

401 means authentication is absent, expired, revoked, or invalid. 403 means an authenticated account lacks permission or its mutation has invalid/missing CSRF. Owned-resource lookup failures return 404 without granting access to another merchant. Default-deny rules protect unclassified routes and methods; `/login`, health/diagnostic endpoints and unknown APIs are not public.

MERCHANT accounts can use only assigned active merchants' payment, capture, refund, balance and journal resources. Null/global journals and foreign-account lines are denied. System balancing entries inside an owned journal retain the existing journal response contract. ADMIN accounts can read DLT/outbox diagnostics and global journals, with no `X-Merchant-Id` on journal requests; they cannot perform merchant business operations.

The HTTP regression suite provisions persisted synthetic identities and performs actual session/CSRF exchanges. No test or development profile bypasses application authentication.
