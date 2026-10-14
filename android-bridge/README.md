# Experimental Android notification feasibility probe

**TNG payment compatibility: UNVERIFIED. Android debug build, headless tests and lint verified; validate notification behavior on your own physical device.**

This experimental companion is separate from KaiPay's financial services. Local detection and live delivery require separate explicit enabling. Minimized, unverified candidates can reach the isolated experimental server; notification text never leaves the phone. It does not verify settlement or automate TNG. Builds and synthetic tests do not establish real TNG compatibility.

## Evidence and limits

The official [Google Play listing](https://play.google.com/store/apps/details?id=my.com.tngdigital.ewallet) identifies the package `my.com.tngdigital.ewallet`. The [TNG incoming-transfer FAQ](https://support.tngdigital.com.my/hc/en-my/articles/41434005775769-Will-I-receive-a-notification-if-someone-transfers-money-to-my-account) promises an **in-app notification**, which may never become an Android system notification. It does not specify accessible fields, amounts, references, sender identity, language or wording. Do not infer them from this prototype.

[Android NotificationListenerService](https://developer.android.com/reference/android/service/notification/NotificationListenerService) receives system notification posts, updates and removals after explicit user access. It cannot read TNG's in-app notification inbox or transaction history. Access is broad at the Android permission boundary; our callback immediately discards packages other than the verified package before reading extras. Displayed names/icons are not trusted package identifiers. A notification's package is also not evidence of settlement or proof of the installed application's signing identity.

The listener API began at API 18; this probe requires Android 8/API 26+. On Android Q/10 and below, low-RAM devices cannot bind listeners; work-profile listeners are ignored and administrators may block cross-profile notifications. Android N/7+ callbacks run on the main thread. Android 15 [redacts sensitive/OTP notifications for untrusted listeners](https://developer.android.com/about/versions/15/behavior-changes-all#otp-redaction); accept redaction and never work around it. Android 14/API34 added a lockdown removal reason; the probe clears diagnostics on TNG notification removal, including lockdown. [Restricted settings](https://support.google.com/android/answer/12623953?hl=en) on Android 13+ and device policies may prevent sideloaded notification access. If inaccessible, record the blocker and stop.

TNG's [P2P seller FAQ](https://support.tngdigital.com.my/hc/en-my/articles/17608981927577-What-is-P2P-seller) documents personal QR usage and an opt-in seller program. This does not grant this experiment an official integration or unrestricted merchant rights. Its [user terms](https://www.touchngo.com.my/assets/pdf/user-tnc.pdf), dated June 5, 2026 at investigation, require safeguarding credentials, using official app stores, intended account usage and not circumventing security (clauses 1.41, 2.1, 2.2). Check provider/account-specific eligibility before any commercial expansion.

## Implementation

- Detection starts off after process restart. Explicit enable requires normal Android notification access. Disable, disconnect, revocation and service destruction erase content and require another explicit enable.
- INTERNET and WorkManager's network-state, wake-lock, boot and foreground-service permissions support optional background delivery. No storage, Accessibility or notification-post permission, analytics or raw-content logs. Notification text remains memory-only. Minimized candidate history and encrypted configuration use private, backup-excluded storage. FLAG_SECURE protects the screen; rendered text and credential input are hidden/cleared while paused.
- Local inspection supports EXTRA_TITLE, EXTRA_TEXT, EXTRA_BIG_TEXT, EXTRA_SUB_TEXT, EXTRA_SUMMARY_TEXT and EXTRA_TEXT_LINES. Supported styled CharSequences become plain strings. Missing values remain absent. Malformed values, fields over 4,096 characters, more than 16 lines, or over 16,384 total characters suppress all content. Conservative credential markers and unlabelled short codes suppress text; this can suppress numeric references and is not a comprehensive secret detector. Never trigger credential notifications for testing.
- Classification returns INCOMING_CANDIDATE, OUTGOING_OR_UNRELATED, AMBIGUOUS or UNKNOWN, with a fixed explanation and evidence quality. Exact RM/MYR values use integer cents; malformed, conflicting or unsupported monetary values are ambiguous. Generic outgoing, promotional, request and non-receipt indicators exclude incoming classification. Amount alone cannot establish direction. **One reviewed English receipt structure is supported as a candidate only:** the exact received-money title plus an anchored transfer body and app-instruction suffix. Matching expanded text is required when present; nonblank additional fields or text lines do not match. Other languages, wording and variations remain unverified. Integer/grouped monetary parsing is tested as a parser capability, not independently observed provider formatting. Fixture names and monetary values are invented. References and sender identity are not inferred.
- Process-random HMAC aliases replace raw Android keys and channels. Up to 32 observations expire after five minutes of elapsed time, including device sleep. Content fingerprints distinguish same-timestamp updates from duplicates. Older callbacks retain metadata only; group summaries never identify a receipt. These are diagnostics, not transaction identity or delivery guarantees.
- Removing a TNG notification clears all raw observations, including lockdown removal. Bounded five-minute tombstones suppress delayed callbacks for removed sources. Clear/disable/process death resets deduplication. No historical rescan occurs.
- The foreground interface refreshes every second and offers manual refresh. It shows populated fields, amount/classification, aliases, update/removal counters and uncertainty. Clear diagnostics erases observations; Disable and clear also stops inspection.
- A debug-only read-only snapshot provider supports explicit local ADB inspection. DUMP permission and shell UID are both required; ordinary apps cannot read it. No URI grants, file access, writes or calls; the provider is absent from release builds. Export includes at most four recent whole events within a 120,000-byte content budget, with an omitted-event count. Access, enable, connection and expiry gates apply. Manually saved host snapshots are sensitive: keep them outside Git and delete them after use.
## Build and install prerequisites

The earlier v0.3 baseline passed 84 core and 54 headless Android tests. The delivery build adds queue, transport, worker and harness tests; run the command below to verify current source. Release builds omit both debug providers and forbid cleartext, while retaining WorkManager's ordinary initialization components. The earlier text-inspection build on one physical Android 16 device exposed title/body/expanded text for an incoming notification, with independently checked receipt and matching parsed amount. Classification on a fresh real notification remains unverified. Broader compatibility and reliability remain unverified. Request device-owner permission before installing.

Use the installed JDK **21** for the Gradle process, Android SDK platform 35/build tools 35.0.0/platform-tools, and Gradle **8.11.1**. AGP **8.9.2** and Kotlin **1.9.25** are pinned in the build. [Official AGP compatibility](https://developer.android.com/build/releases/agp-8-9-0-release-notes) specifies Gradle 8.11.1, SDK35 and minimum JDK17; [Gradle's Java compatibility table](https://docs.gradle.org/8.11.1/userguide/compatibility.html) supports running Java21 since Gradle8.5. The build emits Java17 bytecode, requiring no separate JDK17 install. The machine's default PATH JDK25 is unsuitable for Gradle8.11.1. There is deliberately no generated Gradle wrapper binary in this draft. Use an independently installed Gradle8.11.1; do not invoke a nonexistent `gradlew`.

Kotlin 1.9.25 is outside JetBrains's documented fully supported Gradle/AGP range for this combination. The executed Android build establishes specific observed behavior; it does not establish complete vendor-supported compatibility. See the [Kotlin compatibility matrix](https://kotlinlang.org/docs/gradle-configure-project.html). Keep compiler/AGP upgrades coordinated with Android D8/R8 requirements rather than changing either arbitrarily.

Use an emulator-free setup with the official SDK command-line tools, Gradle and a physical phone. Personally review and accept Google's [SDK license agreement](https://developer.android.com/studio#command-tools) **before downloading or using the SDK**. Do not automate legal acceptance. With `sdkmanager` on PATH, the [minimal documented package installation syntax](https://developer.android.com/tools/sdkmanager) is:

```powershell
sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"
```

Review any required package-license prompts yourself; avoid accepting licenses for unrelated packages. Current Android documentation deprecates `sdkmanager` in favor of the new Android CLI; these commands apply to the still documented SDK Command-Line Tools. No emulator, system image, NDK or Android Studio is required. The verified build used command-line tools 22.0, platform 35 revision 2, build-tools 35.0.0 and platform-tools 37.0.1.

Set `ANDROID_HOME` to your local SDK or create an ignored `local.properties` containing `sdk.dir=<local SDK path>`. From `android-bridge/`, select JDK21 and run:

```powershell
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-21'
$env:PATH = "$env:JAVA_HOME/bin;$env:PATH"
gradle --version
gradle :probe-core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:assembleRelease --no-daemon --max-workers=1 --console=plain
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The command runs core and Robolectric/JUnit tests, assembly and lint sequentially with one worker. Test-only dependencies simulate Android framework callbacks, manifest access and UI/lifecycle behavior on API 26/35; they may download framework libraries from Maven. Tests disable Robolectric's injected Conscrypt provider to avoid an Oracle JDK signature-verifier incompatibility, retaining real JVM cryptography and assertions; production provider selection is unchanged. They require no emulator and do not prove OS notification delivery, OEM policy behavior or TNG compatibility. The test JVM has one fork and a 512 MiB heap; increase resources only in response to measured failures, keeping all verification enabled. Do not publish signing keys, local SDK paths, APKs or notification captures.

JVM-only verification without an Android SDK, from `android-bridge/probe-core/`:

```powershell
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-21'
$env:PATH = "$env:JAVA_HOME/bin;$env:PATH"
mvn -o test
```

An initial online `mvn test` may be needed to resolve public Kotlin/JUnit dependencies. This command tests only `probe-core`; it is not Android compilation, permission testing or device evidence.

## Safe real-device experiment

After build/test/lint succeeds, connect your own physical phone with a data-capable cable, enable USB debugging and personally authorize the computer. Keep serial numbers private. Install only with explicit device-owner permission; do not reset apps, clear wallet data or change wallet security.

1. Open the probe. Detection must start disabled with zero local observations. Grant normal Android notification access yourself; enable and confirm OS access, detection and listener connection are true. Do not bypass restricted settings or OTP redaction.
2. Check disable/enable, revocation/regrant, restart/reconnection and an unrelated Clock timer. Other-app notifications must not appear. After force stopping only the probe, reopen: detection is disabled and observations empty. Regrant alone never enables inspection.
3. If a suitable normal TNG notification is available, inspect its populated fields locally. Otherwise, when ready, personally arrange one small legitimate incoming transfer with a consenting person or your separate eligible account. The probe never operates TNG or initiates transfers. Do not trigger OTPs or repeat transfers merely for parser development.
4. Before removing the notification, inspect title/body/expanded/supporting fields and classification in the probe. A matching reviewed structure can show INCOMING_CANDIDATE; other wording may remain UNKNOWN even when an amount is present. Independently verify receipt in your own wallet history. One observation cannot establish all languages, outgoing formats, references, ordering, locked arrival or reliability.
5. For authorized local engineering inspection of the debug build only, query the probe's provider rather than system-wide notification dumps. The command below contains sensitive text in its output: redirect it into an ignored local file, inspect locally and delete it after use. Do not copy contents into public reports, remote services, terminal transcripts or Git. The ordinary app UI is sufficient when ADB access is unavailable.
6. Removing the TNG notification clears observations. Finish with Disable and clear, revoke notification access in normal settings and delete any host snapshot. If only in-app messages or unusable/redacted system fields are available, record the evidence gap; do not bypass protections.

From a repository with `.local/` ignored, using an already authorized ADB computer:

```powershell
New-Item -ItemType Directory -Force .local | Out-Null
adb shell content query --uri content://dev.kaipay.notificationprobe.diagnostics/snapshot > .local/tng-snapshot-private.txt
# Inspect locally; never upload or commit this file.
Remove-Item -LiteralPath .local/tng-snapshot-private.txt
```

## Evidence needed before a later batch

The reviewed rule comes from one observed English incoming structure, with invented names/amounts in tests. Further provider wording needs additional sanitized evidence and reviewed anchored rules. Independently establish incoming direction, exact amount, available transaction identity, safe distinction from outgoing/promotion/request/balance messages, updates, language variations and foreground/background/locked delivery. A notification and package match do not prove authenticity or settlement. Production financial confirmation remains outside this companion.

## Experimental delivery

Provision a distinct server-assigned device UUID and newly generated experimental device token, separate from simulator and receiver credentials. Enter them with the server base URL in the protected companion screen. Configuration is encrypted using Android Keystore AES-GCM in `noBackupFilesDir`; tokens never appear in URLs, diagnostic exports, WorkManager input or logs. Save/replacement turns delivery off and resets old queue/history. The server can revoke or replace its configured device token. Never use wallet credentials.

Enable normal notification access, then local detection, then **Enable experimental live delivery**. Default delivery is off. Detection resets off after process death; deliberately enabled delivery consent and existing minimized queued events can survive an ordinary process death. Listener disconnect, permission revocation, explicit disable and credential replacement cancel transmission. Cancelled events never resume automatically. An already transmitted request may have reached the server before cancellation; disabling the phone cannot retract accepted server evidence.

Only positive MYR integer amounts up to 1,000,000 cents with `INCOMING_CANDIDATE`, `RULE_MATCHED` and the reviewed `tng.receipt.en.observed.v1` profile are queued from TNG callbacks. Payloads contain random event/lifecycle UUIDs, assigned device UUID, classification/profile, source, integer amount, observation instant and evidence quality. No titles, bodies, sender names, Android keys, wallet details, screenshots or invented provider references are transmitted.

The background persistence dispatcher writes the minimized candidate atomically before WorkManager scheduling. It bounds notification callback work at128 entries and preserves ordered consent/configuration controls even when callbacks saturate that queue; disabling invalidates and removes stale callbacks without discarding bootstrap or cleanup controls. Unique work uses exponential backoff starting at 30 seconds, at most ten upload attempts and ten separate invalidation attempts per event, with at most three deliberate recovery retries, always within a 24-hour age limit. I/O failures, HTTP408/429 and server5xx are retryable; other rejections, redirects and TLS certificate errors are permanent. The durable queue holds at most100 events with a512KiB snapshot ceiling and refuses growth rather than evicting replay history. Status shows pending, delivered, invalidation pending, invalidated, failed and expired. Storage failures disable transmission and revoke local credentials; reprovision after resolving the storage problem.

Locally keyed notification fingerprints and lifecycle tombstones survive restart/disable without transmitting Android identifiers. Identical callbacks reuse existing event identity; unsupported or changed updates invalidate the original evidence through an authenticated server route instead of introducing another matching opportunity. If an original acknowledgement was lost during enabled delivery, the worker safely retries the original event before its idempotent invalidation. A never-attempted cancelled event remains cancelled after re-enable. For a previously attempted cancelled event, a later changed callback sends only an invalidation control request, never the original candidate again; a missing server observation terminates that control request safely. Newer callbacks after an observed removal can start a new lifecycle; missed removals and key reuse remain uncertain and are treated conservatively. This is transport idempotency, never guaranteed unique TNG transaction identity. Clear/forget explicitly resets duplicate protection; do not replay previous notifications after a reset.

For owner-authorized USB testing, keep the separate server bound to `127.0.0.1:28082`, run `adb reverse tcp:28082 tcp:28082`, and configure the debug app with `http://127.0.0.1:28082`. Only debug builds permit that exact HTTP destination and port; all other configurations require HTTPS with ordinary certificate verification. Redirects and URL credentials/query/fragment/path are rejected. Release builds deny all cleartext, including loopback. USB work omits network-state constraints because the tunnel can work without Wi-Fi/mobile; HTTPS work requires a connected network. Remove the reverse mapping with `adb reverse --remove tcp:28082` at cleanup. No public tunnel or server exposure is needed.

Use **DEBUG: queue invented SYNTHETIC candidate** with invented integer cents to test the Android-to-server-to-browser flow before real notifications. Such events are explicitly `SYNTHETIC`, profile `synthetic.debug.v1`, quality `SYNTHETIC`; they cannot masquerade as provider-origin observations. Notification detection only produces tentative evidence. The receiver must review actual TNG transaction history and confirm through the separate receiver-authorized server action. Matching amounts and timestamps do not identify an order.

A separate debug-only delivery provider at `content://dev.kaipay.notificationprobe.delivery` supports owner-authorized local ADB `content call`: `status`, `events`, `provision`, `enable`, `synthetic`, `disable`, `retry`, `clear`, `forget`. Both shell UID and DUMP permission are required. `provision` receives a JSON argument containing exactly `baseUrl`, `deviceId`, `token`; do not save its command or arguments in logs. `synthetic` receives invented integer cents. `events` returns only random event UUID, source, status, attempt counters and upload/invalidation flags. Existing diagnostic snapshot access remains read-only and can expose locally permitted notification text: never export it during synthetic delivery tests. Neither provider is included in release builds.

WorkManager persists scheduling across process death and reboot, but force-stop, Doze, OEM battery restrictions and USB disconnection can postpone execution. No immediate delivery, exactly-once transport or universal locked/background reliability is promised. Delayed server observations remain explicitly unverified and do not silently match expired requests. A future ordinary incoming payment is an optional owner-led classifier-validation step, not a prerequisite for synthetic integration testing.
