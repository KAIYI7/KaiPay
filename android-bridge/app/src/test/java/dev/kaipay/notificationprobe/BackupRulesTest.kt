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
class BackupRulesTest {
    @Test fun `cloud and device transfer exclude all supported private data domains`() {
        val domains = setOf("root", "file", "database", "sharedpref", "external",
            "device_root", "device_file", "device_database", "device_sharedpref")
        val rules = mutableMapOf<String, MutableSet<String>>()
        var section: String? = null
        RuntimeEnvironment.getApplication().resources.getXml(R.xml.data_extraction_rules).use { xml ->
            while (xml.eventType != XmlPullParser.END_DOCUMENT) {
                if (xml.eventType == XmlPullParser.START_TAG) {
                    when (xml.name) {
                        "cloud-backup", "device-transfer" -> {
                            section = xml.name
                            assertNull(rules.put(xml.name, mutableSetOf()))
                        }
                        "exclude" -> {
                            assertNotNull(section)
                            assertEquals(".", xml.getAttributeValue(null, "path"))
                            assertTrue(rules.getValue(checkNotNull(section)).add(xml.getAttributeValue(null, "domain")))
                        }
                        "include" -> fail("Probe data must not be eligible for backup")
                    }
                } else if (xml.eventType == XmlPullParser.END_TAG && xml.name == section) {
                    section = null
                }
                xml.next()
            }
        }
        assertEquals(setOf("cloud-backup", "device-transfer"), rules.keys)
        rules.values.forEach { assertEquals(domains, it) }
    }
}
