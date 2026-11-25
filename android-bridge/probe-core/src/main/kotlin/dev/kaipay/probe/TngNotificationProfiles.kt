package dev.kaipay.probe

/** English receipt structures observed locally; real identities and amounts are never retained as fixtures. */
object TngNotificationProfiles {
    private const val receivedTitle = "You\u2019ve received money!"
    private val receiptBody = Regex(
        "[\\p{L}\\p{M}][\\p{L}\\p{M} .'\u2019-]{0,127} has transferred " +
            "RM ?[0-9][0-9,]*(?:\\.[0-9]+)? to you\\. " +
            "Tap here to check the transaction details\\."
    )
    private val observedV1 = IncomingRule("tng.receipt.en.observed.v1") { fields, lines ->
        val body = fields["text"]
        fields["title"] == receivedTitle && body != null && receiptBody.matches(body) &&
            (fields["bigText"] == null || fields["bigText"] == body) &&
            fields.all { (name, value) -> name in setOf("title", "text", "bigText") || value.isBlank() } &&
            lines.all { it.isBlank() }
    }

    // Second wording, from one owner-reported TNG-to-TNG receipt. The note is free text set by the
    // sender or by TNG. It can only make a match fail: every classifier filter still reads it.
    // The name class adds only "/" and "@" (for example "A/L", "A/P" and aliases) to the first rule's.
    // The note starts with a visible character (explicit \p{Z}: the JVM and Android disagree on \s)
    // and excludes control and format characters such as bidi overrides, except the emoji joiner U+200D
    // after the first character.
    internal const val KACHING_TITLE = "Ka-ching! Incoming Money."
    internal const val KACHING_RULE_ID = "tng.receipt.kaching.en.observed.v1"
    private val kachingBody = Regex(
        "(?:RM|MYR) ?[0-9][0-9,]*(?:\\.[0-9]+)? received from " +
            "[\\p{L}\\p{M}][\\p{L}\\p{M} .'\u2019/@-]{0,127} for " +
            "(?:[^\\p{Cc}\\p{Cf}\\p{Z}\\s])(?:[^\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]|\u200D){0,255}"
    )
    private val kachingV1 = IncomingRule(KACHING_RULE_ID) { fields, lines ->
        val body = fields["text"]
        fields["title"] == KACHING_TITLE && body != null && kachingBody.matches(body) &&
            (fields["bigText"] == null || fields["bigText"] == body) &&
            fields.all { (name, value) -> name in setOf("title", "text", "bigText") || value.isBlank() } &&
            lines.all { it.isBlank() }
    }

    internal const val RECEIVED_TITLE = receivedTitle
    internal val ruleIds = setOf(observedV1.id, kachingV1.id)

    /** Only `tng.receipt.en.observed.v1` is delivered; the second rule is display and report only. */
    val current = ClassificationProfile(listOf(observedV1, kachingV1))
}
