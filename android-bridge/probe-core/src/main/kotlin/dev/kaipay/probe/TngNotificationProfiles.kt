package dev.kaipay.probe

/** English receipt structures observed locally; real identities and amounts are never retained as fixtures. */
object TngNotificationProfiles {
    private const val receivedTitle = "You\u2019ve received money!"
    private val receiptBody = Regex(
        "[\\p{L}\\p{M}][\\p{L}\\p{M} .'\u2019-]{0,127} has transferred " +
            "RM ?[0-9][0-9,]*(?:\\.[0-9]+)? to you\\. " +
            "Tap here to check the transaction details\\."
    )
    const val V1_RULE_ID = "tng.receipt.en.observed.v1"
    private val observedV1 = IncomingRule(V1_RULE_ID) { fields, lines ->
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
    const val KACHING_RULE_ID = "tng.receipt.kaching.en.observed.v1"
    private val kachingBody = Regex(
        "(?:RM|MYR) ?[0-9][0-9,]*(?:\\.[0-9]+)? received from " +
            "[\\p{L}\\p{M}][\\p{L}\\p{M} .'\u2019/@-]{0,127} for " +
            "(?:[^\\p{Cc}\\p{Cf}\\p{Z}\\s])(?:[^\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]|\u200D){0,255}"
    )
    // Rule-local reject: refund or reversal wording anywhere in this rule's text, in case TNG uses
    // this frame for money coming back. English adds only inflections the shared filter misses
    // ("refund" and "reversed" are already filtered); Chinese is matched as a substring. Fails safe.
    private val kachingReject = Regex(
        // Explicit ASCII word edges and an explicit space class: the JVM's \b and \s are ASCII-only while
        // Android's ICU treats Han as word characters, so "资金refunds" must be rejected on both.
        "(?<![A-Za-z0-9_])(?:reversals?|refunded|refunds|refunding|charge[- ]?backs?|" +
            "bayaran[\\s\\p{Z}]+balik|pembalikan)(?![A-Za-z0-9_])|" +
            // refund, return (simplified, traditional), send back, reversal (simplified, traditional)
            "退款|退还|退還|退回|冲正|沖正|" +
            // revoke (simplified, traditional), withdraw
            "撤销|撤銷|撤回",
        RegexOption.IGNORE_CASE
    )
    private val kachingV1 = IncomingRule(KACHING_RULE_ID) { fields, lines ->
        val body = fields["text"]
        fields["title"] == KACHING_TITLE && body != null && kachingBody.matches(body) &&
            !kachingReject.containsMatchIn(body) &&
            (fields["bigText"] == null || fields["bigText"] == body) &&
            fields.all { (name, value) -> name in setOf("title", "text", "bigText") || value.isBlank() } &&
            lines.all { it.isBlank() }
    }

    internal const val RECEIVED_TITLE = receivedTitle
    internal val ruleIds = setOf(observedV1.id, kachingV1.id)

    /** The reviewed rules whose candidates may be delivered; each is sent with its own rule ID as profile. */
    val deliveredRuleIds: Set<String> = setOf(V1_RULE_ID, KACHING_RULE_ID)

    /** Both reviewed wordings; each candidate carries the ID of the rule that matched. */
    val current = ClassificationProfile(listOf(observedV1, kachingV1))
}
