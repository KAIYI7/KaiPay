# Experimental Android notification feasibility probe

**TNG payment compatibility: UNVERIFIED. Android debug build, headless tests and lint verified; validate notification behavior on your own physical device.**

This is an offline observation probe, separate from KaiPay's financial services. It does not verify payment, transmit observations, or automate TNG. Compiling the APK and passing headless tests do not establish notification delivery on a physical phone.

## Evidence and limits

The official [Google Play listing](https://play.google.com/store/apps/details?id=my.com.tngdigital.ewallet) identifies the package `my.com.tngdigital.ewallet`. The [TNG incoming-transfer FAQ](https://support.tngdigital.com.my/hc/en-my/articles/41434005775769-Will-I-receive-a-notification-if-someone-transfers-money-to-my-account) promises an **in-app notification**, which may never become an Android system notification. It does not specify accessible fields, amounts, references, sender identity, language or wording. Do not infer them from this prototype.

[Android NotificationListenerService](https://developer.android.com/reference/android/service/notification/NotificationListenerService) receives system notification posts, updates and removals after explicit user access. It cannot read TNG's in-app notification inbox or transaction history. Access is broad at the Android permission boundary; our callback immediately discards packages other than the verified package before reading extras. Displayed names/icons are not trusted package identifiers. A notification's package is also not evidence of settlement or proof of the installed application's signing identity.

The listener API began at API 18; this probe requires Android 8/API 26+. On Android Q/10 and below, low-RAM devices cannot bind listeners; work-profile listeners are ignored and administrators may block cross-profile notifications. Android N/7+ callbacks run on the main thread. Android 15 [redacts sensitive/OTP notifications for untrusted listeners](https://developer.android.com/about/versions/15/behavior-changes-all#otp-redaction); accept redaction and never work around it. Android 14/API34 added a lockdown removal reason; the probe clears diagnostics on TNG notification removal, including lockdown. [Restricted settings](https://support.google.com/android/answer/12623953?hl=en) on Android 13+ and device policies may prevent sideloaded notification access. If inaccessible, record the blocker and stop.

TNG's [P2P seller FAQ](https://support.tngdigital.com.my/hc/en-my/articles/17608981927577-What-is-P2P-seller) documents personal QR usage and an opt-in seller program. This does not grant this experiment an official integration or unrestricted merchant rights. Its [user terms](https://www.touchngo.com.my/assets/pdf/user-tnc.pdf), dated June 5, 2026 at investigation, require safeguarding credentials, using official app stores, intended account usage and not circumventing security (clauses 1.41, 2.1, 2.2). Check provider/account-specific eligibility before any commercial expansion.

## Implementation

- Explicit temporary enable/disable plus OS access settings/status. Detection starts off after process restart. Disable, listener disconnect and service destruction erase the memory store and require another explicit enable; separately revoke OS access in Android settings when finished.
- No `INTERNET`, storage, Accessibility or notification-post permission; no analytics, raw-content logging, export or network retries. No notification data is persisted. Backup is disabled and explicit Android 12+ cloud/device-transfer rules exclude every supported data domain. Future OS transfer mechanisms require separate validation. The screen uses `FLAG_SECURE` and applies system-bar/cutout insets to keep controls reachable.
- Only title/text/expanded-text presence flags survive. Missing fields remain UNKNOWN; malformed/oversized fields fail closed. Conservative credential markers suppress all presence flags. No credential value is parsed or retained; the marker filter is not a comprehensive sensitive-content detector, so no content is ever displayed or stored.
- Live notifications always remain UNKNOWN. No production direction/amount parsing. The independently tested `SYNTHETIC_INCOMING MYR 12.34` grammar is an invented test protocol, never an actual TNG sample and never called by the listener.
- A process-random HMAC of the Android notification key tracks at most 32 source notifications. Raw keys, IDs, tags and content are not retained. Up to 32 redacted callback diagnostics distinguish new/duplicate/update/out-of-order metadata. Equal post time and presence flags can hide content changes; reused notification IDs can hide distinct transactions. This is diagnostic deduplication, not transaction identity.
- Removing a TNG notification clears diagnostics (including lockdown removal). Clear/disable/process death resets deduplication. Observation loss during disconnect, process death, disable or absent system notifications is expected. No rescan of historical notifications, durable delivery or exactly-once guarantee.
- Press **Refresh status** after a notification to see the current diagnostics. Previously displayed counts can remain stale until refreshed; seeing an event does not identify its payment direction or transaction.

## Build and install prerequisites

The verified emulator-free build executed 19 core tests and 28 headless Android tests: 47 total, zero failures/errors/skips. Debug APK assembly succeeded and lint reported zero errors and warnings using Gradle 8.11.1 and JDK 21. Physical notification delivery and OEM behavior require device-specific validation. Repeat the full verification command after changing source; request device-owner permission before installing.

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
gradle :probe-core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --no-daemon --max-workers=1 --console=plain
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The command runs the 19 core tests plus 28 Robolectric/JUnit tests, assembly and lint sequentially with one worker. Test-only dependencies simulate Android framework callbacks, manifest access and UI/lifecycle behavior on API 26/35; they may download framework libraries from Maven. Tests disable Robolectric's injected Conscrypt provider to avoid an Oracle JDK signature-verifier incompatibility, retaining real JVM cryptography and assertions; production provider selection is unchanged. They require no emulator and do not prove OS notification delivery, OEM policy behavior or TNG compatibility. The test JVM has one fork and a 512 MiB heap; increase resources only in response to measured failures, keeping all verification enabled. Do not publish signing keys, local SDK paths, APKs or notification captures.

JVM-only verification without an Android SDK, from `android-bridge/probe-core/`:

```powershell
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-21'
$env:PATH = "$env:JAVA_HOME/bin;$env:PATH"
mvn -o test
```

An initial online `mvn test` may be needed to resolve public Kotlin/JUnit dependencies. This command tests only `probe-core`; it is not Android compilation, permission testing or device evidence.

## Safe real-device experiment

After the complete build/test/lint command passes, connect a data-capable USB cable. If needed, enable Developer Options by tapping Build number seven times, enable USB debugging, and personally authorize this computer on the unlocked phone. Windows may need the phone manufacturer's USB driver if ADB cannot detect it. Run `adb devices` locally; do not copy its device serial into public reports. Install the debug APK only after explicit owner permission. Do not uninstall/reset apps or clear TNG data. Perform and record the following checks on the actual device; headless tests do not replace them.

1. Use your own normal, unmodified physical Android phone and official-store TNG app. Record only phone Android version, app version, profile type, permission status and redacted counters in private notes.
2. Open the probe, open Android notification access settings, and explicitly grant access if the device permits it. Enable local detection, then refresh; check OS access, enabled and connected statuses are true.
3. Before real money, test disable/enable, OS revocation/regrant, listener reconnect and app reopening. Revocation or disconnect must clear diagnostics and require explicit enable. Activity recreation can preserve the gate in the same process; actual process death starts disabled. Swiping Recents is not proof of process death. Confirm another app's naturally occurring notifications do not enter the list; check control visibility around system bars/cutouts. Do not trigger OTPs.
4. Personally authorize and perform one ordinary small personal incoming transfer with a consenting person or your own separate eligible account using TNG's supported UI. Keep personal QR/identifiers private. Do not manufacture samples or use `adb dumpsys notification`/logcat content captures. Check whether an Android system notification appears without exporting its text/screenshots.
5. Before dismissing the notification or opening TNG history, open the probe and refresh; record only UNKNOWN/presence flags and new/duplicate/update counters. Removal clears all diagnostics, so zero events after removal is inconclusive. Then independently check TNG history yourself. Observe locked/unlocked and foreground/background behavior only where naturally available; one transfer cannot establish consistency in all states. Naturally occurring unrelated TNG activity must also remain UNKNOWN, so this probe cannot establish payment direction.
6. Disable/clear the probe and revoke Android access when done. If transfers are in-app only or content inaccessible, report compatibility insufficient and stop. No bypass or automation is authorized.

## Evidence needed before a later batch

Require actual Android assemble/lint/JVM results and physical-phone permission/lifecycle checks; confirm the official installed package and trusted install source, system-notification availability for a real incoming transfer, redacted field presence, duplicate/update behavior, foreground/background/lock-state effects and distinction from unrelated TNG events. Verify receiver history manually. If later parsing requires raw samples, obtain separate consent and a private storage/redaction plan first; this probe intentionally does not collect them. Live parsing, transmission and financial confirmation remain outside this foundation.
