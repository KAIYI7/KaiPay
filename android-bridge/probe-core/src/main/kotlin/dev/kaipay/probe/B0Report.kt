package dev.kaipay.probe

/**
 * One redacted B0 short-code line for the newest inspected observation.
 *
 * The line is assembled only from constants in this file and in [TngNotificationProfiles]: codes,
 * field names, the known titles and templates, a fixed vocabulary, fixed punctuation and placeholders.
 * No substring of a notification is ever appended, so no name, amount, note, reference, digit,
 * alias or time can reach the clipboard.
 */
object B0Report {
    data class Line(val text: String, val index: Int, val total: Int)

    internal const val SKELETON_LIMIT = 200
    internal const val WORDING_LIMIT = 400

    /** Labels that start a free-text part; with "for" they open the note slot, in any letter case. */
    private val noteLabels = setOf("for", "note", "notes", "remark", "remarks", "message", "ref", "reference", "description")

    /** Generic receipt words kept by the skeleton. Mixed or lower case only, never a single letter. */
    internal val vocabulary = setOf(
        "You’ve", "You've", "You", "you", "your", "received", "Received", "money", "Money", "has", "have",
        "transferred", "to", "Tap", "here", "check", "the", "transaction", "details", "Ka-ching", "Incoming",
        "from", "for", "via", "payment", "Payment", "transfer", "Transfer", "fund", "Fund", "successful",
        "Successful", "successfully", "wallet", "eWallet", "account", "DuitNow", "reference", "Reference",
        "sent", "is", "of", "on", "in", "and", "with", "by"
    )
    private val keptPunctuation = listOf(".", ",", "!", "?", ":", "'", "’", "-")

    private val explanations = mapOf(
        "No usable notification text is available." to "EMPTY",
        "An unsupported currency is present." to "MONEY",
        "An unsupported currency code is present." to "MONEY",
        "An unsupported currency suffix is present." to "MONEY",
        "Unsupported monetary formatting is present." to "MONEY",
        "Currency marker has no valid amount." to "MONEY",
        "A monetary token is split or followed by another numeric value." to "MONEY",
        "Monetary value is malformed, non-positive, or out of range." to "MONEY",
        "Conflicting monetary values are present." to "MONEY",
        "Grouped summary cannot identify an individual receipt." to "GROUP",
        "Incoming and outgoing indicators conflict." to "CONFLICT",
        "Outgoing transaction indicators are present." to "OUTGOING",
        "Informational, promotional, request, or non-receipt indicators are present." to "FILTER",
        "Multiple incoming rules match." to "MULTI",
        "Incoming wording matches but an exact supported amount is absent." to "NOAMT",
        "A configured notification rule matches; independent transaction verification is required." to "RULE",
        "No reviewed incoming notification rule matches; amount alone is insufficient." to "NORULE",
        "Notification fields are malformed or exceed local limits." to "MALFORMED",
        "Potential credential content was suppressed." to "SENSITIVE"
    )
    internal fun why(explanation: String): String = explanations[explanation] ?: "OTHER"

    // Report templates: the rules' fixed words, with name and note loosened to any single-line text.
    private val receivedTemplate = Regex(
        "(.+?) has transferred (RM|MYR)( ?)(\\S+) to you\\. Tap here to check the transaction details\\.")
    private val kachingTemplate = Regex("(RM|MYR)( ?)(\\S+) received from (.+?) for (.+)")

    private class Wording(val text: String, val name: String? = null, val note: String? = null, val exact: Boolean = false,
        val endSlot: Slot = Slot.NONE)

    fun line(snapshot: Snapshot, versionCode: Int): Line {
        val total = snapshot.events.size
        val found = snapshot.events.withIndex().lastOrNull { it.value.inspection != null }
        val parts = mutableListOf("B0", "v$versionCode", "r=?", "f=?")
        if (found == null) {
            parts += listOf("o=M", "n=0/$total", "rem=${snapshot.removals}",
                "det=${yn(snapshot.enabled)}", "lis=${yn(snapshot.connected)}")
            return Line(parts.joinToString(" "), 0, total)
        }
        val inspection = found.value.inspection!!
        val result = inspection.classification
        parts += "o=" + when (result.outcome) {
            ClassificationOutcome.INCOMING_CANDIDATE -> "C"
            ClassificationOutcome.UNKNOWN -> "U"
            ClassificationOutcome.AMBIGUOUS -> "A"
            ClassificationOutcome.OUTGOING_OR_UNRELATED -> "X"
        }
        when (inspection.diagnostic.disposition) {
            Disposition.SENSITIVE_BLOCKED -> parts += "d=SB"
            Disposition.MALFORMED -> parts += "d=MF"
            Disposition.UNKNOWN -> Unit
        }
        parts += "e=" + when (result.confidence) {
            EvidenceQuality.NONE -> "N"
            EvidenceQuality.AMOUNT_ONLY -> "AO"
            EvidenceQuality.RULE_MATCHED -> "RM"
        }
        parts += "rule=" + when (val id = result.ruleId) {
            null -> "-"
            in TngNotificationProfiles.ruleIds -> TngNotificationProfiles.ruleIds.single { it == id }
            else -> "other"
        }
        parts += "amt=" + yn(result.amountMinor != null)
        parts += "why=" + why(result.explanation)
        if (inspection.diagnostic.disposition == Disposition.UNKNOWN) {
            val fields = inspection.fields
            parts += "t" + title(fields["title"])
            val body = fields["text"]?.let { wording(it) }
            parts += "b" + (body?.let { describe(it) } ?: "=absent")
            parts += "bt" + when (val expanded = fields["bigText"]) {
                null -> "=absent"
                fields["text"] -> "=same"
                else -> describe(wording(expanded))
            }
            val extra = listOfNotNull("subText".takeIf { fields["subText"]?.isNotBlank() == true },
                "summaryText".takeIf { fields["summaryText"]?.isNotBlank() == true },
                "lines".takeIf { inspection.textLines.any { it.isNotBlank() } })
            parts += "x=" + (extra.joinToString(",").ifEmpty { "-" })
            body?.name?.let { parts += "name=" + nameFlags(it) }
            body?.note?.let { parts += "note=" + scripts(it) }
        }
        parts += "n=${found.index + 1}/$total"
        parts += "rem=${snapshot.removals}"
        return Line(parts.joinToString(" "), found.index + 1, total)
    }

    private fun yn(value: Boolean) = if (value) "Y" else "N"

    private fun title(value: String?): String = when (value) {
        null -> "=absent"
        TngNotificationProfiles.RECEIVED_TITLE -> "=\"" + TngNotificationProfiles.RECEIVED_TITLE + "\""
        TngNotificationProfiles.KACHING_TITLE -> "=\"" + TngNotificationProfiles.KACHING_TITLE + "\""
        else -> "~\"" + skeleton(value) + "\""
    }

    private fun describe(wording: Wording): String =
        if (wording.exact) "=\"" + wording.text + "\"" else "~\"" + wording.text + "\""

    private fun currency(code: String, space: String) = (if (code == "MYR") "MYR" else "RM") + (if (space.isEmpty()) "" else " ")

    /**
     * Each non-blank line is matched on its own, so a known wording followed by another line keeps its
     * placeholders. A note continues onto later lines: after a note, every later line is redacted.
     */
    private fun wording(text: String): Wording {
        val segments = mutableListOf(StringBuilder())
        text.forEach { c -> if (isLineBreak(c)) segments += StringBuilder() else segments.last().append(c) }
        if (segments.size == 1) return oneLine(text, Slot.NONE)
        var slot = Slot.NONE
        val described = segments.map { it.toString() }.filter { it.isNotBlank() }.map { segment ->
            oneLine(segment, slot).also { slot = if (it.endSlot == Slot.NOTE) Slot.NOTE else Slot.NONE }
        }
        val first = described.firstOrNull { it.exact }
        val joined = described.joinToString("[nl]") { it.text }
        val bounded = if (joined.length > WORDING_LIMIT) joined.take(WORDING_LIMIT) + Char(0x2026) else joined
        return Wording(bounded, first?.name, first?.note, exact = false)
    }

    private fun oneLine(text: String, start: Slot): Wording {
        if (start == Slot.NOTE) return Wording(skeletonFrom(text, Slot.NOTE).first, endSlot = Slot.NOTE)
        receivedTemplate.matchEntire(text)?.let { m ->
            return Wording("[NAME] has transferred " + currency(m.groupValues[2], m.groupValues[3]) +
                "[AMOUNT] to you. Tap here to check the transaction details.", name = m.groupValues[1], exact = true)
        }
        kachingTemplate.matchEntire(text)?.let { m ->
            return Wording(currency(m.groupValues[1], m.groupValues[2]) + "[AMOUNT] received from [NAME] for [NOTE]",
                name = m.groupValues[4], note = m.groupValues[5], exact = true, endSlot = Slot.NOTE)
        }
        return skeletonFrom(text, Slot.NONE).let { (shape, end) -> Wording(shape, endSlot = end) }
    }

    internal fun nameFlags(name: String): String {
        val cased = name.filter { it.isUpperCase() || it.isLowerCase() }
        val case = when {
            cased.isEmpty() -> "uncased"
            cased.all { it.isUpperCase() } -> "UPPER"
            cased.all { it.isLowerCase() } -> "lower"
            else -> "Mixed"
        }
        val marks = linkedSetOf<String>()
        name.forEach { c ->
            when {
                c.isLetter() || Character.getType(c).let {
                    it == Character.NON_SPACING_MARK.toInt() || it == Character.COMBINING_SPACING_MARK.toInt() ||
                        it == Character.ENCLOSING_MARK.toInt()
                } || c == ' ' -> Unit
                c == '.' -> marks += "."
                c == '\'' -> marks += "'"
                c == '’' -> marks += "’"
                c == '-' -> marks += "-"
                c == '/' -> marks += "/"
                c == '@' -> marks += "@"
                c.isDigit() -> marks += "0"
                else -> marks += "*"
            }
        }
        return (listOf(case, scripts(name, digits = false)) + marks).joinToString(",")
    }

    internal fun scripts(text: String, digits: Boolean = true): String {
        val found = linkedSetOf<String>()
        var index = 0
        while (index < text.length) {
            val cp = text.codePointAt(index)
            if (Character.isLetter(cp)) found += when (Character.UnicodeScript.of(cp)) {
                Character.UnicodeScript.LATIN -> "Latin"
                Character.UnicodeScript.HAN -> "Han"
                else -> "Other"
            }
            index += Character.charCount(cp)
        }
        val ordered = listOf("Latin", "Han", "Other").filter { it in found }.ifEmpty { listOf("none") }.joinToString("+")
        return if (digits && text.any { it.isDigit() }) "$ordered+0" else ordered
    }

    private fun isWordChar(c: Char) = c.isLetterOrDigit() || Character.getType(c).let {
        it == Character.NON_SPACING_MARK.toInt() || it == Character.COMBINING_SPACING_MARK.toInt() ||
            it == Character.ENCLOSING_MARK.toInt() || it == Character.SURROGATE.toInt()
    }
    private fun isLineBreak(c: Char) = c == '\n' || c == '\r' || c == Char(0x2028) || c == Char(0x2029) || c == '\u0085'

    private enum class Slot { NONE, NAME, NOTE }

    internal fun skeleton(text: String): String = skeletonFrom(text, Slot.NONE).first

    /**
     * Keeps vocabulary words and fixed punctuation; every other run collapses to one "[?]".
     * Words after "from" or "by" (any case) up to a note label are a likely name; everything after "for" or a
     * label such as "Note", "Remark", "Message" or "Ref" (any case) is a likely note, also on later lines.
     * Both are redacted even when they are vocabulary words. Returns the shape and the slot at its end.
     */
    private fun skeletonFrom(text: String, start: Slot): Pair<String, Slot> {
        val amounts = MonetaryParser.validMyrSpans(text)
        val out = mutableListOf<String>()
        var redactedEnd = -1 // out.size just after the last "[?]", while only spaces/punctuation follow it.
        fun redact() {
            if (redactedEnd >= 0 && redactedEnd <= out.size) {
                while (out.size > redactedEnd) out.removeAt(out.size - 1)
            } else {
                out += "[?]"; redactedEnd = out.size; return
            }
            redactedEnd = out.size
        }
        fun keep(token: String) { out += token; redactedEnd = -1 }
        fun separator(token: String) { out += token }
        var slot = start
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val amount = amounts.firstOrNull { it.first == i }
            when {
                amount != null -> {
                    val raw = text.substring(amount.first, amount.last + 1)
                    val code = if (raw.startsWith("MYR", ignoreCase = true)) "MYR" else "RM"
                    val spaced = raw.length > code.length && raw[code.length].isWhitespace()
                    keep(code + (if (spaced) " " else "") + "[AMOUNT]")
                    if (raw.endsWith(".")) separator(".")
                    i = amount.last + 1
                }
                isWordChar(c) -> {
                    var end = i
                    while (end < text.length && (isWordChar(text[end]) ||
                            (text[end] in "'’-" && end + 1 < text.length && isWordChar(text[end + 1]) && end > i))) end++
                    val token = text.substring(i, end)
                    val kept = vocabulary.firstOrNull { it == token }
                    val lower = token.lowercase()
                    when {
                        slot == Slot.NOTE -> redact()
                        lower in noteLabels -> { if (kept != null) keep(kept) else redact(); slot = Slot.NOTE }
                        slot == Slot.NAME -> redact()
                        lower == "from" || lower == "by" -> { if (kept != null) keep(kept) else redact(); slot = Slot.NAME }
                        kept != null -> keep(kept)
                        else -> redact()
                    }
                    i = end
                }
                c.isWhitespace() || c == Char(0xA0) || isLineBreak(c) -> {
                    var end = i
                    var lineBreak = false
                    while (end < text.length && (text[end].isWhitespace() || text[end] == Char(0xA0) || isLineBreak(text[end]))) {
                        if (isLineBreak(text[end])) lineBreak = true
                        end++
                    }
                    if (lineBreak) { keep("[nl]"); if (slot == Slot.NAME) slot = Slot.NONE } else separator(" ")
                    i = end
                }
                else -> {
                    separator(keptPunctuation.firstOrNull { it == c.toString() } ?: "[p]")
                    i++
                }
            }
        }
        val joined = out.joinToString("")
        return (if (joined.length > SKELETON_LIMIT) joined.take(SKELETON_LIMIT) + Char(0x2026) else joined) to slot
    }
}
