package dev.kaipay.notificationprobe

import android.content.pm.PackageManager
import android.net.Uri
import android.os.Binder
import android.os.Process
import dev.kaipay.probe.Fields
import dev.kaipay.probe.ProbeParser
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation
import org.robolectric.shadows.ShadowBinder
import org.robolectric.shadows.ShadowContextImpl

// Robolectric 4.17's default checkCallingPermission uses myPid/myUid, ignoring ShadowBinder.
// Model the Android Binder caller explicitly while retaining real enforceCallingPermission.
@Implements(className = "android.app.ContextImpl")
class BinderPermissionContext : ShadowContextImpl() {
    @Implementation
    override fun checkCallingPermission(permission: String): Int =
        if (Binder.getCallingPid() == Process.myPid()) PackageManager.PERMISSION_DENIED
        else checkPermission(permission, Binder.getCallingPid(), Binder.getCallingUid())
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [BinderPermissionContext::class])
@ConscryptMode(ConscryptMode.Mode.OFF)
class DebugSnapshotProviderTest {
    private lateinit var provider: DebugSnapshotProvider
    private val app get() = RuntimeEnvironment.getApplication()
    private val uri get() = DebugSnapshotProvider.SNAPSHOT_URI

    @Before fun setup() {
        ProbeRuntime.disable()
        ProbeRuntime.session.setConnected(true)
        grantAccess(app)
        ProbeRuntime.session.setEnabled(true)
        provider = Robolectric.buildContentProvider(DebugSnapshotProvider::class.java)
            .create(uri.authority!!).get()
        shell(true)
    }
    @After fun cleanup() { ProbeRuntime.disable(); ShadowBinder.reset() }
    private fun shell(permission: Boolean) {
        ShadowBinder.setCallingPid(4321) // ADB is a separate process; enforceCallingPermission rejects own-process calls.
        ShadowBinder.setCallingUid(Process.SHELL_UID)
        val shadow = shadowOf(app)
        if (permission) shadow.grantPermissions(Binder.getCallingPid(), Process.SHELL_UID, "android.permission.DUMP")
        else shadow.denyPermissions(Binder.getCallingPid(), Process.SHELL_UID, "android.permission.DUMP")
    }
    private fun snapshot(): JSONObject = provider.query(uri, null, null, null, null).use {
        assertTrue(it.moveToFirst()); JSONObject(it.getString(0))
    }
    private fun post(text: String = SYNTHETIC_PRIVATE) {
        ProbeRuntime.session.posted(ProbeParser.OFFICIAL_PACKAGE, "PRIVATE_SYNTHETIC_KEY", 100) { Fields(text = text) }
    }
    private fun denied(block: () -> Unit) {
        try { block(); fail("Expected authorization failure") } catch (_: SecurityException) { }
    }

    @Test fun `non shell caller cannot read even with DUMP`() {
        post()
        ShadowBinder.setCallingUid(12345)
        shadowOf(app).grantPermissions(Binder.getCallingPid(), 12345, "android.permission.DUMP")
        denied { snapshot() }
    }
    @Test fun `shell without DUMP cannot read`() { post(); shell(false); denied { snapshot() } }
    @Test fun `own process call cannot impersonate an external shell permission check`() {
        post(); ShadowBinder.setCallingPid(Process.myPid())
        shadowOf(app).grantPermissions(Process.myPid(), Process.SHELL_UID, "android.permission.DUMP")
        denied { snapshot() }
    }
    @Test fun `authorized snapshot escapes permitted text without raw notification key`() {
        val text = "Synthetic quoted \"receipt\"\nwith newline"
        post(text)
        val payload = snapshot()
        assertEquals(text, payload.getJSONArray("events").getJSONObject(0).getJSONObject("fields").getString("text"))
        assertFalse(payload.toString().contains("PRIVATE_SYNTHETIC_KEY"))
    }
    @Test fun `disabled snapshot exposes no contents`() {
        post(); ProbeRuntime.disable()
        assertEquals(0, snapshot().getJSONArray("events").length())
    }
    @Test fun `export is bounded and omits whole events without truncating inspected fields`() {
        repeat(32) { index ->
            ProbeRuntime.session.posted(ProbeParser.OFFICIAL_PACKAGE, "SYNTHETIC_KEY_$index", index.toLong()) {
                Fields(text = "\u0001".repeat(4096), bigText = "\u0001".repeat(4096))
            }
        }
        val payload = snapshot()
        val events = payload.getJSONArray("events")
        assertTrue(payload.toString().toByteArray(Charsets.UTF_8).size < 128_000)
        assertTrue(events.length() in 1..4)
        assertEquals(32 - events.length(), payload.getInt("omittedEvents"))
        repeat(events.length()) { index ->
            assertEquals(4096, events.getJSONObject(index).getJSONObject("fields").getString("text").length)
        }
    }
    @Test fun `revocation clears snapshot and requires explicit enable after regrant`() {
        post(); grantAccess(app, false)
        assertEquals(0, snapshot().getJSONArray("events").length())
        assertTrue(ProbeRuntime.session.snapshot().events.isEmpty())
        grantAccess(app)
        assertFalse(snapshot().getBoolean("enabled"))
    }
    @Test fun `credential blocked snapshot contains no content`() {
        post("Synthetic OTP 123456")
        val payload = snapshot()
        assertEquals("SENSITIVE_BLOCKED", payload.getJSONArray("events").getJSONObject(0).getString("disposition"))
        assertFalse(payload.toString().contains("123456"))
        assertFalse(payload.toString().contains("Synthetic OTP"))
    }
    @Test fun `provider cannot write call or open files`() {
        listOf<() -> Unit>( { provider.insert(uri, null) }, { provider.update(uri, null, null, null) },
            { provider.delete(uri, null, null) }, { provider.call("read", null, null) },
            { provider.openFile(uri, "r") }).forEach {
            try { it(); fail("Read-only provider accepted mutation or file operation") }
            catch (_: UnsupportedOperationException) { }
        }
    }
    @Test fun `unexpected route and selection rejected`() {
        listOf<() -> Unit>( { provider.query(Uri.parse("content://${uri.authority}/other"), null, null, null, null) },
            { provider.query(uri, null, "anything", null, null) }).forEach {
            try { it(); fail("Unsupported query accepted") } catch (_: IllegalArgumentException) { }
        }
    }
    @Suppress("DEPRECATION")
    @Test fun `debug merged manifest limits snapshot access and grants no URIs`() {
        val info = app.packageManager.resolveContentProvider(uri.authority!!, PackageManager.GET_META_DATA)!!
        assertTrue(info.exported)
        assertEquals("android.permission.DUMP", info.readPermission)
        assertEquals("android.permission.DUMP", info.writePermission)
        assertFalse(info.grantUriPermissions)
    }
}
