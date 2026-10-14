package dev.kaipay.notificationprobe

import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.io.IOException
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class DeliveryStoreTest {
    @get:Rule val directory = TemporaryFolder()
    private var time = 1_800_000_000_000L
    private val device = UUID.randomUUID().toString()
    private lateinit var store: DeliveryStore
    @Before fun setup() { store = DeliveryStore(directory.root, { time }); store.setEnabled(true) }

    @Test fun `persisted retry keeps identity and minimizes stored and network data`() {
        val event = requireNotNull(store.observe("PRIVATE_RAW_ANDROID_KEY", time, 123, device))
        store.mark(event.eventId, DeliveryStatus.PENDING, "Connection unavailable", true)
        assertTrue("Atomic snapshot missing: " + directory.root.listFiles()?.map { it.name },
            directory.root.resolve("candidate-queue.json").exists())
        val restarted = DeliveryStore(directory.root, { time })
        assertTrue(restarted.enabled)
        assertEquals(restarted.warning, event, restarted.find(event.eventId)?.candidate)
        assertEquals(1, restarted.find(event.eventId)?.attempts)
        assertNull(restarted.observe("PRIVATE_RAW_ANDROID_KEY", time, 123, device))
        assertEquals(1, restarted.rows().size)
        val json = event.json()
        assertEquals(setOf("eventId", "notificationId", "sequence", "deviceId", "source", "classification",
            "classifierProfile", "amountMinor", "observedAt", "evidenceQuality"), json.keys().asSequence().toSet())
        assertFalse(directory.root.resolve("candidate-queue.json").readText().contains("PRIVATE_RAW_ANDROID_KEY"))
        assertFalse(json.toString().contains("PRIVATE_RAW_ANDROID_KEY"))
    }
    @Test fun `eligible changed update invalidates original without second candidate`() {
        val event = requireNotNull(store.observe("key", time, 123, device))
        store.uploaded(event.eventId)
        assertNull(store.observe("key", time + 1, 234, device, changed = true))
        assertEquals(1, store.rows().size)
        assertEquals(DeliveryStatus.INVALIDATION_PENDING, store.find(event.eventId)?.status)
        assertTrue(store.find(event.eventId)!!.uploaded)
        assertTrue(store.find(event.eventId)!!.invalidate)
    }
    @Test fun `unsupported new callback after process restart invalidates persisted original`() {
        val event = requireNotNull(store.observe("key", time, 123, device))
        val restarted = DeliveryStore(directory.root, { time })
        restarted.unknownUpdate("key", time + 1)
        assertEquals(DeliveryStatus.INVALIDATION_PENDING, restarted.find(event.eventId)?.status)
        assertFalse(restarted.find(event.eventId)!!.uploaded)
        assertEquals(event, restarted.find(event.eventId)?.candidate)
    }
    @Test fun `supported changed same amount and timestamp after restart invalidates by private fingerprint`() {
        val event = requireNotNull(store.observe("key", time, 123, device, contentDigest = "invented-content-hash-one"))
        val restarted = DeliveryStore(directory.root, { time })
        assertNull(restarted.observe("key", time, 123, device, contentDigest = "invented-content-hash-two"))
        assertEquals(DeliveryStatus.INVALIDATION_PENDING, restarted.find(event.eventId)?.status)
        assertEquals(1, restarted.rows().size)
        assertFalse(directory.root.resolve("candidate-queue.json").readText().contains("invented-content-hash-one"))
    }
    @Test fun `stale and removed unsupported callbacks do not invalidate newer receipt`() {
        val event = requireNotNull(store.observe("key", time, 123, device))
        store.unknownUpdate("key", time - 1)
        assertEquals(DeliveryStatus.PENDING, store.find(event.eventId)?.status)
        store.removed("key", time)
        store.unknownUpdate("key", time)
        assertEquals(DeliveryStatus.PENDING, store.find(event.eventId)?.status)
        assertNull(store.observe("key", time, 123, device))
    }
    @Test fun `key reuse needs removed lifecycle and newer post`() {
        val first = requireNotNull(store.observe("key", time, 123, device))
        store.removed("key", time)
        val second = requireNotNull(store.observe("key", time + 1, 123, device))
        assertNotEquals(first.notificationId, second.notificationId)
        assertNotEquals(first.eventId, second.eventId)
    }
    @Test fun `newer unsupported reused key advances tombstone across restart`() {
        store.observe("key", time, 123, device)
        store.removed("key", time)
        store.unknownUpdate("key", time + 3)
        val restarted = DeliveryStore(directory.root, { time })
        assertNull(restarted.observe("key", time + 2, 123, device))
    }
    @Test fun `invalidation has independent persisted attempts after original retry budget`() {
        val event = requireNotNull(store.observe("key", time, 123, device))
        repeat(MAX_ATTEMPTS) { store.mark(event.eventId, DeliveryStatus.PENDING, "Attempt", true) }
        store.uploaded(event.eventId); store.unknownUpdate("key", time + 1)
        assertEquals(MAX_ATTEMPTS, store.find(event.eventId)?.attempts)
        assertEquals(0, store.find(event.eventId)?.invalidationAttempts)
        store.invalidationAttempt(event.eventId)
        assertEquals(1, DeliveryStore(directory.root, { time }).find(event.eventId)?.invalidationAttempts)
    }
    @Test fun `disable persists cancellation and keeps duplicate tombstones on reenable`() {
        val event = requireNotNull(store.observe("key", time, 123, device))
        store.setEnabled(false)
        val restarted = DeliveryStore(directory.root, { time })
        assertFalse(restarted.enabled)
        assertEquals(DeliveryStatus.FAILED, restarted.find(event.eventId)?.status)
        restarted.setEnabled(true)
        assertNull(restarted.observe("key", time, 123, device))
        restarted.unknownUpdate("key", time + 1)
        assertEquals(DeliveryStatus.FAILED, restarted.find(event.eventId)?.status)
        assertFalse(restarted.find(event.eventId)!!.invalidate)
        assertTrue(restarted.retry().isEmpty())
    }
    @Test fun `age expiration prevents obsolete retries while exact identity stays inspectable`() {
        val event = requireNotNull(store.synthetic(device, 123))
        time += DELIVERY_TTL
        assertEquals(DeliveryStatus.EXPIRED, store.find(event.eventId)?.status)
        assertTrue(store.retry().isEmpty())
        assertEquals(event.eventId, DeliveryStore(directory.root, { time }).rows().single().candidate.eventId)
        assertNull(store.observe("expired", time - DELIVERY_TTL, 123, device))
    }
    @Test fun `queue refuses capacity growth without evicting retry history`() {
        repeat(DELIVERY_CAPACITY) { assertNotNull(store.synthetic(device, 1)) }
        assertNull(store.synthetic(device, 1))
        assertEquals(DELIVERY_CAPACITY, DeliveryStore(directory.root, { time }).rows().size)
        assertTrue(store.warning.contains("capacity"))
    }
    @Test fun `manual retry bounded three times and never retries permanent rejection`() {
        val event = requireNotNull(store.synthetic(device, 123))
        repeat(3) {
            store.mark(event.eventId, DeliveryStatus.FAILED, "Retry limit reached")
            assertEquals(listOf(event.eventId), store.retry())
        }
        store.mark(event.eventId, DeliveryStatus.FAILED, "Retry limit reached")
        assertTrue(store.retry().isEmpty())
        val another = requireNotNull(store.synthetic(device, 123))
        store.mark(another.eventId, DeliveryStatus.FAILED, "Permanent server rejection (HTTP 401)")
        assertTrue(store.retry().isEmpty())
        assertEquals(3, DeliveryStore(directory.root, { time }).find(event.eventId)?.manualRetries)
    }
    @Test fun `synthetic stays explicit and cannot masquerade as TNG provider evidence`() {
        val event = requireNotNull(store.synthetic(device, 123))
        assertEquals("SYNTHETIC", event.source)
        assertEquals("SYNTHETIC", event.evidenceQuality)
        assertEquals("synthetic.debug.v1", event.classifierProfile)
        assertNull(store.synthetic(device, 0)); assertNull(store.synthetic(device, 1_000_001))
        store.setEnabled(false); assertNull(store.synthetic(device, 123))
    }
    @Test fun `write failure latches fail closed persists marker and invokes credential revocation`() {
        var failed = false
        val broken = DeliveryStore(directory.root, { time }, beforeWrite = { throw IOException("invented disk failure") },
            onFailure = { failed = true })
        try { broken.synthetic(device, 123); fail("Expected disk failure") } catch (_: IOException) { }
        assertFalse(broken.healthy); assertFalse(broken.enabled); assertTrue(failed)
        val restarted = DeliveryStore(directory.root, { time })
        assertFalse(restarted.enabled); assertFalse(restarted.healthy)
        assertTrue(restarted.rows().isEmpty())
    }
    @Test fun `clear explicitly resets history and duplicate identity`() {
        val original = requireNotNull(store.observe("key", time, 123, device))
        store.clear(); assertFalse(store.enabled); assertTrue(store.rows().isEmpty())
        store.setEnabled(true)
        assertNotEquals(original.eventId, store.observe("key", time, 123, device)?.eventId)
    }
}
