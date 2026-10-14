package dev.kaipay.probe

import org.junit.Assert.*
import org.junit.Test

/** Generic wording derived from one observation; ALL names and amounts below are invented. */
class TngNotificationProfilesTest {
    private val title = "You\u2019ve received money!"
    private fun body(amount: String = "12.34", party: String = "Alex Demo") =
        "$party has transferred RM$amount to you. Tap here to check the transaction details."
    private fun inspect(text: String = body(), heading: String = title, expanded: String? = text,
        sub: String? = null, summary: String? = null, lines: List<String> = emptyList(), grouped: Boolean = false) =
        ProbeParser.inspectContent(ProbeParser.OFFICIAL_PACKAGE,
            { Fields(heading, text, expanded, sub, summary, lines) }, grouped, TngNotificationProfiles.current)!!

    @Test fun `observed English structure with invented values is an incoming candidate only`() {
        val result = inspect().classification
        assertEquals(ClassificationOutcome.INCOMING_CANDIDATE, result.outcome)
        assertEquals(TransactionDirection.INCOMING, result.direction)
        assertEquals(1234L, result.amountMinor)
        assertEquals("MYR", result.currency)
        assertEquals(EvidenceQuality.RULE_MATCHED, result.confidence)
        assertEquals("tng.receipt.en.observed.v1", result.ruleId)
        assertNull(result.reference)
        assertTrue(result.explanation.contains("independent transaction verification"))
    }
    @Test fun `unchanged body supports absent optional expanded field`() {
        assertEquals(ClassificationOutcome.INCOMING_CANDIDATE, inspect(expanded = null).classification.outcome)
    }
    @Test fun `generic title punctuation variations remain unknown`() {
        listOf("You've received money!", "You received money!", "You\u2019ve received money", "Received money!",
            title + " PROMOTION", title + "\n").forEach {
            assertNotEquals(it, ClassificationOutcome.INCOMING_CANDIDATE, inspect(heading = it).classification.outcome)
        }
    }
    @Test fun `received title alone cannot classify missing body`() {
        val result = ProbeParser.inspectContent(ProbeParser.OFFICIAL_PACKAGE,
            { Fields(title = title, bigText = body()) }, profile = TngNotificationProfiles.current)!!
        assertEquals(ClassificationOutcome.UNKNOWN, result.classification.outcome)
    }
    @Test fun `full body anchor rejects appended and prepended instructions`() {
        listOf(body() + " Further information", "Notice: " + body(), body() + "\n",
            body().replace("check the transaction details", "view your transaction details"),
            body().replace("to you.", "to your wallet.")).forEach {
            assertNotEquals(it, ClassificationOutcome.INCOMING_CANDIDATE, inspect(text = it).classification.outcome)
        }
    }
    @Test fun `expanded mismatch remains unknown even if amount agrees`() {
        assertEquals(ClassificationOutcome.UNKNOWN,
            inspect(expanded = body(party = "Robin Demo")).classification.outcome)
    }
    @Test fun `conflicting expanded amount remains ambiguous`() {
        val result = inspect(expanded = body("98.76")).classification
        assertEquals(ClassificationOutcome.AMBIGUOUS, result.outcome)
        assertNull(result.amountMinor)
    }
    @Test fun `unobserved supporting content and lines never yield incoming`() {
        listOf(inspect(sub = "Additional information"), inspect(summary = "Additional information"),
            inspect(lines = listOf(body()))).forEach {
            assertNotEquals(ClassificationOutcome.INCOMING_CANDIDATE, it.classification.outcome)
        }
    }
    @Test fun `promotional or outgoing wording inside party cannot exploit receipt template`() {
        listOf("Cashback Offer", "Payment Request", "Transfer To", "Sent Funds", "Failed Transfer",
            "Refund Notice", "Cancelled Transfer").forEach {
            assertNotEquals(it, ClassificationOutcome.INCOMING_CANDIDATE, inspect(text = body(party = it)).classification.outcome)
        }
    }
    @Test fun `missing invalid unsupported or conflicting money never yields incoming`() {
        listOf(body("0.00"), body("-1.00"), body("1.001"), body("01.00"),
            body().replace("RM12.34", "USD12.34"), body().replace("RM12.34", "RM12.34 and RM98.76"),
            body().replace("RM12.34", "RM")).forEach {
            assertNotEquals(it, ClassificationOutcome.INCOMING_CANDIDATE, inspect(text = it).classification.outcome)
        }
    }
    @Test fun `group summary is never an individual receipt candidate`() {
        assertEquals(ClassificationOutcome.UNKNOWN, inspect(grouped = true).classification.outcome)
    }
    @Test fun `credentials in expanded or supporting fields suppress all text`() {
        listOf(inspect(expanded = "OTP123"), inspect(sub = "PIN123"), inspect(summary = "passwordSecret"),
            inspect(lines = listOf("TACabc"))).forEach {
            assertEquals(Disposition.SENSITIVE_BLOCKED, it.diagnostic.disposition)
            assertTrue(it.fields.isEmpty())
            assertNotEquals(ClassificationOutcome.INCOMING_CANDIDATE, it.classification.outcome)
        }
    }
    @Test fun `unrelated application never invokes supplier with observed profile`() {
        assertNull(ProbeParser.inspectContent("unrelated.synthetic", { error("Unrelated content read") },
            profile = TngNotificationProfiles.current))
    }
    @Test fun `party or multiline bounds remain conservative`() {
        listOf(body(party = "A".repeat(129)), body(party = "Alex\nDemo"), body(party = ""),
            body(party = "123"), body(party = "Alex Demo RM1.00")).forEach {
            assertNotEquals(it, ClassificationOutcome.INCOMING_CANDIDATE, inspect(text = it).classification.outcome)
        }
    }
}
