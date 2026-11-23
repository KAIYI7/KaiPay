package dev.kaipay.probe

import java.math.BigDecimal

/** Package filtering precedes supplier access; content lives only in the bounded local session. */
object ProbeParser {
    const val OFFICIAL_PACKAGE = "my.com.tngdigital.ewallet"
    private const val MAX_FIELD = 4096
    private const val MAX_TOTAL = 16384
    private const val MAX_LINES = 16
    private val credentialMarker = Regex(
        "otp|tac|mfa|2fa|password|passwd|passcode|pin|credentials?|verification|security|authentication|" +
            "one[ -]?time|kata[ -]?laluan|kod[ -]?pengesahan|验证码|密码", RegexOption.IGNORE_CASE)
    private val possibleCredentialDigits = Regex("(?<![\\p{Nd}])[\\p{Nd}]{4,8}(?![\\p{Nd}])")

    fun inspect(packageName: String?, fields: () -> Fields): Diagnostic? =
        inspectContent(packageName, fields)?.diagnostic

    fun inspectContent(packageName: String?, fields: () -> Fields, groupSummary: Boolean = false,
        profile: ClassificationProfile = ClassificationProfile.EMPTY): ContentInspection? {
        if (packageName != OFFICIAL_PACKAGE) return null
        val input = try { fields() } catch (_: RuntimeException) { return blocked(Disposition.MALFORMED) }
        val named = linkedMapOf("title" to input.title, "text" to input.text, "bigText" to input.bigText,
            "subText" to input.subText, "summaryText" to input.summaryText)
        if (named.values.any { it != null && it !is String }) return blocked(Disposition.MALFORMED)
        val lines: List<*> = when (val value = input.textLines) {
            null -> emptyList<Any>()
            is Array<*> -> {
                if (value.size > MAX_LINES) return blocked(Disposition.MALFORMED)
                value.toList()
            }
            is List<*> -> {
                if (value.size > MAX_LINES) return blocked(Disposition.MALFORMED)
                value.toList()
            }
            else -> return blocked(Disposition.MALFORMED)
        }
        if (lines.size > MAX_LINES || lines.any { it !is String }) return blocked(Disposition.MALFORMED)
        val strings = named.values.filterIsInstance<String>() + lines.filterIsInstance<String>()
        if (strings.any { it.length > MAX_FIELD } || strings.sumOf { it.length } > MAX_TOTAL)
            return blocked(Disposition.MALFORMED)
        // Scan complete bounded fields, never a truncated prefix. Monetary spans are not PINs.
        if (strings.any { text ->
                credentialMarker.containsMatchIn(text) || possibleCredentialDigits.findAll(text).any { digits ->
                    MonetaryParser.validMyrSpans(text).none { span ->
                        digits.range.first >= span.first && digits.range.last <= span.last
                    }
                }
            }) return blocked(Disposition.SENSITIVE_BLOCKED)
        val retained = named.mapNotNull { (key, value) -> (value as? String)?.let { key to it } }.toMap()
        val textLines = lines.filterIsInstance<String>().toList()
        return ContentInspection(Diagnostic(Disposition.UNKNOWN, input.title != null, input.text != null,
            input.bigText != null), retained, textLines,
            NotificationClassifier.classify(retained, textLines, groupSummary, profile))
    }

    private fun blocked(disposition: Disposition) = ContentInspection(Diagnostic(disposition), emptyMap(),
        emptyList(), Classification(ClassificationOutcome.UNKNOWN, explanation = when (disposition) {
            Disposition.MALFORMED -> "Notification fields are malformed or exceed local limits."
            else -> "Potential credential content was suppressed."
        }))

    /** Deliberately invented test protocol. Never called by the Android listener. */
    fun parseSynthetic(text: String?): Long? {
        if (text == null || !Regex("SYNTHETIC_INCOMING MYR [0-9]{1,6}\\.[0-9]{2}").matches(text)) return null
        return BigDecimal(text.removePrefix("SYNTHETIC_INCOMING MYR ")).movePointRight(2)
            .longValueExact().takeIf { it > 0 }
    }
}

/** Android adapters convert supported CharSequences to bounded Strings. Never log this input. */
class Fields(val title: Any? = null, val text: Any? = null, val bigText: Any? = null,
    val subText: Any? = null, val summaryText: Any? = null, val textLines: Any? = null)

class ContentInspection(val diagnostic: Diagnostic, val fields: Map<String, String>,
    val textLines: List<String>, val classification: Classification) {
    override fun toString() = "ContentInspection(diagnostic=$diagnostic, classification=$classification)"
}
enum class Disposition { UNKNOWN, MALFORMED, SENSITIVE_BLOCKED }
data class Diagnostic(
    val disposition: Disposition,
    val titlePresent: Boolean = false,
    val textPresent: Boolean = false,
    val bigTextPresent: Boolean = false
)
