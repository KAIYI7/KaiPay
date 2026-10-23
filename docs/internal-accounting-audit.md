# Internal accounting consistency audit

KaiPay provides a manual, read-only report of **internal structural agreement among retained records**. It covers committed capture states, completed refunds, journals and ledger entries for one explicit merchant. It is separate from `AUTHORIZATION_V1` simulated authorization reconciliation. Neither this report nor matching internal records establish provider acceptance, bank reconciliation, clearing or settlement.

The report detects relationships that row-level constraints and posting-time validation do not reassess in retained data: missing or multiple source journals, missing source records, ownership conflicts, empty or unbalanced journals, invalid entry/account roles, inconsistent currency, incorrect source gross amounts and completed-refund totals or state conflicts. It takes no financial action, creates no accounts, posts no journals and changes no source or report tables.

## Small operator boundary

This is a transient standalone command and one SQL report, not a new persistent audit subsystem. No migration, scheduler, HTTP endpoint or UI is added. Existing authorization reports and their merchant/ADMIN access remain unchanged. Operators can retain the safe JSON output locally according to their own access and retention policy; running again performs a new observation, not immutable replay.

Database credentials belong only to a trusted operator. Explicit merchant selection limits the report output; it is not merchant authentication, row-level database security or permission to give database credentials to merchants. The command never bootstraps Spring, Kafka, financial services or webhook dispatch. Application merchant and ADMIN sessions cannot invoke it through HTTP.

Use a separately provisioned database login with `CONNECT`, schema `USAGE`, and `SELECT` on only `merchants`, `payments`, `refunds`, `journals`, `ledger_entries` and `accounts`. No financial write, report-table access, migration, superuser or role-management privilege is needed. Keep `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` and `AUDIT_BUILD_REVISION` in the trusted operator environment. Credentials must not appear in command arguments, source control or report output. The build revision is an operator-supplied release label, not an attestation.

The entry point on the backend runtime classpath is:

```text
com.lky.kaipay.ledger.audit.InternalAccountingAuditCli <merchantUUID> <fromInclusive> <cutoffExclusive>
```

For the packaged application, select the standalone main without starting the application:

```text
java -Dloader.main=com.lky.kaipay.ledger.audit.InternalAccountingAuditCli -cp backend/target/KaiPay-0.0.1-SNAPSHOT.jar org.springframework.boot.loader.launch.PropertiesLauncher <merchantUUID> <fromInclusive> <cutoffExclusive>
```

Use UTC timestamps at microsecond precision, an increasing interval of at most seven days, and a cutoff no later than observation. Unlike authorization reconciliation, there is no five-minute finalization grace: capture and completed-refund posting are committed atomically with their financial records. A fresh connection is required. The command enforces a PostgreSQL read-only REPEATABLE READ transaction even if an operator accidentally supplies a more privileged login.

Successful execution writes one complete JSON report and exits zero, including when it finds inconsistencies. Invalid, unavailable, oversized, interrupted or timed-out execution exits one with a fixed safe error and no partial report. Inspect `hasInconsistencies`, `hasUnassessedRecords`, classification counts and all observations; exit zero and `status: COMPLETE` do not certify that accounting is correct.

## Bounded current observation

The root interval selects three independent sets: merchant payments and refunds by creation time, and merchant journals by posting time, each in `[fromInclusive, cutoffExclusive)`. Their defined, nonrecursive closure also includes:

- Owned source payments and refund parents referenced by root refunds/journals.
- All owned refunds of the selected payments, plus owned refund sources of root journals.
- All owned capture/refund journals associated with the selected payment/refund identities.
- Entries of those journals and their account relationships.

Linked rows may be older or newer than the root interval. This is one **current** snapshot, not historical reconstruction, a closed-period statement or a durable watermark. Entirely unrelated or orphaned rows outside these roots/relationships are outside coverage. Sources are compared using the canonical UUID text written by KaiPay (`UUID.toString()`); malformed or noncanonical source text has no matching source and is reported without echoing that text. Unreferenced accounts are not audited.

Final caps are 1,000 payments, 1,000 refunds, 1,000 journals and 10,000 entries after expansion. Every set is checked at its cap plus one. Any overflow rejects the whole report rather than silently truncating it. Queries have a two-second statement timeout, 500ms lock timeout and a cumulative 15-second acquisition/execution deadline with connection abort. JSON output is capped at 4MiB. These are operational controls, not throughput guarantees; use smaller root intervals when necessary. Operator output I/O and retention remain the operator's responsibility.

## Interpreting observations

| Classification | Meaning |
| --- | --- |
| `CONSISTENT` | The checks for this particular source or journal agreed. Inspect related observations too: a payment relationship can agree while its journal is unbalanced. |
| `INCONSISTENT` | At least one supported structural relationship conflicts. Allowlisted reason codes describe the observed conflict; the audit has not diagnosed loss, cause or repair. |
| `NOT_ASSESSED` | A pending or unsupported state/type prevents a complete source-policy assessment. Independent structural defects can still be reported. |
| `NOT_APPLICABLE` | A known precapture payment has no capture or completed-refund accounting to assess. |

The report compares each journal's debits and credits **per currency**, checks the source gross on the customer-receivable side, and validates the expected account role, direction, ownership and currency. Capture credits may be split between merchant liability and platform revenue; refunds reverse those roles. Legitimately omitted zero lines are accepted. Numeric sums use PostgreSQL `numeric`, so large retained amounts are not compared through overflowing Java `long` totals. Amounts are emitted as exact decimal minor-unit strings, preserving their original currency; non-MYR history is not relabelled or converted, and currencies are never summed together.

Fee allocation is deliberately outside assessment. The existing capture percentage plus capped fixed fee and proportional refund policy remain unchanged, including retention of the fixed fee on a full refund. A balanced but incorrectly allocated fee split may therefore pass these structural checks. Multiple lines or splits among permitted roles that preserve the assessed totals are not a fee-policy violation here. The report does not assert that the current fee formula was the policy for every historical row.

Completed refunds are compared with their own payment's currency and retained refund state. `REFUND_PENDING`, noncompleted refunds and unknown source types are not invented proof of completed money movement. Foreign journal/refund/account/source associations are Boolean conflicts. Foreign identifiers, amounts, statuses and currencies are omitted rather than copied into another merchant's report. No descriptions, account names or numbers, customer data, refund reasons, idempotency keys, metadata, gateway references, event payloads or credentials appear in evidence.

This diagnostic shares the same database trust boundary as the financial sources. It cannot detect records absent from every retained source, prove completeness beyond its selected closure, prevent privileged database tampering, or detect both sides being identically altered. It does not validate outbox retention/event provenance, exact original commit history, cash movements, account solvency or bank balances. Findings require human investigation under separately authorized procedures; there is no repair, replay or financial action control.
