package dev.kaipay.notificationprobe

import android.content.Context
import android.app.Application
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import dev.kaipay.probe.ClassificationOutcome
import dev.kaipay.probe.Delivery
import dev.kaipay.probe.EvidenceQuality
import dev.kaipay.probe.RedactedEvent
import dev.kaipay.probe.TransactionDirection
import java.util.concurrent.TimeUnit

internal class LiveDelivery private constructor(private val app: Application) {
    private val dispatcher = DeliveryDispatcher { localError = "Delivery storage/scheduling failed; inspect status and retry" }
    internal lateinit var store: DeliveryStore; private set
    private lateinit var credentials: DeviceCredentialStorage
    @Volatile private var cachedConfig: DeliveryConfig? = null
    private var configurationGeneration = 0L
    private val initialized = java.util.concurrent.CountDownLatch(1)
    @Volatile private var ready = false
    @Volatile private var consent = false
    @Volatile private var generation = 0L
    @Volatile private var localError = ""

    init { control {
        try {
            credentials = DeliveryCredentials(app)
            store = DeliveryStore(app.noBackupFilesDir, onFailure = {
                consent = false; cachedConfig = null; credentials.clear()
                DeliveryWorker.cancelConnections()
                WorkManager.getInstance(app).cancelAllWorkByTag(TAG)
            })
            val loadedConfig = try { credentials.load() } catch (_: Exception) { null }
            synchronized(this) {
                if (configurationGeneration == 0L) cachedConfig = loadedConfig
                if (generation == 0L) { consent = store.enabled && cachedConfig != null && store.healthy; generation = store.generation }
                else if (store.healthy) store.setEnabled(false)
            }
            ready = true; resume()
        } finally { initialized.countDown() }
    } }
    internal fun awaitReady(): Boolean = initialized.await(10, TimeUnit.SECONDS) && ready
    internal fun config(): DeliveryConfig? = cachedConfig
    fun configure(config: DeliveryConfig) {
        config.validate(BuildConfig.DEBUG)
        synchronized(this) {
            cachedConfig = null
            val operation = ++configurationGeneration
            disable()
            control {
                try {
                    store.clear(); credentials.save(config)
                    synchronized(this) { if (operation == configurationGeneration) cachedConfig = config }
                    localError = "Device configuration saved; delivery remains off"
                } catch (_: Exception) { localError = "Device configuration could not be saved; delivery remains off" }
            }
        }
    }
    fun enable(): Boolean {
        val state = ProbeRuntime.session.snapshot()
        synchronized(this) {
            if (!ready || !store.healthy || !app.notificationAccessGranted() || !state.enabled || !state.connected || cachedConfig == null) return false
            consent = true; generation++
            control { store.setEnabled(true); resume() }
        }
        return true
    }
    fun disable() {
        val cancelScheduled = synchronized(this) {
            if (!consent && (ready && !store.enabled || !ready && generation != 0L)) return
            val wasReady = ready
            consent = false; generation++
            wasReady
        }
        try {
            DeliveryWorker.cancelConnections()
            if (cancelScheduled) WorkManager.getInstance(app).cancelAllWorkByTag(TAG)
        } catch (_: Exception) { localError = "Work cancellation unavailable; durable disable still queued" }
        finally { control(dropStaleCallbacks = true) { store.setEnabled(false) } }
    }
    fun clear() {
        disable()
        control { store.clear(); localError = "Delivery history cleared; do not reuse previous events" }
    }
    fun forget() {
        synchronized(this) {
            cachedConfig = null; configurationGeneration++
            disable()
            control { store.clear(); try { credentials.clear(); localError = "Device credential removed" }
                catch (_: Exception) { localError = "Credential cleanup failed; delivery remains off" } }
        }
    }
    fun retry() { if (consent) control { store.retry().forEach(::schedule); resume() } }
    fun synthetic(amount: Long): Boolean {
        if (!BuildConfig.DEBUG || !eligibleConsent()) return false
        val captured = generation
        candidate(captured) {
            if (eligibleConsent() && captured == generation) {
                config()?.let { store.synthetic(it.deviceId, amount)?.let { event -> schedule(event.eventId) } }
            }
        }
        return true
    }
    fun observed(key: String, event: RedactedEvent) {
        if (!eligibleConsent() || event.delivery == Delivery.DUPLICATE || event.delivery == Delivery.OUT_OF_ORDER) return
        val metadata = event.metadata ?: return
        val inspection = event.inspection ?: return
        val result = inspection.classification
        val amount = result.amountMinor ?: 0L
        // Bounded transient digest: only its private keyed form is persisted by the background store.
        // Do not capture inspection text in the persistence Runnable or place this digest on the wire.
        val contentDigest = java.security.MessageDigest.getInstance("SHA-256").digest(buildString {
            inspection.fields.toSortedMap().forEach { (name, value) ->
                append(name.length); append(':'); append(name); append(value.length); append(':'); append(value)
            }
            inspection.textLines.forEach { append(it.length); append(':'); append(it) }
        }.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        val incoming = result.outcome == ClassificationOutcome.INCOMING_CANDIDATE &&
            result.direction == TransactionDirection.INCOMING && result.currency == "MYR" &&
            result.confidence == EvidenceQuality.RULE_MATCHED && result.ruleId == "tng.receipt.en.observed.v1" &&
            amount in 1L..1_000_000L && !metadata.groupSummary
        val captured = generation
        // Capture only minimized values. The Runnable never captures event/inspection/raw notification fields.
        val postTime = metadata.postTime
        val changed = event.delivery == Delivery.UPDATE
        candidate(captured) {
            if (!eligibleConsent() || captured != generation) return@candidate
            val deviceId = config()?.deviceId ?: return@candidate
            if (incoming) store.observe(key, postTime, amount, deviceId, changed, contentDigest)?.let { schedule(it.eventId) }
            else store.unknownUpdate(key, postTime)
            store.rows().filter { it.status == DeliveryStatus.INVALIDATION_PENDING }.forEach { schedule(it.candidate.eventId) }
        }
    }
    fun removed(key: String, postTime: Long) {
        val captured = generation
        if (consent) candidate(captured) { store.removed(key, postTime) }
    }
    fun unreadable(key: String, postTime: Long) {
        if (!eligibleConsent()) return
        val captured = generation
        candidate(captured) {
            if (eligibleConsent() && captured == generation) {
                store.unknownUpdate(key, postTime)
                store.rows().filter { it.status == DeliveryStatus.INVALIDATION_PENDING }.forEach { schedule(it.candidate.eventId) }
            }
        }
    }
    private fun eligibleConsent(): Boolean = consent && app.notificationAccessGranted() &&
        ProbeRuntime.session.snapshot().let { it.enabled && it.connected }
    internal fun mayTransmit(captured: Long): Boolean {
        if (!app.notificationAccessGranted()) { disable(); return false }
        return ready && store.healthy && consent && generation == captured
    }
    internal fun consentGeneration() = generation
    private fun resume() {
        if (!consent) return
        if (!app.notificationAccessGranted()) { disable(); return }
        // Queue transmission survives ordinary process death; new notification detection remains off.
        store.rows().filter { it.status == DeliveryStatus.PENDING || it.status == DeliveryStatus.INVALIDATION_PENDING }
            .forEach { schedule(it.candidate.eventId) }
    }
    private fun schedule(id: String) {
        val config = config() ?: return
        val constraints = Constraints.Builder().apply {
            if (endpoint(config.baseUrl, BuildConfig.DEBUG).scheme == "https") setRequiredNetworkType(NetworkType.CONNECTED)
        }.build()
        val work = OneTimeWorkRequest.Builder(DeliveryWorker::class.java)
            .setInputData(Data.Builder().putString("eventId", id).build())
            .setConstraints(constraints).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG).build()
        val prefix = if (store.find(id)?.invalidate == true) "invalidate" else "candidate"
        WorkManager.getInstance(app).enqueueUniqueWork("$prefix-$id", ExistingWorkPolicy.KEEP, work)
    }
    fun status(): String = try {
        if (!ready) "Experimental delivery initializing; disabled until ready" else buildString {
            append("Experimental delivery enabled: "); append(consent)
            append("\nConfiguration present: "); append(config() != null)
            val rows = store.displayRows()
            DeliveryStatus.values().forEach { status -> append("\n"); append(status); append(": "); append(rows.count { it.status == status }) }
            if (store.warning.isNotEmpty()) { append("\n"); append(store.warning) }
            if (localError.isNotEmpty()) { append("\n"); append(localError) }
            rows.takeLast(5).forEach { append("\n"); append(it.candidate.source); append(" "); append(it.status); append(" — "); append(it.note) }
            append("\nNotification evidence is unverified. Check actual TNG history before receiver confirmation.")
        }
    } catch (_: Exception) { "Delivery storage unavailable; disable and clear before testing" }
    private fun control(dropStaleCallbacks: Boolean = false, action: () -> Unit) {
        dispatcher.control(dropStaleCallbacks, action)
    }
    private fun candidate(captured: Long, action: () -> Unit) {
        if (dispatcher.candidate({ consent && generation == captured }, action) == DeliveryDispatcher.Admission.FULL)
            localError = "Delivery callback capacity reached; candidate dropped"
    }
    companion object {
        private const val TAG = "kaipay-experimental-delivery"
        @Volatile private var instance: LiveDelivery? = null
        fun get(context: Context): LiveDelivery = instance ?: synchronized(this) {
            instance ?: LiveDelivery(context.applicationContext as Application).also { instance = it }
        }
        fun disableIfInitialized() { instance?.disable() }
    }
}
