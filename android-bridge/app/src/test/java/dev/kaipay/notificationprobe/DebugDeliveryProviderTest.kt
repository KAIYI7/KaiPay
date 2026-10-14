package dev.kaipay.notificationprobe

import android.os.Binder
import android.os.Process
import android.os.Bundle
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.shadows.ShadowBinder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [BinderPermissionContext::class])
@ConscryptMode(ConscryptMode.Mode.OFF)
class DebugDeliveryProviderTest {
    private lateinit var provider: DebugDeliveryProvider
    @Before fun setup() {
        provider = Robolectric.buildContentProvider(DebugDeliveryProvider::class.java)
            .create("dev.kaipay.notificationprobe.delivery").get()
        ShadowBinder.setCallingPid(4321); ShadowBinder.setCallingUid(Process.SHELL_UID)
    }
    @After fun cleanup() { ShadowBinder.reset() }
    private fun denied(block: () -> Unit) {
        try { block(); fail("Expected authorization rejection") } catch (_: SecurityException) { }
    }
    @Test fun `ordinary application with DUMP cannot provision or trigger delivery`() {
        ShadowBinder.setCallingUid(12345)
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Binder.getCallingPid(), 12345, "android.permission.DUMP")
        denied { provider.call("provision", "{}", null) }
        denied { provider.call("synthetic", "123", null) }
        denied { provider.call("events", null, null) }
    }
    @Test fun `shell without DUMP cannot inspect delivery`() {
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Binder.getCallingPid(), Process.SHELL_UID, "android.permission.DUMP")
        denied { provider.call("status", null, null) }
    }
    @Test fun `own process cannot use a spoofed shell uid permission grant`() {
        ShadowBinder.setCallingPid(Process.myPid())
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Process.myPid(), Process.SHELL_UID, "android.permission.DUMP")
        denied { provider.call("enable", null, null) }
    }
    @Test fun `authorized shell status never exposes raw credentials or observations`() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Binder.getCallingPid(), Process.SHELL_UID, "android.permission.DUMP")
        val result = provider.call("status", null, null).getString("status")!!
        assertFalse(result.contains("Bearer")); assertFalse(result.contains("sourceAlias")); assertFalse(result.contains("textLines"))
        assertFalse(provider.call("enable", null, null).getBoolean("enabled"))
    }
    @Test fun `malformed provisioning errors never echo sensitive argument content`() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Binder.getCallingPid(), Process.SHELL_UID, "android.permission.DUMP")
        listOf("{invented-secret", "{\"token\":\"invented-secret\",\"deviceId\":{},\"baseUrl\":5}").forEach {
            assertEquals("Invalid delivery configuration or argument", provider.call("provision", it, null).getString("error"))
        }
    }
    @Test fun `authorized actual content CLI empty extras bundle permits status and synthetic gate`() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Binder.getCallingPid(), Process.SHELL_UID, "android.permission.DUMP")
        assertNotNull(provider.call("status", null, Bundle()).getString("status"))
        assertFalse(provider.call("synthetic", "123", Bundle()).getBoolean("queued"))
        assertEquals("Invalid delivery configuration or argument", provider.call("provision", "{invented-secret", Bundle()).getString("error"))
    }
    @Test fun `nonempty extras bundle is rejected without echoing its contents`() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Binder.getCallingPid(), Process.SHELL_UID, "android.permission.DUMP")
        val extras = Bundle().apply { putString("unexpected", "invented-sensitive-value") }
        val response = provider.call("status", null, extras)
        assertEquals("Invalid delivery configuration or argument", response.getString("error"))
        assertFalse(response.toString().contains("invented-sensitive-value"))
    }
}
