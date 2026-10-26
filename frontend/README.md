# KaiPay frontend

From this directory:

```sh
npm ci
npm run dev
npm test
npm run build
```

The development server proxies `/v1` to the local backend on port 28080. Use the
backend's explicit `local` profile for HTTP development; the default server cookie
configuration requires HTTPS. Accounts are individually provisioned by the operator.
There is no public registration or shared default account.

The browser bootstraps an existing server session with `/v1/auth/csrf` and
`/v1/auth/me`. Login uses a CSRF-protected form POST. After login and logout it
refreshes the masked CSRF token. Cookies are sent on same-origin requests; no
password, authentication token, session secret or CSRF token is stored in localStorage.

Only memberships returned by the server appear in merchant selection. Every merchant
API call explicitly captures its requested merchant header. The backend verifies that
context and resource ownership. Administrator operational pages use no merchant
header and do not impersonate a merchant. Frontend route checks support usability;
backend authorization remains the security boundary.

Dependency security assessment (27 October 2026): the locked tree includes the
Vite 6.4.4, Vitest 4.1.12 and selector-parser 6.1.5 security patches. Development
and test servers should remain local; the production build contains static assets.
Run `npm audit` and `npm audit --omit=dev` from this directory to recheck current
advisories. Audit results depend on the registry's current advisory data.

Two moderate audit entries remain for React Router 6.30.6 and its DOM wrapper,
covering [attacker-controlled navigation destinations](https://github.com/advisories/GHSA-wrjc-x8rr-h8h6)
and [SSR error hydration](https://github.com/advisories/GHSA-337j-9hxr-rhxg).
The current app uses declarative `BrowserRouter` with client rendering, no SSR
hydration, and internal navigation destinations built from fixed paths and server
resource IDs. No affected application path was identified in this assessment.
The published fixes start at Router 7.18.0; reassess before introducing untrusted
navigation destinations or SSR, or as part of a separately tested Router migration.

Payment/refund cache keys include merchant and resource identities. A separate query
client is allocated for each authenticated lifetime, even when accounts share a
merchant. Logout, expiry and identity changes clear cached private data, cancel reads
and fence late responses/mutation completions from earlier sessions. Same-origin
tabs exchange only transition-begin/completion notifications through
BroadcastChannel, clearing old private views before refreshing the server identity.
The channel contains no identity, credential or token data. On browser focus or
visibility restoration, private views are hidden and caches fenced immediately
before CSRF/session revalidation; failed checks leave private data cleared. Where
BroadcastChannel is unavailable, focus/visibility revalidation still applies. A
remote transition interrupted before completion requires a reload to reinitialize.
There is no cross-tab distributed lock; the server remains the authority.
The client does not replay saved credentials or renew sessions with a timer. A failed server logout is reported explicitly and still
clears private client views. Reload and retry logout before sharing the browser if
server invalidation could not be confirmed.

The sandbox's known seed customers are synthetic input hints for assigned merchants.
Other provisioned merchants can enter their synthetic customer UUID. The backend
validates ownership. Payments remain simulated.

Component regressions exercise the real session/merchant contexts and query clients
with mock HTTP responses, including the CSRF request sequence, session transitions,
forbidden/unauthenticated errors, role routes and delayed cross-user requests. They
verify frontend behavior; real session cookies, authentication, CSRF enforcement,
merchant isolation and payment processing also require backend/browser integration
checks.
