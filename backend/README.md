# Backend authentication and local operation

KaiPay processes simulated payments. Browser API calls require individually provisioned accounts and server-side sessions. There is no public signup, shared default password, anonymous payment access, or administrator impersonation of merchants.

Use JDK 21 and Maven. Docker must be available for the PostgreSQL/Kafka integration tests:

```powershell
mvn verify
```

Run commands in `backend/`. Maven verification packages `target/KaiPay-0.0.1-SNAPSHOT.jar`. The database must have the additive Flyway V7 identity migration before account provisioning; starting the configured application applies migrations. Existing V1–V6 migrations and financial data are preserved. Migrations and application startup create no user accounts.

## Configuration

Outside local development, inject `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, and `KAFKA_BOOTSTRAP_SERVERS` through the environment. Missing database/broker configuration prevents application startup. Never put deployment secrets in Git, command arguments, build artifacts, or logs.

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

Replace the example username and merchant UUID below with the intended individual account and an existing active synthetic merchant:

```powershell
java -Dloader.main=com.lky.kaipay.security.DemoAccountCli -cp target/KaiPay-0.0.1-SNAPSHOT.jar org.springframework.boot.loader.launch.PropertiesLauncher provision demo.alice MERCHANT 11111111-1111-1111-1111-111111111111
```

Append additional merchant UUIDs for explicitly assigned memberships. Invalid or inactive merchants, duplicate accounts/memberships, and database errors roll back the entire provisioning operation. Administrators must have no merchant memberships:

```powershell
java -Dloader.main=com.lky.kaipay.security.DemoAccountCli -cp target/KaiPay-0.0.1-SNAPSHOT.jar org.springframework.boot.loader.launch.PropertiesLauncher provision operator.alice ADMIN
java -Dloader.main=com.lky.kaipay.security.DemoAccountCli -cp target/KaiPay-0.0.1-SNAPSHOT.jar org.springframework.boot.loader.launch.PropertiesLauncher rotate demo.alice
java -Dloader.main=com.lky.kaipay.security.DemoAccountCli -cp target/KaiPay-0.0.1-SNAPSHOT.jar org.springframework.boot.loader.launch.PropertiesLauncher disable demo.alice
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
