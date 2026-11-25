package dev.kaipay.probe

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

/** Every name, amount, note, reference and time below is invented. */
class B0ReportTest {
    private val kaching = "Ka-ching! Incoming Money."
    private val received = "You’ve received money!"
    private var clock = 0L
    private fun session() = ProbeSession(elapsedClock = { clock }, profile = TngNotificationProfiles.current).apply {
        setConnected(true); setEnabled(true)
    }
    private fun ProbeSession.post(title: String?, text: String?, bigText: String? = text, sub: String? = null,
        lines: List<String> = emptyList(), key: String = "key-${Random.nextInt()}", time: Long = 1_700_000_000_123) =
        posted(ProbeParser.OFFICIAL_PACKAGE, key, time, NotificationMetadata("channel-demo", time - 5)) {
            Fields(title, text, bigText, sub, null, lines)
        }
    private fun report(vararg posts: Pair<String?, String?>): B0Report.Line {
        val session = session()
        posts.forEach { (title, text) -> session.post(title, text) }
        return B0Report.line(session.snapshot(), 6)
    }

    @Test fun `reported second wording gives exact placeholders and no private value`() {
        val line = report(kaching to "RM12.34 received from ALEX DEMO for 资金转账")
        assertEquals("B0 v6 r=? f=? o=C e=RM rule=tng.receipt.kaching.en.observed.v1 amt=Y why=RULE " +
            "t=\"Ka-ching! Incoming Money.\" b=\"RM[AMOUNT] received from [NAME] for [NOTE]\" bt=same x=- " +
            "name=UPPER,Latin note=Han n=1/1 rem=0", line.text)
        assertEquals(1, line.index); assertEquals(1, line.total)
        listOf("ALEX", "DEMO", "12", "34", "资", "转账").forEach { assertFalse(it, line.text.contains(it)) }
    }
    @Test fun `first wording and currency forms are reported as templates`() {
        val first = report(received to "Alex Demo has transferred RM 12.34 to you. Tap here to check the transaction details.")
        assertTrue(first.text, first.text.contains("o=C e=RM rule=tng.receipt.en.observed.v1"))
        assertTrue(first.text, first.text.contains(
            "b=\"[NAME] has transferred RM [AMOUNT] to you. Tap here to check the transaction details.\""))
        assertTrue(first.text, first.text.contains("name=Mixed,Latin "))
        assertFalse(first.text.contains("note="))
        val myr = report(kaching to "MYR 5.00 received from ALEX DEMO for makan")
        assertTrue(myr.text, myr.text.contains("b=\"MYR [AMOUNT] received from [NAME] for [NOTE]\""))
        assertTrue(myr.text, myr.text.contains("note=Latin "))
    }
    @Test fun `failed rule still shows template and flags that explain it`() {
        val slash = report(received to "Raj A/L Demo has transferred RM12.34 to you. Tap here to check the transaction details.")
        assertTrue(slash.text, slash.text.contains("o=U e=AO rule=- amt=Y why=NORULE"))
        assertTrue(slash.text, slash.text.contains("name=Mixed,Latin,/ "))
        assertFalse(slash.text.contains("Raj"))
        val filtered = report(kaching to "RM12.34 received from ALEX DEMO for test 2")
        assertTrue(filtered.text, filtered.text.contains("o=X"))
        assertTrue(filtered.text, filtered.text.contains("why=FILTER"))
        assertTrue(filtered.text, filtered.text.contains("b=\"RM[AMOUNT] received from [NAME] for [NOTE]\""))
        assertTrue(filtered.text, filtered.text.contains("note=Latin+0 "))
        assertFalse(filtered.text.contains("test"))
        val titleVariant = report("Ka‑ching! Incoming Money." to "RM12.34 received from ALEX DEMO for demo")
        assertTrue(titleVariant.text, titleVariant.text.contains("t~\"[?]! Incoming Money.\""))
    }
    @Test fun `unknown wording becomes a skeleton of vocabulary words only`() {
        val line = report("Payment successful" to "You received RM12.34 from TAN AH KOW. Ref 202611250001234 at 10:32.")
        assertTrue(line.text, line.text.contains("t~\"Payment successful\""))
        assertTrue(line.text, line.text.contains("b~\"You received RM[AMOUNT] from [?].\""))
        listOf("TAN", "KOW", "Ref", "2026", "10", "32").forEach { assertFalse(it, line.text.contains(it)) }
        assertFalse(line.text.contains("name="))
    }
    @Test fun `blocked and malformed notifications carry no wording`() {
        val blocked = report(kaching to "RM12.34 received from ALEX DEMO for pinjam")
        assertEquals("B0 v6 r=? f=? o=U d=SB e=N rule=- amt=N why=SENSITIVE n=1/1 rem=0", blocked.text)
        val session = session()
        session.posted(ProbeParser.OFFICIAL_PACKAGE, "k", 1) { Fields(title = 5) }
        assertEquals("B0 v6 r=? f=? o=U d=MF e=N rule=- amt=N why=MALFORMED n=1/1 rem=0",
            B0Report.line(session.snapshot(), 6).text)
    }
    @Test fun `missed observation reports counts and states only`() {
        val session = session()
        session.post(kaching, "RM12.34 received from ALEX DEMO for demo", key = "k1")
        session.removed(ProbeParser.OFFICIAL_PACKAGE, "k1", 1)
        val line = B0Report.line(session.snapshot(), 6)
        assertEquals("B0 v6 r=? f=? o=M n=0/0 rem=1 det=Y lis=Y", line.text)
        assertEquals(0, line.index)
        val off = ProbeSession(elapsedClock = { 0 })
        assertEquals("B0 v6 r=? f=? o=M n=0/0 rem=0 det=N lis=N", B0Report.line(off.snapshot(), 6).text)
    }
    @Test fun `newest inspected observation is reported with its position and extras by name only`() {
        val session = session()
        session.post("Promotion", "Get a voucher today", key = "a")
        session.post(kaching, "RM12.34 received from ALEX DEMO for demo", "different SECRET expanded", sub = "SUBSECRET",
            lines = listOf("LINESECRET"), key = "b")
        val line = B0Report.line(session.snapshot(), 6)
        assertTrue(line.text, line.text.contains("n=2/2"))
        assertTrue(line.text, line.text.contains("bt~\"[?]\""))
        assertTrue(line.text, line.text.contains("x=subText,lines"))
        listOf("SECRET", "different", "expanded").forEach { assertFalse(it, line.text.contains(it)) }
    }
    @Test fun `every classifier explanation maps to a code`() {
        val explanations = mutableSetOf<String>()
        fun add(title: String?, text: String?, grouped: Boolean = false, profile: ClassificationProfile = TngNotificationProfiles.current) {
            ProbeParser.inspectContent(ProbeParser.OFFICIAL_PACKAGE, { Fields(title, text) }, grouped, profile)!!
                .classification.explanation.let { explanations += it }
        }
        add(null, "hidden"); add(null, "USD 5"); add(null, "ABC 5"); add(null, "5 ABC"); add(null, "5 RM")
        add(null, "RM"); add(null, "RM5 6"); add(null, "RM5.5"); add(null, "RM5 and RM6"); add(null, "RM5", grouped = true)
        add(null, "RM5 sent"); add(kaching, "RM5.00 received from A for paid"); add(null, "RM5 cashback")
        add(null, "RM5"); add(kaching, "RM5.00 received from A for demo")
        add(kaching, "RM5.00 received from A for demo", profile = ClassificationProfile(listOf(
            IncomingRule("a") { _, _ -> true }, IncomingRule("b") { _, _ -> true })))
        add("x", "no amount", profile = ClassificationProfile(listOf(IncomingRule("c") { _, _ -> true })))
        add(null, "OTP"); explanations += ProbeParser.inspectContent(ProbeParser.OFFICIAL_PACKAGE, { Fields(5) })!!
            .classification.explanation
        assertEquals(19, explanations.size)
        explanations.forEach { assertNotEquals(it, "OTHER", B0Report.why(it)) }
        assertEquals("OTHER", B0Report.why("anything else"))
    }
    @Test fun `vocabulary has no capitals-only entries or single letters`() {
        B0Report.vocabulary.forEach {
            assertTrue(it, it.length >= 2)
            assertNotEquals(it, it.uppercase(), it)
            assertFalse(it, it.any { c -> c.isDigit() })
        }
    }
    @Test fun `random names notes and references never reach the line`() {
        val random = Random(20261125)
        val alphabet = ('A'..'Z').toList() + listOf(' ', '/', '@', '.', '-')
        val capsVocabulary = B0Report.vocabulary.map { it.uppercase() }
        val allowed = Regex("[A-Za-z0-9’'.,!?:\\-\\[\\]=~\"+/@ *()…]*")
        repeat(400) {
            val name = if (random.nextInt(4) == 0) capsVocabulary.random(random)
                else "Q" + (1..random.nextInt(3, 20)).map { alphabet.random(random) }.joinToString("").trimEnd()
            val note = listOf("资金转账", "makan", "share x", capsVocabulary.random(random)).random(random)
            val reference = (1..random.nextInt(9, 20)).map { ('0'..'9').random(random) }.joinToString("")
            val title = listOf(kaching, received, "Transfer $name", "Incoming").random(random)
            val text = listOf("RM${random.nextInt(1, 999)}.${random.nextInt(10, 99)} received from $name for $note",
                "$name has transferred RM${random.nextInt(1, 99)} to you. Tap here to check the transaction details.",
                "You received money from $name ref $reference", "$name $reference").random(random)
            val line = report(title to text).text
            assertTrue(line, allowed.matches(line))
            val tokens = Regex("[A-Za-z’'-]{2,}").findAll(
                line.replace(Regex("\"[^\"]*\"")) { quoted -> quoted.value.replace(Regex("\\[[A-Z?p]+\\]|\\[nl\\]"), " ") })
                .map { it.value }.toList()
            val constants = B0Report.vocabulary + setOf("B0", "AO", "RM", "MYR", "NORULE", "RULE", "FILTER", "OUTGOING",
                "CONFLICT", "MULTI", "NOAMT", "MONEY", "GROUP", "EMPTY", "SENSITIVE", "MALFORMED", "OTHER", "UPPER",
                "Mixed", "lower", "uncased", "Latin", "Han", "Other", "none", "absent", "same", "subText", "summaryText",
                "lines", "rule", "amt", "why", "rem", "name", "note", "bt", "SB", "MF", "tng", "receipt", "en",
                "observed", "kaching", "Ka-ching", "Incoming", "Money", "has", "transferred", "to", "you", "Tap", "here",
                "check", "the", "transaction", "details", "received", "from", "for", "You’ve", "money")
            tokens.forEach { assertTrue("$it in $line", it in constants) }
            assertFalse(line, line.contains(reference))
            // No constant contains "Q", so any fragment with it would be a leak.
            name.split(' ', '/', '@', '.', '-').filter { 'Q' in it }.forEach {
                assertFalse("$it in $line", line.contains(it))
            }
            val digitsLeft = line.replace("B0 v6", "").replace(Regex("n=[0-9]+/[0-9]+|rem=[0-9]+|[+]0"), "")
                .replace("tng.receipt.en.observed.v1", "").replace("tng.receipt.kaching.en.observed.v1", "")
            assertFalse(line, digitsLeft.any { it.isDigit() })
        }
    }
    @Test fun `known wording followed by another line keeps placeholders and hides an all-vocabulary note`() {
        val text = "RM10.00 received from ALI DEMO for transfer money to wallet"
        val session = session()
        session.post(kaching, text, bigText = text + Char(10) + "Tap here to check the transaction details.")
        val line = B0Report.line(session.snapshot(), 6).text
        assertTrue(line, line.contains("o=U e=AO rule=- amt=Y why=NORULE"))
        assertTrue(line, line.contains("b=\"RM[AMOUNT] received from [NAME] for [NOTE]\""))
        // A line after a note may be the note wrapping, so it is redacted too.
        assertTrue(line, line.contains("bt~\"RM[AMOUNT] received from [NAME] for [NOTE][nl][?].\""))
        listOf("transfer money", "wallet", "ALI", "10", "Tap").forEach { assertFalse(it, line.contains(it)) }
    }
    @Test fun `unknown wording redacts vocabulary words in name and note positions`() {
        val line = report("Payment" to "You received money from Fund Money for your wallet").text
        assertTrue(line, line.contains("b~\"You received money from [?] for [?]\""))
        listOf("Fund", "your wallet").forEach { assertFalse(it, line.contains(it)) }
        assertEquals("from [?]", B0Report.skeleton("from Fund Money"))
        assertEquals("by [?]", B0Report.skeleton("by Tap here"))
        assertEquals("for [?][nl][?]", B0Report.skeleton("for transfer money" + Char(10) + "Tap here"))
        assertEquals("from [?][nl]Tap here", B0Report.skeleton("from Fund" + Char(10) + "Tap here"))
    }
    @Test fun `capitalised and labelled triggers open the slots`() {
        assertEquals("Received. [?]", B0Report.skeleton("Received. From: Money Fund. For: payment transfer"))
        assertEquals("[?]", B0Report.skeleton("Note: transfer money"))
        assertEquals("Reference: [?]", B0Report.skeleton("Reference: transfer money"))
        assertEquals("received [?]", B0Report.skeleton("received BY Fund FOR money"))
        assertEquals("Payment [?]", B0Report.skeleton("Payment Remark: wallet"))
    }
    @Test fun `wrapped note in an unknown wording stays redacted on later lines`() {
        val nl = Char(10).toString()
        val line = report("Payment" to "You received money for transfer" + nl + "money to wallet" + nl + "Tap here").text
        assertTrue(line, line.contains("b~\"You received money for [?][nl][?][nl][?]\""))
        assertFalse(line, line.contains("wallet"))
    }
    @Test fun `many line breaks stay bounded and CRLF is one break`() {
        val crlf = Char(13).toString() + Char(10)
        val session = session()
        session.post("Payment", "received" + crlf + "money", bigText = "a" + (Char(10) + "a").repeat(1900))
        val line = B0Report.line(session.snapshot(), 6).text
        assertTrue(line, line.contains("b~\"received[nl]money\""))
        assertTrue(line.length.toString(), line.length < 700)
        val blank = session()
        blank.post("Payment", Char(10).toString().repeat(4000) + "money")
        assertTrue(B0Report.line(blank.snapshot(), 6).text.contains("b~\"money\""))
    }
    @Test fun `a name containing for is still fully redacted`() {
        val line = report(kaching to "RM12.34 received from ALI for BOB for makan").text
        assertTrue(line, line.contains("o=C"))
        assertTrue(line, line.contains("b=\"RM[AMOUNT] received from [NAME] for [NOTE]\""))
        listOf("ALI", "BOB", "makan").forEach { assertFalse(it, line.contains(it)) }
    }
    @Test fun `skeleton edge characters and amount adjacency`() {
        assertEquals("[?]", B0Report.skeleton("RM10.00abc"))
        assertEquals("[?]", B0Report.skeleton("1RM10.00"))
        assertEquals("received RM[AMOUNT].", B0Report.skeleton("received RM10.00."))
        assertEquals("received [?]", B0Report.skeleton("received " + String(Character.toChars(0x1F600))))
        assertEquals("received [?]", B0Report.skeleton("received e" + Char(0x301)))
        assertEquals("received[p]from", B0Report.skeleton("received" + Char(0x202E) + "from"))
        assertEquals("received money", B0Report.skeleton("received" + Char(0xA0) + "money"))
        assertEquals("received[nl][?]", B0Report.skeleton("received" + Char(0x2028) + "ALEX"))
    }
    @Test fun `skeleton collapses runs keeps line breaks and is bounded`() {
        assertEquals("[?] from [?]", B0Report.skeleton("ALEX DEMO, JR from ALI / 123"))
        assertEquals("received[nl][?]", B0Report.skeleton("received\nALEX"))
        assertEquals("[?][p]", B0Report.skeleton("ALEX \"QUOTE\""))
        val long = B0Report.skeleton("the ".repeat(100))
        assertEquals(B0Report.SKELETON_LIMIT + 1, long.length)
        assertTrue(long.endsWith("…"))
    }
}
