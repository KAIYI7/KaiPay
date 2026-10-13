package dev.kaipay.probe

import java.math.BigDecimal

/** No production payment interpretation. The supplier is never read for another package. */
object ProbeParser {
    const val OFFICIAL_PACKAGE = "my.com.tngdigital.ewallet"
    private const val MAX_FIELD = 4096
    private val credentialMarker = Regex(
        "otp|password|passwd|passcode|pin|credential|verification|security|authentication|" +
            "one.?time|kata.?laluan|kod.?pengesahan|验证码|密码", RegexOption.IGNORE_CASE)

    fun inspect(packageName: String?, fields: () -> Fields): Diagnostic? {
        if (packageName != OFFICIAL_PACKAGE) return null
        val input = fields()
        val values = listOf(input.title, input.text, input.bigText)
        if (values.any { it != null && it !is String }) return Diagnostic(Disposition.MALFORMED)
        val strings = values.filterIsInstance<String>()
        if (strings.any { it.length > MAX_FIELD }) return Diagnostic(Disposition.MALFORMED)
        if (strings.any { credentialMarker.containsMatchIn(it) }) return Diagnostic(Disposition.SENSITIVE_BLOCKED)
        // Only presence survives; never retain content, lengths, amounts, sender, tags or references.
        return Diagnostic(Disposition.UNKNOWN, input.title != null, input.text != null, input.bigText != null)
    }

    /** Deliberately invented test protocol. Never called by the Android listener. */
    fun parseSynthetic(text: String?): Long? {
        if (text == null || !Regex("SYNTHETIC_INCOMING MYR [0-9]{1,6}\\.[0-9]{2}").matches(text)) return null
        return BigDecimal(text.removePrefix("SYNTHETIC_INCOMING MYR ")).movePointRight(2)
            .longValueExact().takeIf { it > 0 }
    }
}

/** Temporary function input only; never placed in the diagnostic store or logs. */
class Fields(val title: Any? = null, val text: Any? = null, val bigText: Any? = null)
enum class Disposition { UNKNOWN, MALFORMED, SENSITIVE_BLOCKED }
data class Diagnostic(
    val disposition: Disposition,
    val titlePresent: Boolean = false,
    val textPresent: Boolean = false,
    val bigTextPresent: Boolean = false
)
