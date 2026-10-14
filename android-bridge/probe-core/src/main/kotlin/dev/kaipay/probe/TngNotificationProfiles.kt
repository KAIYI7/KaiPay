package dev.kaipay.probe

/** One English receipt structure observed locally; real identities and amounts are never retained as fixtures. */
object TngNotificationProfiles {
    private const val receivedTitle = "You\u2019ve received money!"
    private val receiptBody = Regex(
        "[\\p{L}\\p{M}][\\p{L}\\p{M} .'\u2019-]{0,127} has transferred " +
            "RM ?[0-9][0-9,]*(?:\\.[0-9]+)? to you\\. " +
            "Tap here to check the transaction details\\."
    )
    val current = ClassificationProfile(listOf(IncomingRule("tng.receipt.en.observed.v1") { fields, lines ->
        val body = fields["text"]
        fields["title"] == receivedTitle && body != null && receiptBody.matches(body) &&
            (fields["bigText"] == null || fields["bigText"] == body) &&
            fields.all { (name, value) -> name in setOf("title", "text", "bigText") || value.isBlank() } &&
            lines.all { it.isBlank() }
    }))
}
