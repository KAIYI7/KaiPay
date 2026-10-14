package dev.kaipay.notificationprobe

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeliveryTransportTest {
    private fun rejects(url: String, debug: Boolean) {
        try { endpoint(url, debug); fail("Unexpected accepted transport") } catch (_: Exception) { }
    }
    @Test fun `debug USB exception allows only exact loopback port and no URL secrets`() {
        assertEquals("http://127.0.0.1:28082/api/notification-candidates", endpoint("http://127.0.0.1:28082", true).toString())
        listOf("http://localhost:28082", "http://127.0.0.1:80", "http://192.0.2.1:28082", "http://127.0.0.1:28082/path",
            "http://user:password@127.0.0.1:28082", "http://127.0.0.1:28082?token=bad", "http://127.0.0.1:28082#fragment")
            .forEach { rejects(it, true) }
    }
    @Test fun `release forbids even localhost cleartext while HTTPS retains standard URI`() {
        rejects("http://127.0.0.1:28082", false)
        assertEquals("https://example.invalid/api/notification-candidates", endpoint("https://example.invalid/", false).toString())
        listOf("file:///tmp/secret", "ftp://example.invalid", "https://user:password@example.invalid", "https://example.invalid?q=1")
            .forEach { rejects(it, false) }
    }
    @Test fun `merged debug network configuration defaults denied with one exact exception`() {
        var baseDenied = false
        val domains = mutableListOf<String>()
        RuntimeEnvironment.getApplication().resources.getXml(R.xml.network_security_config).use { xml ->
            while (xml.eventType != XmlPullParser.END_DOCUMENT) {
                if (xml.eventType == XmlPullParser.START_TAG) {
                    if (xml.name == "base-config") baseDenied = xml.getAttributeValue(null, "cleartextTrafficPermitted") == "false"
                    if (xml.name == "domain") {
                        assertEquals("false", xml.getAttributeValue(null, "includeSubdomains")); domains += xml.nextText()
                    }
                }
                xml.next()
            }
        }
        assertTrue(baseDenied); assertEquals(listOf("127.0.0.1"), domains)
    }
}
