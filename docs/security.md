# Demo authentication and access

KaiPay processes simulated payments. It does not move real money or integrate a real payment provider. Accounts are individually provisioned by an operator; there is no public registration or shared administrator password.

The backend uses Spring Security and server-side sessions. Passwords are stored as encoded hashes. Sessions use HttpOnly, Secure, SameSite=Strict cookies by default and a configurable 20-minute idle timeout. Sessions live in one backend instance's memory: restarting it logs users out. Shared session storage and multi-instance continuity are not implemented.

`MERCHANT` accounts can request only active merchants assigned to them in the database. `X-Merchant-Id` selects requested context; the server validates membership before accessing payments, refunds, balances or journals. Foreign payment and journal IDs do not grant access. Merchant journal views retain the balancing entries of their own transactions; global and system-only journals are unavailable to merchants.

`ADMIN` is a separately provisioned operator identity. It may read global journal diagnostics, outbox and dead-letter events. It cannot create, capture or refund payments, access merchant balances, or use a merchant header to impersonate a merchant. Unknown API routes and methods are denied by default. No public diagnostic endpoint is exposed.

Browser authentication uses the following same-origin API sequence:

1. `GET /v1/auth/csrf` obtains a session-bound masked CSRF token and its header name.
2. `POST /v1/auth/login` submits URL-encoded `username` and `password`, with the CSRF header. Successful login returns 204 and changes the session identifier.
3. Fetch a fresh CSRF token, then `GET /v1/auth/me` for the authenticated identity, role and permitted merchant memberships.
4. Send the current CSRF header with state-changing requests, including `POST /v1/auth/logout`. Logout invalidates the session; fetch a new token before another login.

Authentication failures return sanitized 401 responses. Authenticated permission failures return 403; foreign resources within a valid merchant context return 404. The React application holds the CSRF token in memory, uses cookies for authentication, and creates a separate query cache for each authenticated lifetime. No password, authentication token or session secret is stored in localStorage. Frontend route restrictions improve usability; backend authorization enforces access.

Same-origin tabs send generic authentication-transition notifications to clear private caches and revalidate the shared cookie. Focus and visibility checks hide old private views before checking the current server identity. These notifications contain no credentials or session identifiers. Browsers without BroadcastChannel rely on focus/visibility checks; an interrupted transition in another tab may require a reload. This does not provide a distributed lock for simultaneous tab logins.

V7 adds users and merchant memberships to the existing V1–V6 schema. It does not seed login accounts or reinterpret the synthetic merchant API-key fixture fields as credentials. Existing header-only scripts must adopt session login and CSRF. Payment/refund idempotency and ledger/outbox processing contracts remain unchanged.

Public hosting requires a separately approved deployment: HTTPS and trusted reverse-proxy configuration, private database/broker access, individual admin access protected by a perimeter and MFA, login throttling, demo quotas and synthetic data retention/reset policy, logging/privacy review, dependency review and rollback rehearsal. Use the explicit local profile only for loopback HTTP development. This foundation does not implement public registration, guest sandboxes, an MFA provider, durable simulator recovery, or a deployment platform.
