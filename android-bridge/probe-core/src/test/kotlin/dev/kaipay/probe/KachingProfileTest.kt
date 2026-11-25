package dev.kaipay.probe

import org.junit.Assert.*
import org.junit.Test

/** Second wording, from one owner-reported receipt; ALL names, amounts and notes below are invented. */
class KachingProfileTest {
    private val title = "Ka-ching! Incoming Money."
    private val ruleId = "tng.receipt.kaching.en.observed.v1"
    private fun body(amount: String = "RM12.34", party: String = "ALEX DEMO", note: String = "资金转账") =
        "$amount received from $party for $note"
    private fun inspect(text: String = body(), heading: String = title, expanded: String? = text,
        sub: String? = null, summary: String? = null, lines: List<String> = emptyList(), grouped: Boolean = false) =
        ProbeParser.inspectContent(ProbeParser.OFFICIAL_PACKAGE,
            { Fields(heading, text, expanded, sub, summary, lines) }, grouped, TngNotificationProfiles.current)!!
    private fun outcome(text: String = body(), heading: String = title, expanded: String? = text) =
        inspect(text, heading, expanded).classification.outcome

    @Test fun `reported structure with invented values is an incoming candidate only`() {
        val result = inspect().classification
        assertEquals(ClassificationOutcome.INCOMING_CANDIDATE, result.outcome)
        assertEquals(TransactionDirection.INCOMING, result.direction)
        assertEquals(1234L, result.amountMinor)
        assertEquals("MYR", result.currency)
        assertEquals(EvidenceQuality.RULE_MATCHED, result.confidence)
        assertEquals(ruleId, result.ruleId)
        assertNull(result.reference)
        assertTrue(result.explanation.contains("independent transaction verification"))
    }
    @Test fun `accepted currency forms names and notes`() {
        mapOf("RM12.34" to 1234L, "RM 12.34" to 1234L, "MYR12.34" to 1234L, "MYR 12.34" to 1234L,
            "RM1,234.00" to 123400L, "RM5" to 500L).forEach { (amount, minor) ->
            val result = inspect(body(amount = amount)).classification
            assertEquals(amount, ClassificationOutcome.INCOMING_CANDIDATE, result.outcome)
            assertEquals(amount, minor, result.amountMinor)
        }
        listOf("ALEX DEMO", "Alex Demo", "RAJ A/L DEMO", "SITI A/P DEMO", "ALI @ DEMO", "O'DEMO", "DEMO-LEE",
            "J. DEMO", "王小明").forEach {
            assertEquals(it, ClassificationOutcome.INCOMING_CANDIDATE, outcome(body(party = it)))
        }
        listOf("Fund transfer", "makan", "资金转账", "dinner 晚餐", "x").forEach {
            assertEquals(it, ClassificationOutcome.INCOMING_CANDIDATE, outcome(body(note = it)))
        }
        assertEquals(ClassificationOutcome.INCOMING_CANDIDATE, outcome(expanded = null))
    }
    @Test fun `title variants remain unknown`() {
        listOf("Ka-ching! Incoming money.", "Ka‑ching! Incoming Money.", "Ka-ching! Incoming Money. ",
            "Ka-ching Incoming Money.", "Ka-ching! Incoming Money", "KA-CHING! INCOMING MONEY.",
            "You’ve received money!").forEach {
            assertNotEquals(it, ClassificationOutcome.INCOMING_CANDIDATE, outcome(heading = it))
        }
    }
    @Test fun `body anchors reject prefixes suffixes breaks and missing notes`() {
        listOf("Notice: " + body(), body() + "\nTap here", "RM12.34 received from ALEX DEMO",
            "RM12.34 received from ALEX DEMO for ", "RM12.34 received from ALEX DEMO for    ",
            "RM12.34 received from ALEX DEMO for \tdemo", "RM12.34 received from ALEX DEMO for a b",
            body(note = "n".repeat(257)), "RM12.34 received from  for demo", "12.34 received from ALEX DEMO for demo",
            "RM12.34 received by ALEX DEMO for demo").forEach {
            assertNotEquals(it, ClassificationOutcome.INCOMING_CANDIDATE, outcome(it))
        }
        assertEquals(ClassificationOutcome.INCOMING_CANDIDATE, outcome(body(note = "n".repeat(256))))
    }
    @Test fun `note edge characters and lowercase currency`() {
        val joined = String(Character.toChars(0x1F468)) + Char(0x200D) + String(Character.toChars(0x1F469))
        assertEquals(ClassificationOutcome.INCOMING_CANDIDATE, outcome(body(note = joined)))
        assertEquals(ClassificationOutcome.INCOMING_CANDIDATE, outcome(body(party = "ALI for BOB")))
        listOf("demo" + Char(0x202E) + "x", Char(0x200B) + "demo", Char(0xA0) + "demo", Char(0x3000) + "demo",
            Char(0x200D).toString(), Char(0x200D) + "demo").forEach {
            assertNotEquals(it, ClassificationOutcome.INCOMING_CANDIDATE, outcome(body(note = it)))
        }
        val lower = inspect(body(amount = "rm12.34")).classification
        assertEquals(ClassificationOutcome.UNKNOWN, lower.outcome)
        assertEquals(EvidenceQuality.AMOUNT_ONLY, lower.confidence)
    }
    @Test fun `names outside the widened class are not candidates`() {
        listOf("ALEX DEMO 2", "DEMO, ALEX", "ALEX & DEMO", "ALEX (DEMO)", "A".repeat(129), "-ALEX").forEach {
            assertNotEquals(it, ClassificationOutcome.INCOMING_CANDIDATE, outcome(body(party = it)))
        }
        assertEquals(ClassificationOutcome.INCOMING_CANDIDATE, outcome(body(party = "A".repeat(128))))
    }
    @Test fun `filter words in a note fail safe`() {
        listOf("test", "refund", "request", "pending", "balance", "cashback", "reminder", "promo", "failed").forEach {
            assertEquals(it, ClassificationOutcome.OUTGOING_OR_UNRELATED, outcome(body(note = it)))
        }
        listOf("dinner paid", "sent back", "transfer to Demo", "purchase").forEach {
            assertEquals(it, ClassificationOutcome.AMBIGUOUS, outcome(body(note = it)))
        }
    }
    @Test fun `money and credential content in a note fail safe`() {
        listOf("RM5.00 share", "USD 3", "pax 2", "€3").forEach {
            assertEquals(it, ClassificationOutcome.AMBIGUOUS, outcome(body(note = it)))
        }
        listOf("pinjam", "shopping", "20261125", "OTP", "密码").forEach {
            val result = inspect(body(note = it))
            assertEquals(it, Disposition.SENSITIVE_BLOCKED, result.diagnostic.disposition)
            assertEquals(it, ClassificationOutcome.UNKNOWN, result.classification.outcome)
        }
        listOf(body(amount = "RM0.00"), body(amount = "RM01.00"), body(amount = "RM1.001"), body(amount = "RM1,23.00")).forEach {
            assertNotEquals(it, ClassificationOutcome.INCOMING_CANDIDATE, outcome(it))
        }
    }
    @Test fun `expanded mismatch supporting content lines and group summaries never yield incoming`() {
        assertEquals(ClassificationOutcome.UNKNOWN, outcome(expanded = body(party = "ROBIN DEMO")))
        assertEquals(ClassificationOutcome.AMBIGUOUS, outcome(expanded = body(amount = "RM98.76")))
        listOf(inspect(sub = "Additional information"), inspect(summary = "Additional information"),
            inspect(lines = listOf(body())), inspect(grouped = true)).forEach {
            assertNotEquals(ClassificationOutcome.INCOMING_CANDIDATE, it.classification.outcome)
        }
    }
    @Test fun `the two rules never both match and the first rule is unchanged`() {
        val first = "Alex Demo has transferred RM12.34 to you. Tap here to check the transaction details."
        val v1 = inspect(first, "You’ve received money!").classification
        assertEquals(ClassificationOutcome.INCOMING_CANDIDATE, v1.outcome)
        assertEquals("tng.receipt.en.observed.v1", v1.ruleId)
        assertEquals(ClassificationOutcome.UNKNOWN, outcome(first))
        assertEquals(ClassificationOutcome.UNKNOWN, outcome(heading = "You’ve received money!"))
        assertEquals(ClassificationOutcome.UNKNOWN,
            inspect("Raj A/L Demo has transferred RM12.34 to you. Tap here to check the transaction details.",
                "You’ve received money!").classification.outcome)
        assertEquals(listOf("tng.receipt.en.observed.v1", ruleId), TngNotificationProfiles.current.incomingRules.map { it.id })
    }
    @Test fun `refund and reversal wording is rejected by the second rule only`() {
        val english = listOf("reversal", "Reversal", "REVERSAL", "reversals", "refunded", "Refunds", "REFUNDING",
            "chargeback", "ChargeBack", "chargebacks", "charge-back", "Charge back", "Bayaran balik", "BAYARAN  BALIK",
            "bayaran" + Char(0xA0) + "balik", "pembalikan", "Pembalikan dana",
            // Latin terms glued to Han text are rejected on the JVM and on Android alike (explicit ASCII edges).
            "资金refunds", "reversal资金", "退回x")
        val chinese = listOf("退款", "退还", "退還", "退回", "冲正", "沖正", "撤销", "撤銷", "撤回", "资金退款", "退回饭钱")
        (english + chinese).forEach { word ->
            val noted = inspect(body(note = word)).classification
            assertEquals(word, ClassificationOutcome.UNKNOWN, noted.outcome)
            assertEquals(word, EvidenceQuality.AMOUNT_ONLY, noted.confidence)
            assertNull(word, noted.ruleId)
        }
        // In the name too: the reject reads the whole text.
        listOf("REVERSAL DEMO", "退款 DEMO").forEach {
            assertEquals(it, ClassificationOutcome.UNKNOWN, outcome(body(party = it)))
        }
        // Whole words only for Latin terms; benign notes still match.
        listOf("refundable", "irreversal", "reversalx", "refunds2", "chargebackx", "rechargeback", "资金转账", "dinner", "Fund transfer", "pembayaran", "balik kampung")
            .forEach { assertEquals(it, ClassificationOutcome.INCOMING_CANDIDATE, outcome(body(note = it))) }
    }
    @Test fun `the second rule's reject leaves the first rule unchanged`() {
        val first = "REVERSAL DEMO has transferred RM12.34 to you. Tap here to check the transaction details."
        val v1 = inspect(first, "You’ve received money!").classification
        assertEquals(ClassificationOutcome.INCOMING_CANDIDATE, v1.outcome)
        assertEquals("tng.receipt.en.observed.v1", v1.ruleId)
        assertEquals(setOf("tng.receipt.en.observed.v1", ruleId), TngNotificationProfiles.deliveredRuleIds)
    }
}
