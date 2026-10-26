# Supervised local operations

The experimental receiver is separate from KaiPay's financial backend. Notification evidence remains tentative. A receiver must independently check genuine wallet history, explicitly verify and then confirm the selected evidence. Health, delivery acknowledgement and synthetic confirmation never establish receipt of real funds, settlement or a refund.

## Private synthetic receiver on Windows

Use PowerShell7.4+, JDK21 and a verified experimental-module JAR. No Docker, production backend, payer gateway, public connector or Android device is needed. Run from `experimental-bridge/`:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
mvn -o -B verify
# Private disposable credentials; these commands do not print their values.
$env:BRIDGE_RECEIVER_TOKEN = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))
$env:BRIDGE_DEVICE_TOKEN = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))
./ops.ps1 preflight
./ops.ps1 start
./ops.ps1 status
./ops.ps1 stop
# Retire session credentials after stopping.
$env:BRIDGE_RECEIVER_TOKEN = $null
$env:BRIDGE_DEVICE_TOKEN = $null
```

Keep the same private credentials for status/receiver use during that process's lifetime. Enter the receiver token only in the protected local receiver form. Never put credentials in URLs, command arguments, source, screenshots or reports. Missing/wrong status credentials mean UNKNOWN/HTTP401, not proof of an offline backend. Do not change execution policy to run these commands; resolve a policy refusal with the operator.

`preflight` checks JDK21 with a five-second timeout, built JAR, distinct credentials and an unoccupied port. `start` allows30seconds for readiness, binds exactly `127.0.0.1`, forces live reporting/real QR off and starts no payer gateway. Inherited JVM/application/config/logging switches are removed. Choose another port with `-Port` at start/preflight; status/stop use the recorded port. Credentialed probes require the verified owned child and matching loopback listener.

Operations are serialized by a private file lock. Process ownership checks include creation time, executable, unique JVM marker and JAR path. Stop kills only that verified child, waits at most10seconds and preserves durable synthetic state. Changed PID/command identity is refused; inspect the record instead of killing arbitrary Java/PowerShell processes. A valid record whose child is absent can be retired with stop. Corrupt records fail closed. Start never adopts/stops an existing listener.

Ignored checkout `.local/supervised-ops/` holds the bounded ownership record, snapshot/lock and one stdout/stderr pair overwritten on each start. Credentials are not persisted. Keep it private; inspect unexpected log growth during supervision. This is not an indefinite monitoring archive. Stop before snapshot backup. Deleting/resetting replay history removes duplicate protection; never reuse old event/lifecycle UUIDs afterward. Stop can interrupt an in-flight request: uncertain acknowledgement requires retry of the identical durable event.

This helper supports synthetic receiver operation only. Separately provisioned private live-delivery operation retains the existing manual configuration; this helper does not activate it. Do not point a live phone at the synthetic helper. Public activation and physical settings changes require separate authorization.

## Interpret dimensions separately

| Signal | Evidence and limit |
| --- | --- |
| Process RUNNING | Status rendered in this process; no background survival guarantee. |
| Notification access | Current permission, independent of service binding. |
| Listener CONNECTED | Connection callback in this process; last state-callback timestamp. No heartbeat or continuous receipt guarantee. |
| Listener DISCONNECTED | Disconnect/destroy callback, or current access unavailable. |
| Listener UNKNOWN | No state callback in this process. Restarted process/worker/empty queue cannot replace it. |
| Capture/delivery controls | Separate owner opt-ins; delivery off does not hide listener uncertainty. |
| Queue count/oldest pending age | Persisted minimized events; age follows event time, backwards clock gives UNKNOWN. Empty queue can mean absent capture. |
| Delivery worker | Actual scoped WorkManager sample: RUNNING, ENQUEUED, RETRY_ENQUEUED, BLOCKED_DEPENDENCY. ENQUEUED does not prove constraints met or timely execution. |
| Recovery worker | Separate work sample; completed reconciliation/rebind request is not listener recovery. |
| Worker freshness | Query at most every5seconds while status is read, one in flight; hung query abandoned after10seconds, samples stale after15seconds. Old/missing samples UNKNOWN. |
| Last delivery ACK | Persisted after validated authorized event acknowledgement. Missing, future or older than5minutes UNKNOWN. Recent communication is not current reachability or payment verification. |
| Backend probe | Receiver-only `GET /api/health`, fresh UTC sample/no-store. This process responded now; phone listener/queue/worker/notification transport UNKNOWN. No financial-state read or notification/wallet content. Gateway denies it. |
| Financial state | PAYMENT_DETECTED tentative; AWAITING_VERIFICATION requires receiver action; MANUALLY_CONFIRMED another selected-evidence confirmation. No automatic settlement/refund/ledger entry. |

The existing protected Android screen refreshes only in foreground; pause hides diagnostics/stops UI polling. Health display uploads no notification text or new health payload.

## Startup, outage recovery and shutdown

1. Check operating mode, JDK/JAR and private credentials. Preflight, start receiver, inspect status and verify private receiver access. Keep public connectors stopped. Later device operation requires its own authorization.
2. In a separately authorized supervised phone session, inspect permission, callback state/timestamp, capture/delivery controls, pending queue and worker freshness independently. A rebind request, running worker or backend response cannot prove new notifications can arrive.
3. Backend outage: restore the same private backend/store/configuration before considering manual retry. Pending same-UUID events retain bounded backoff; Android may defer work. Do not clear/recreate the queue, generate replacement events or confirm because transport recovered. Inspect FAILED/EXPIRED rows and local diagnostics; explicit retry applies only where the app permits it.
4. Evidence expires after24hours. Delayed admission or a checkout/review cutoff can prevent association after connectivity returns. Retry never reopens a window. Independently verify genuine evidence; do not override expiry.
5. Laptop sleep/network loss/stop prevents timely reception. After wake/reconnect, check backend, phone listener, queue and fresh ACK separately. A ready backend cannot prove the phone stayed bound during sleep. The app cannot replay missing notifications from wallet history.
6. End any authorized phone session using appropriate owner controls, stop the exact owned host process and retire session credentials. Do not stop unrelated containers/Java/tunnels/network or security services. Preserve replay history deliberately; queue cancellation and snapshot retention differ.

## Xiaomi limitation and next supervised validation

HyperOS may restart the process/queue while NotificationListenerService stays unbound. Supported bounded `requestRebind` and periodic WorkManager reconciliation cannot guarantee OEM binding. Battery/autostart causality is unknown. Unattended readiness remains PARTIAL.

If permission is granted but listener UNKNOWN/DISCONNECTED, do not rely on capture. A previously observed owner-operated notification-access OFF→ON restored binding. This manual recovery needs separate explicit owner authorization; neither app nor host toggles permission/OEM settings. Revocation can disable owner controls: inspect them before deliberately enabling again. Never bypass restricted settings, automate wallet UI or change battery/autostart restrictions speculatively.

Next device validation, with new authorization: record initial permission/controls/callback/timestamp; terminate only the probe process once; observe process/queue/work recovery separately from binding; check actual OS binding and a newly observed `onListenerConnected` callback. Debug synthetic candidate injection is separate transport evidence and cannot establish listener binding. If still unbound, obtain owner approval before a manual permission cycle, then recheck callback state. Keep capture/live reporting off unless that exact experiment authorizes them. No live TNG capture or payment is needed. Record original OEM settings without altering them; keep identifiers/private data out of shared evidence. Robolectric/JVM tests/emulators cannot establish Xiaomi recovery.

`./ops.tests.ps1` exercises actual private process lifecycle, ownership/credential isolation and restart persistence on disposable synthetic state, refusing an existing operations directory. Android/JVM suites exercise queue/retry/duplicate/expiry/confirmation scenarios. Claims remain limited to those synthetic checks.
