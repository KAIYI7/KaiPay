# Simulated authorization reconciliation

KaiPay can compare retained simulated authorization evidence with payment records and preserve an immutable report of what it observed. Reconciliation detects and explains differences. It never authorizes, captures, refunds, settles, posts journals, repairs financial records, invokes the simulator, replays messages or activates webhooks.

The scope is `AUTHORIZATION_V1`, ruleset `AUTHORIZATION_V1.0`. The simulator's separately committed operation remains authoritative for its simulated authorization outcome. It shares PostgreSQL with KaiPay, so this is a local simulation with a shared database failure boundary. It is not reconciliation against a bank statement, external clearing record or settlement guarantee.

Capture/refund accounting is not assessed. An approved authorization can remain matched after capture or refund; that result says nothing about the completeness or correctness of its capture/refund journals. Webhook delivery, Kafka acknowledgments, outbox publication, dead-letter messages and external notifications do not establish financial truth.

## Cohort and observation

Each manual run selects one merchant's payments created in `[fromInclusive, cutoffExclusive)`. The creation interval can be at most seven days, with at most 1,000 payments. An oversized cohort rejects the entire run rather than silently truncating it. The cutoff must be at least five minutes before observation.

The comparison uses one current PostgreSQL REPEATABLE READ snapshot. The cutoff selects a creation cohort; it does not reconstruct payment state at a historical time. A provider result committed after cutoff but before the snapshot can be assessed. A payment or result committed after the snapshot can appear in a later run of the same cohort. No closed-period completeness or permanent watermark is promised.

Reports retain the cohort, observation time, ruleset, implementation revision, counts and one observation per selected payment. Reusing the exact run UUID and normalized input returns the stored report without recomputing it. A changed input with the same UUID is rejected. Use a new UUID to make a new assessment. A later result never edits the old report.

An empty completed report means no eligible records were observed. A completed report means the bounded comparison finished; it does not certify that all finances are reconciled.

## Reading the findings

| Finding | Meaning |
| --- | --- |
| `MATCHED` | Retained authorization request/outcome and current payment authorization evidence agree. Capture/refund accounting remains outside assessment. |
| `REQUEST_MISMATCH` | Stored operation amount or currency differs from the same merchant's payment. |
| `AUTHORIZATION_MISMATCH` | A definitive outcome, payment lifecycle, reference or failure evidence conflicts. |
| `INTENT_STATE_CONFLICT` | An operation exists while its payment remains CREATED, contrary to the atomic intent transition. |
| `OWNERSHIP_CONFLICT` | An operation is not bound to the payment's merchant. Its foreign values are omitted. |
| `EVIDENCE_INVALID` | Evidence has an unsupported contract or invalid result shape; no financial loss or cause is inferred. |
| `QUEUED` / `INTENT_PENDING` | Initiation or evaluation has not yet been observed. |
| `UNRESOLVED` | No definitive simulated outcome is persisted. Timeouts and exhausted retries do not establish decline or rejection. |
| `PENDING_FINALIZATION` | A recent definitive simulator result exists while the payment remains PROCESSING. |
| `FINALIZATION_OUTSTANDING` | Definitive result is at least five minutes old and local finalization was not observed. Reconciliation has taken no action. |
| `EVIDENCE_UNAVAILABLE` | Independent evidence is absent for a final/downstream payment. Historical compatibility permits this; absence alone cannot prove deletion. |
| `AMBIGUOUS_PROCESSING` | PROCESSING has no durable operation. Existing recovery deliberately quarantines ambiguous historical activity. |

The five-minute grace is a diagnostic threshold, not a recovery deadline. Clock anomalies produce `TIMING_UNCERTAIN` warnings and suppress age escalation. They do not override an independently observed identity, amount or outcome mismatch. Repeated evaluations, transport deliveries or consumption groups are not duplicate financial authorizations; the operation primary key prevents duplicate operation rows. Repeated event identities are deduplicated for display and conservatively mark the bounded transport context incomplete, including when their payloads conflict. This does not change the financial comparison result.

New financial activity remains MYR, with 100 sen = RM1.00. Historical USD or other retained currency evidence is compared without relabeling, conversion or mixed-currency aggregation. An unresolved historical non-MYR operation cannot be evaluated by reconciliation.

Evidence contains safe stored scalar values and equality/presence predicates. Raw authorization references, free-text messages, customer details, payment tokens, idempotency keys, metadata, URLs, secrets and message payloads are omitted. A report preserves the observed comparison; it is not a raw forensic copy or cryptographic proof against privileged database modification.

## Access and interface

Merchant sessions use their assigned merchant context through `X-Merchant-Id`:

- `GET /v1/reconciliation/runs`
- `GET /v1/reconciliation/runs/{runId}`
- `GET /v1/reconciliation/runs/{runId}/observations`

ADMIN sessions use separate `/v1/admin/reconciliation/runs` routes with the same detail/observations suffixes and an explicit `merchantId` query parameter on every request. There is no unscoped all-tenant default or merchant impersonation.

Lists default to 20 rows, accept sizes 1–100 and pages 0–100, and have fixed ordering. Observation filters accept an allowlisted class or payment UUID; `NON_MATCHED` excludes matched observations. GETs read stored reports and never execute a new comparison. There are no run, repair, resolve, replay or delete HTTP actions.

The Reconciliation page shows the selected cohort, observation time, scope, counts and safe findings. Merchant/session changes clear the displayed context. ADMIN selects one merchant UUID. The page has no execution or repair control; reports are created by an operator outside application startup.

## Operator boundary

The standalone command runs manually using database environment credentials. It does not bootstrap the application, Kafka listeners, scheduled work or external delivery. Provision database access with SELECT on `merchants`, `payments`, `simulated_authorization_operations`, `payment_events_outbox` and `consumed_events`, plus SELECT/INSERT on `reconciliation_runs` and `reconciliation_observations`; financial INSERT/UPDATE/DELETE and reconciliation UPDATE/DELETE permissions are unnecessary. Migrations are a separate release operation requiring the usual database operator authority. Do not put credentials in command arguments or source control.

The entry point is `com.lky.kaipay.reconciliation.core.ReconciliationCli` on the backend runtime classpath. Supply `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` and `RECON_BUILD_REVISION` in the operator environment. The build revision is a trusted release identifier, not automatically attested by the command. `RECON_CONSUMER_GROUP` defaults to `kaipay-payment-processor-group`; it provides diagnostic marker context rather than proof of the original group.

```text
ReconciliationCli <runUUID> <merchantUUID> AUTHORIZATION_V1 AUTHORIZATION_V1.0 <fromInclusive> <cutoffExclusive> <operatorAlias> <reason>
```

Use UTC timestamps at microsecond precision. Operator aliases allow 1–100 letters, digits, underscores or hyphens. Reasons are `DEMO_REVIEW`, `INCIDENT_REVIEW` or `VERIFICATION`. The asserted alias attributes the DB operator's command; it does not authenticate a human identity. A report replay returns its stored build/group context even if the current environment changed. Use a new run UUID for reassessment under a new supported ruleset or diagnostic group.

Runs use an atomic transaction for the completed report and all observations. Failure before commit leaves no partial report. If commit acknowledgment is lost, retry the same run UUID and exact input to discover the committed result safely. No background retry/recovery service is installed.

Initial limits are a 15-second execution deadline, two-second statement timeout and 500ms lock timeout. These are bounded demo controls, not performance, throughput or settlement guarantees. Operators must choose smaller creation intervals if a cohort exceeds limits. Reports are not purged automatically; retention and restricted database access are operator responsibilities.

Late activity requires a new explicit run of the relevant old cohort. Reconciliation cannot guarantee finalization after dead-letter routing, lost broker retention, source deletion or database corruption. Disabling the command or hiding its views leaves financial behavior unchanged and preserves report history.
