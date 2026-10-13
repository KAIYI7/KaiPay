package dev.kaipay.probe

import org.junit.Assert.*
import org.junit.Test

/** Every payment example here is invented test wording, NOT an observed TNG format. */
class NotificationClassifierTest {
    private val synthetic = ClassificationProfile(listOf(IncomingRule("synthetic.receipt") { fields, _ ->
        fields["title"] == "Synthetic receipt" && fields["text"]?.startsWith("Received payment ") == true
    }))
    private fun classify(text: String, profile: ClassificationProfile = synthetic) = NotificationClassifier.classify(
        mapOf("title" to "Synthetic receipt", "text" to text), profile = profile)
    private fun inspect(fields: Fields, profile: ClassificationProfile = ClassificationProfile.EMPTY) =
        ProbeParser.inspectContent(ProbeParser.OFFICIAL_PACKAGE, { fields }, profile = profile)!!

    @Test fun `explicit synthetic incoming requires deliberately supplied profile`() {
        val result = classify("Received payment RM 12.34")
        assertEquals(ClassificationOutcome.INCOMING_CANDIDATE, result.outcome)
        assertEquals(1234L, result.amountMinor)
        assertEquals("MYR", result.currency)
        assertEquals(TransactionDirection.INCOMING, result.direction)
        assertEquals(EvidenceQuality.RULE_MATCHED, result.confidence)
        assertEquals("synthetic.receipt", result.ruleId)
        assertNull(result.reference)
    }
    @Test fun `live empty registry never infers incoming from synthetic receipt`() {
        val result = classify("Received payment RM 12.34", ClassificationProfile.EMPTY)
        assertEquals(ClassificationOutcome.UNKNOWN, result.outcome)
        assertEquals(1234L, result.amountMinor)
        assertEquals(EvidenceQuality.AMOUNT_ONLY, result.confidence)
        assertEquals(TransactionDirection.UNKNOWN, result.direction)
    }
    @Test fun `RM alone is never incoming`() {
        assertEquals(ClassificationOutcome.AMBIGUOUS, classify("RM", ClassificationProfile.EMPTY).outcome)
    }
    @Test fun `outgoing wording is distinguished conservatively`() {
        val result = classify("You sent MYR 3.45")
        assertEquals(ClassificationOutcome.OUTGOING_OR_UNRELATED, result.outcome)
        assertEquals(TransactionDirection.OUTGOING, result.direction)
        assertEquals(345L, result.amountMinor)
    }
    @Test fun `generic transaction remains unknown`() {
        assertEquals(ClassificationOutcome.UNKNOWN, classify("Transaction RM 2.00").outcome)
    }
    @Test fun `promotion cannot exploit incoming words`() {
        assertEquals(ClassificationOutcome.OUTGOING_OR_UNRELATED,
            classify("Received payment RM 2.00 cashback offer").outcome)
    }
    @Test fun `balance update never becomes incoming`() {
        assertEquals(ClassificationOutcome.OUTGOING_OR_UNRELATED, classify("Received payment RM 2.00 balance update").outcome)
    }
    @Test fun `negated receipt and requests are excluded`() {
        listOf("not received", "did not receive", "no payment", "payment request", "will receive", "may receive",
            "how to", "example", "pending", "failed", "refund", "reminder", "was not successful", "cancelled",
            "canceled", "unsuccessful", "not completed", "rejected", "aborted").forEach {
            assertEquals(it, ClassificationOutcome.OUTGOING_OR_UNRELATED, classify("Received payment RM 2.00 $it").outcome)
        }
    }
    @Test fun `incoming outgoing conflict stays ambiguous`() {
        assertEquals(ClassificationOutcome.AMBIGUOUS, classify("Received payment RM 2.00 and sent RM 2.00").outcome)
    }
    @Test fun `incoming missing amount stays ambiguous`() {
        assertEquals(ClassificationOutcome.AMBIGUOUS, classify("Received payment successfully").outcome)
    }
    @Test fun `exact decimal precision and cents supported`() {
        assertEquals(1L, classify("Received payment MYR 0.01").amountMinor)
        assertEquals(120L, classify("Received payment rm1.20").amountMinor)
        assertEquals(1200L, classify("Received payment MYR 12").amountMinor)
        assertEquals(120L, classify("Received payment myr 1.20 for 2 people").amountMinor)
    }
    @Test fun `grouped MYR amount parsed exactly`() {
        assertEquals(123456789L, classify("Received payment MYR 1,234,567.89").amountMinor)
    }
    @Test fun `malformed numeric tokens never yield partial amounts`() {
        listOf("1.001", "1.2", "1,00.00", "01.00", "1e2", "+1.00", "-1.00", "1.00abc", "1.00/2.00",
            "1..00", "NaN", "1.00,", "1.00.01", "1 000.00", "1.00 2.00").forEach {
            val result = classify("Received payment RM $it")
            assertEquals(it, ClassificationOutcome.AMBIGUOUS, result.outcome)
            assertNull(result.amountMinor)
        }
    }
    @Test fun `zero and overflow fail closed`() {
        listOf("0", "0.00", "92233720368547758.08", "999999999999999999999.99").forEach {
            assertEquals(ClassificationOutcome.AMBIGUOUS, classify("Received payment RM $it").outcome)
        }
    }
    @Test fun `largest exact Long minor amount accepted without float`() {
        assertEquals(Long.MAX_VALUE, classify("Received payment RM 92233720368547758.07").amountMinor)
    }
    @Test fun `conflicting values are discarded`() {
        val result = classify("Received payment RM 2.00 and RM 3.00")
        assertEquals(ClassificationOutcome.AMBIGUOUS, result.outcome)
        assertNull(result.amountMinor)
    }
    @Test fun `duplicate Android field amounts do not conflict`() {
        val result = NotificationClassifier.classify(mapOf("title" to "Synthetic receipt",
            "text" to "Received payment RM 2.00", "bigText" to "Received payment RM 2.00"),
            listOf("Received payment RM 2.00"), profile = synthetic)
        assertEquals(ClassificationOutcome.INCOMING_CANDIDATE, result.outcome)
        assertEquals(200L, result.amountMinor)
    }
    @Test fun `conflicting expanded field amounts remain ambiguous`() {
        assertEquals(ClassificationOutcome.AMBIGUOUS, NotificationClassifier.classify(mapOf(
            "title" to "Synthetic receipt", "text" to "Received payment RM 2.00", "bigText" to "RM 3.00"),
            profile = synthetic).outcome)
    }
    @Test fun `unsupported currencies and mixed currencies rejected`() {
        listOf("USD 1.00", "SGD1.00", "EUR 1.00", "ZAR 1.00", "zar 1.00", "$1.00", "RM 1.00 USD",
            "RM 1.00 usd 1.00", "RM 1.00 chf2.00", "RM 1.00 nzd 2.00", "RM 1.00 btc 2.00",
            "RM 1.00 2.00USD", "RM 1.00 and 2.00chf", "RM 1.00 and 2.00XXX",
            "RM 1.00 XXX2.00", "RM 1.00 xag2.00", "RM 1.00 xau 2.00", "RM 1.00 kwd2.00",
            "RM 1.00 and 2.00xag", "RM 1.00 and 2.00xau", "RM 1.00 and 2.00kwd").forEach {
            val result = classify("Received payment $it")
            assertEquals(ClassificationOutcome.AMBIGUOUS, result.outcome)
            assertNull(result.amountMinor)
        }
    }
    @Test fun `unsupported amount currency suffix is rejected`() {
        assertEquals(ClassificationOutcome.AMBIGUOUS, classify("Received payment 1.00 MYR").outcome)
    }
    @Test fun `sentence ending after decimal is safe`() {
        assertEquals(123L, classify("Received payment RM 1.23.").amountMinor)
    }
    @Test fun `group summary never yields incoming`() {
        val result = NotificationClassifier.classify(mapOf("title" to "Synthetic receipt",
            "text" to "Received payment RM 2.00"), groupSummary = true, profile = synthetic)
        assertEquals(ClassificationOutcome.UNKNOWN, result.outcome)
        assertNull(result.amountMinor)
    }
    @Test fun `multiple rule matches remain ambiguous`() {
        val profile = ClassificationProfile(listOf(IncomingRule("synthetic.a") { _, _ -> true },
            IncomingRule("synthetic.b") { _, _ -> true }))
        assertEquals(ClassificationOutcome.AMBIGUOUS, classify("Received payment RM 2.00", profile).outcome)
    }
    @Test fun `empty and redacted text do not classify`() {
        listOf("", " ", "hidden", "Content hidden", "[redacted]").forEach {
            assertEquals(ClassificationOutcome.UNKNOWN, NotificationClassifier.classify(mapOf("text" to it)).outcome)
        }
    }
    @Test fun `classification representation never reveals financial fields`() {
        val result = Classification(ClassificationOutcome.INCOMING_CANDIDATE, 12345L, "MYR",
            TransactionDirection.INCOMING, "private-reference", EvidenceQuality.RULE_MATCHED,
            "private explanation", "synthetic.receipt")
        assertFalse(result.toString().contains("12345"))
        assertFalse(result.toString().contains("private"))
    }
    @Test fun `unsupported package filters before content access`() {
        listOf(null, "", "unrelated", "my.com.tngdigital.ewallet.fake").forEach {
            assertNull(ProbeParser.inspectContent(it, { error("Unrelated content read") }))
        }
    }
    @Test fun `all six supported fields are inspectable locally`() {
        val result = inspect(Fields("title", "body", "expanded", "sub", "summary", arrayOf("line one", "line two")))
        assertEquals(setOf("title", "text", "bigText", "subText", "summaryText"), result.fields.keys)
        assertEquals(listOf("line one", "line two"), result.textLines)
        assertEquals(Diagnostic(Disposition.UNKNOWN, true, true, true), result.diagnostic)
    }
    @Test fun `missing fields and empty line list remain safe`() {
        val result = inspect(Fields(textLines = emptyList<String>()))
        assertTrue(result.fields.isEmpty())
        assertTrue(result.textLines.isEmpty())
        assertEquals(ClassificationOutcome.UNKNOWN, result.classification.outcome)
    }
    @Test fun `malformed scalar and array values drop entire content`() {
        listOf(Fields(subText = 42), Fields(summaryText = listOf("unexpected")), Fields(textLines = 42),
            Fields(textLines = arrayOf("safe", null)), Fields(textLines = listOf("safe", 3))).forEach {
            val result = inspect(it)
            assertEquals(Disposition.MALFORMED, result.diagnostic.disposition)
            assertTrue(result.fields.isEmpty())
            assertTrue(result.textLines.isEmpty())
        }
        val unreadable = ProbeParser.inspectContent(ProbeParser.OFFICIAL_PACKAGE, { throw IllegalArgumentException("private") })!!
        assertEquals(Disposition.MALFORMED, unreadable.diagnostic.disposition)
        assertFalse(unreadable.toString().contains("private"))
    }
    @Test fun `oversize fields and line count reject rather than truncate`() {
        listOf(Fields(text = "a".repeat(4097)), Fields(textLines = List(17) { "line" }),
            Fields(textLines = listOf("a".repeat(4097)))).forEach {
            assertEquals(Disposition.MALFORMED, inspect(it).diagnostic.disposition)
        }
    }
    @Test fun `oversize line containers are rejected before copying or traversing`() {
        val oversize = object : AbstractList<String>() {
            override val size = 17
            override fun get(index: Int): String = error("Oversize lines must never be inspected or copied")
        }
        assertEquals(Disposition.MALFORMED, inspect(Fields(textLines = oversize)).diagnostic.disposition)
        assertEquals(Disposition.MALFORMED, inspect(Fields(textLines = Array(17) { "line" })).diagnostic.disposition)
    }
    @Test fun `total content ceiling enforced across repeated fields`() {
        val a = "a".repeat(4096)
        assertEquals(Disposition.MALFORMED, inspect(Fields(a, a, a, a, "a")).diagnostic.disposition)
        assertEquals(Disposition.UNKNOWN, inspect(Fields(a, a, a, a)).diagnostic.disposition)
    }
    @Test fun `credential marker in any newly supported field suppresses all text`() {
        listOf(Fields(text = "safe", subText = "OTP 123456"), Fields(summaryText = "password"),
            Fields(textLines = listOf("safe", "PIN 4321")), Fields(bigText = "验证码")).forEach {
            val result = inspect(it)
            assertEquals(Diagnostic(Disposition.SENSITIVE_BLOCKED), result.diagnostic)
            assertTrue(result.fields.isEmpty())
            assertTrue(result.textLines.isEmpty())
        }
    }
    @Test fun `fused credential labels are blocked across every supported field`() {
        listOf("OTP123", "PIN123", "TACabc", "passwordExampleSecret").forEach { secret ->
            listOf(Fields(title = secret), Fields(text = secret), Fields(bigText = secret),
                Fields(subText = secret), Fields(summaryText = secret), Fields(textLines = listOf(secret))).forEach {
                val result = inspect(it)
                assertEquals(Diagnostic(Disposition.SENSITIVE_BLOCKED), result.diagnostic)
                assertTrue(result.fields.isEmpty())
                assertTrue(result.textLines.isEmpty())
            }
        }
    }
    @Test fun `bare possible PIN and OTP digit sequences are suppressed`() {
        listOf("1234", "123456", "Code: 12345678", "Reference 987654", "１２３４").forEach {
            assertEquals(Disposition.SENSITIVE_BLOCKED, inspect(Fields(text = it)).diagnostic.disposition)
        }
    }
    @Test fun `recognized exact MYR amount spans are not credentials`() {
        listOf("RM 1234.56", "MYR 123,456.78", "RM 1234", "RM 1.23").forEach {
            val result = inspect(Fields(text = it))
            assertEquals(it, Disposition.UNKNOWN, result.diagnostic.disposition)
            assertEquals(it, result.fields["text"])
        }
    }
    @Test fun `amount span cannot hide additional bare code`() {
        assertEquals(Disposition.SENSITIVE_BLOCKED, inspect(Fields(text = "RM 1234.56 code 4321")).diagnostic.disposition)
    }
    @Test fun `content object representations omit private text`() {
        val result = inspect(Fields("private-sender", "private-wallet", "private-reference"))
        assertFalse(result.toString().contains("private"))
        assertTrue(result.fields["title"]!!.contains("private"))
        assertFalse(result.classification.explanation.contains("private"))
    }
}
