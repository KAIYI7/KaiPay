# Experimental Android notification feasibility probe

**TNG payment compatibility: UNVERIFIED. Android debug build, headless tests and lint verified; validate notification behavior on your own physical device.**

This is an offline observation probe, separate from KaiPay's financial services. It does not verify payment, transmit observations, or automate TNG. Compiling the APK and passing headless tests do not establish notification delivery on a physical phone.

## Evidence and limits

The official [Google Play listing](https://play.google.com/store/apps/details?id=my.com.tngdigital.ewallet) identifies the package `my.com.tngdigital.ewallet`. The [TNG incoming-transfer FAQ](https://support.tngdigital.com.my/hc/en-my/articles/41434005775769-Will-I-receive-a-notification-if-someone-transfers-money-to-my-account) promises an **in-app notification**, which may never become an Android system notification. It does not specify accessible fields, amounts, references, sender identity, language or wording. Do not infer them from this prototype.

[Android NotificationListenerService](https://developer.android.com/reference/android/service/notification/NotificationListenerService) receives system notification posts, updates and removals after explicit user access. It cannot read TNG's in-app notification inbox or transaction history. Access is broad at the Android permission boundary; our callback immediately discards packages other than the verified package before reading extras. Displayed names/icons are not trusted package identifiers. A notification's package is also not evidence of settlement or proof of the installed application's signing identity.

The listener API began at API 18; this probe requires Android 8/API 26+. On Android Q/10 and below, low-RAM devices cannot bind listeners; work-profile listeners are ignored and administrators may block cross-profile notifications. Android N/7+ callbacks run on the main thread. Android 15 [redacts sensitive/OTP notifications for untrusted listeners](https://developer.android.com/about/versions/15/behavior-changes-all#otp-redaction); accept redaction and never work around it. Android 14/API34 added a lockdown removal reason; the probe clears diagnostics on TNG notification removal, including lockdown. [Restricted settings](https://support.google.com/android/answer/12623953?hl=en) on Android 13+ and device policies may prevent sideloaded notification access. If inaccessible, record the blocker and stop.

TNG's [P2P seller FAQ](https://support.tngdigital.com.my/hc/en-my/articles/17608981927577-What-is-P2P-seller) documents personal QR usage and an opt-in seller program. This does not grant this experiment an official integration or unrestricted merchant rights. Its [user terms](https://www.touchngo.com.my/assets/pdf/user-tnc.pdf), dated June 5, 2026 at investigation, require safeguarding credentials, using official app stores, intended account usage and not circumventing security (clauses 1.41, 2.1, 2.2). Check provider/account-specific eligibility before any commercial expansion.

## Implementation

- Detection starts off after process restart. Explicit enable requires normal Android notification access. Disable, disconnect, revocation and service destruction erase content and require another explicit enable.
- No INTERNET, storage, Accessibility or notification-post permission, analytics, raw-content logs, backend connection or automatic export. Data is memory-only. Backup/cloud/device transfer are disabled. FLAG_SECURE protects the screen; rendered text is hidden while paused.
- Local inspection supports EXTRA_TITLE, EXTRA_TEXT, EXTRA_BIG_TEXT, EXTRA_SUB_TEXT, EXTRA_SUMMARY_TEXT and EXTRA_TEXT_LINES. Supported styled CharSequences become plain strings. Missing values remain absent. Malformed values, fields over 4,096 characters, more than 16 lines, or over 16,384 total characters suppress all content. Conservative credential markers and unlabelled short codes suppress text; this can suppress numeric references and is not a comprehensive secret detector. Never trigger credential notifications for testing.
- Classification returns INCOMING_CANDIDATE, OUTGOING_OR_UNRELATED, AMBIGUOUS or UNKNOWN, with a fixed explanation and evidence quality. Exact RM/MYR values use integer cents; malformed, conflicting or unsupported monetary values are ambiguous. Generic outgoing, promotional, request and non-receipt indicators exclude incoming classification. Amount alone cannot establish direction. **The live positive-rule registry is empty until actual wording is observed and reviewed. Positive rules in tests are invented synthetic fixtures.** References and sender identity are not inferred.
- Process-random HMAC aliases replace raw Android keys and channels. Up to 32 observations expire after five minutes of elapsed time, including device sleep. Content fingerprints distinguish same-timestamp updates from duplicates. Older callbacks retain metadata only; group summaries never identify a receipt. These are diagnostics, not transaction identity or delivery guarantees.
- Removing a TNG notification clears all raw observations, including lockdown removal. Bounded five-minute tombstones suppress delayed callbacks for removed sources. Clear/disable/process death resets deduplication. No historical rescan occurs.
- The foreground interface refreshes every second and offers manual refresh. It shows populated fields, amount/classification, aliases, update/removal counters and uncertainty. Clear diagnostics erases observations; Disable and clear also stops inspection.
- A debug-only read-only snapshot provider supports explicit local ADB inspection. DUMP permission and shell UID are both required; ordinary apps cannot read it. No URI grants, file access, writes or calls; the provider is absent from release builds. Export includes at most four recent whole events within a 120,000-byte content budget, with an omitted-event count. Access, enable, connection and expiry gates apply. Manually saved host snapshots are sensitive: keep them outside Git and delete them after use.
## Build and install prerequisites

The current emulator-free verification passed 70 core tests and 52 headless Android tests (122 total, zero failures/errors/skips), debug assembly and lint (zero issues) with Gradle 8.11.1 and JDK 21. The merged release manifest contains no diagnostic provider or requested permissions. Physical text inspection and real-format classification remain unverified. Repeat verification after changing source; request device-owner permission before installing.

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
gradle :probe-core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:processReleaseMainManifest --no-daemon --max-workers=1 --console=plain
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
4. Before removing the notification, inspect title/body/expanded/supporting fields and classification in the probe. An amount can be present while direction remains UNKNOWN. Independently verify receipt in your own wallet history. One observation cannot establish all languages, outgoing formats, references, ordering, locked arrival or reliability.
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

Actual provider wording needs observed, sanitized fixtures and reviewed anchored rules. The live registry remains empty until then. Independently establish incoming direction, exact amount, available transaction identity, safe distinction from outgoing/promotion/request/balance messages, updates, language variations and foreground/background/locked delivery. A notification and package match do not prove authenticity or settlement. Live uploads, order matching and financial confirmation require a separately authorized design; they are absent from this tool.
