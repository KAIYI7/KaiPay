package dev.kaipay.probe

import java.math.BigDecimal
import java.util.Locale

enum class ClassificationOutcome { INCOMING_CANDIDATE, OUTGOING_OR_UNRELATED, AMBIGUOUS, UNKNOWN }
enum class TransactionDirection { INCOMING, OUTGOING, UNKNOWN }
enum class EvidenceQuality { NONE, AMOUNT_ONLY, RULE_MATCHED }

/** Evidence quality describes notification structure, never settlement authenticity. */
data class Classification(
    val outcome: ClassificationOutcome,
    val amountMinor: Long? = null,
    val currency: String? = null,
    val direction: TransactionDirection = TransactionDirection.UNKNOWN,
    val reference: String? = null,
    val confidence: EvidenceQuality = EvidenceQuality.NONE,
    val explanation: String,
    val ruleId: String? = null
) {
    override fun toString() = "Classification(outcome=$outcome, direction=$direction, confidence=$confidence, ruleId=$ruleId)"
}

/** A positive rule must be deliberately supplied from reviewed observed wording. */
class IncomingRule(val id: String, val matches: (Map<String, String>, List<String>) -> Boolean) {
    init { require(id.matches(Regex("[a-zA-Z0-9._-]{1,64}"))) }
}
class ClassificationProfile(rules: List<IncomingRule>) {
    val incomingRules = rules.toList()
    companion object { val EMPTY = ClassificationProfile(emptyList()) }
}

object NotificationClassifier {
    private val outgoing = Regex("\\b(?:sent|paid|spent|debited|withdrawn|outgoing|purchase)\\b|" +
        "\\b(?:payment|transfer)\\s+(?:to|sent)\\b", RegexOption.IGNORE_CASE)
    private val unrelated = Regex("\\b(?:promo(?:tion)?|offer|cashback|discount|voucher|reward|win|" +
        "request(?:ed)?|reminder|example|sample|tutorial|test|pending|failed|declined|reversed|refund|" +
        "cancelled|canceled|unsuccessful|rejected|aborted)\\b|" +
        "\\b(?:how to|terms apply|up to|balance|not received|did not receive|haven.t received|no payment|" +
        "will receive|may receive|can receive|could receive|would receive|not successful|not completed|" +
        "not complete|not confirmed|not processed|did not succeed|has not succeeded|has not completed)\\b", RegexOption.IGNORE_CASE)

    fun classify(fields: Map<String, String>, textLines: List<String> = emptyList(), groupSummary: Boolean = false,
        profile: ClassificationProfile = ClassificationProfile.EMPTY): Classification {
        val strings = (fields.values + textLines).filter { it.isNotBlank() }.distinct()
        if (strings.isEmpty() || strings.all { it.trim().lowercase(Locale.ROOT) in
                setOf("hidden", "content hidden", "redacted", "[redacted]", "notification") })
            return result(ClassificationOutcome.UNKNOWN, "No usable notification text is available.")
        val money = MonetaryParser.parse(strings)
        if (money.problem != null) return result(ClassificationOutcome.AMBIGUOUS, money.problem)
        if (groupSummary) return result(ClassificationOutcome.UNKNOWN, "Grouped summary cannot identify an individual receipt.")
        val joined = strings.joinToString("\n")
        val positive = profile.incomingRules.filter { it.matches(fields, textLines) }
        if (outgoing.containsMatchIn(joined)) return if (positive.isNotEmpty())
            result(ClassificationOutcome.AMBIGUOUS, "Incoming and outgoing indicators conflict.")
        else Classification(ClassificationOutcome.OUTGOING_OR_UNRELATED, money.amount, money.currency,
            TransactionDirection.OUTGOING, confidence = quality(money), explanation = "Outgoing transaction indicators are present.")
        if (unrelated.containsMatchIn(joined)) return result(ClassificationOutcome.OUTGOING_OR_UNRELATED,
            "Informational, promotional, request, or non-receipt indicators are present.")
        if (positive.size > 1) return result(ClassificationOutcome.AMBIGUOUS, "Multiple incoming rules match.")
        if (positive.isNotEmpty() && money.amount == null) return result(ClassificationOutcome.AMBIGUOUS,
            "Incoming wording matches but an exact supported amount is absent.")
        if (positive.isNotEmpty()) return Classification(ClassificationOutcome.INCOMING_CANDIDATE,
            money.amount, "MYR", TransactionDirection.INCOMING, confidence = EvidenceQuality.RULE_MATCHED,
            explanation = "A configured notification rule matches; independent transaction verification is required.",
            ruleId = positive.single().id)
        return Classification(ClassificationOutcome.UNKNOWN, money.amount, money.currency, confidence = quality(money),
            explanation = "No reviewed incoming notification rule matches; amount alone is insufficient.")
    }

    private fun quality(money: MoneyResult) = if (money.amount == null) EvidenceQuality.NONE else EvidenceQuality.AMOUNT_ONLY
    private fun result(outcome: ClassificationOutcome, explanation: String) = Classification(outcome, explanation = explanation)
}

internal data class MoneyResult(val amount: Long? = null, val currency: String? = null, val problem: String? = null)

/** Whole monetary tokens are validated; partial valid-looking prefixes are never accepted. */
internal object MonetaryParser {
    private val token = Regex("(?<![\\p{L}])(?:MYR|RM)\\s*([^\\s;:!?()\\[\\]{}]+)", RegexOption.IGNORE_CASE)
    private val exact = Regex("(?:0|[1-9][0-9]*|[1-9][0-9]{0,2}(?:,[0-9]{3})+)(?:\\.[0-9]{2})?")
    private val unsupported = Regex("(?<![\\p{L}])(?:USD|SGD|EUR|GBP|AUD|IDR|THB|CNY|JPY|INR|KRW|HKD|CAD|" +
        "CHF|NZD|ZAR|AED|SAR|PHP|VND|TWD|BND|BTC|ETH|USDT|USDC)(?![\\p{L}])|[$€£¥]",
        RegexOption.IGNORE_CASE)
    // Code-shaped monetary prefixes/suffixes fail closed, including unknown lowercase codes.
    // A small prose allowlist avoids treating "for 2" or "2.00 for" as a currency.
    private val otherCurrencyCode = Regex("\\b([A-Z]{3})\\s*[+-]?[0-9]", RegexOption.IGNORE_CASE)
    private val otherCurrencySuffix = Regex("[0-9]\\s*([A-Z]{3})(?![\\p{L}])", RegexOption.IGNORE_CASE)
    private val prose = setOf("for", "and", "the", "via", "not", "was", "has", "had", "its", "our", "you",
        "new", "are", "can", "may", "did", "how")
    private fun unsupportedCode(code: String): Boolean = !code.equals("MYR", ignoreCase = true) &&
        (code != code.lowercase(Locale.ROOT) || code.lowercase(Locale.ROOT) !in prose)
    private val suffixCurrency = Regex("[0-9]\\s*(?:MYR|RM)\\b", RegexOption.IGNORE_CASE)
    private val bareCurrency = Regex("(?<![\\p{L}])(?:MYR|RM)(?![\\p{L}])", RegexOption.IGNORE_CASE)

    fun validMyrSpans(text: String): List<IntRange> = token.findAll(text).filter {
        minor(normalize(it.groupValues[1])) != null
    }.map { it.range }.toList()

    fun parse(texts: List<String>): MoneyResult {
        val amounts = linkedSetOf<Long>()
        for (text in texts) {
            if (unsupported.containsMatchIn(text)) return MoneyResult(problem = "An unsupported currency is present.")
            if (otherCurrencyCode.findAll(text).any { unsupportedCode(it.groupValues[1]) })
                return MoneyResult(problem = "An unsupported currency code is present.")
            if (otherCurrencySuffix.findAll(text).any { unsupportedCode(it.groupValues[1]) })
                return MoneyResult(problem = "An unsupported currency suffix is present.")
            if (suffixCurrency.containsMatchIn(text)) return MoneyResult(problem = "Unsupported monetary formatting is present.")
            val tokens = token.findAll(text).toList()
            if (bareCurrency.findAll(text).count() != tokens.size)
                return MoneyResult(problem = "Currency marker has no valid amount.")
            for (match in tokens) {
                if (Regex("^\\s+[0-9.]").containsMatchIn(text.substring(match.range.last + 1)))
                    return MoneyResult(problem = "A monetary token is split or followed by another numeric value.")
                val amount = minor(normalize(match.groupValues[1]))
                    ?: return MoneyResult(problem = "Monetary value is malformed, non-positive, or out of range.")
                amounts.add(amount)
            }
        }
        if (amounts.size > 1) return MoneyResult(problem = "Conflicting monetary values are present.")
        return amounts.singleOrNull()?.let { MoneyResult(it, "MYR") } ?: MoneyResult()
    }

    // A sentence-ending period is accepted only after a complete decimal amount.
    private fun normalize(raw: String) = if (Regex(".*\\.[0-9]{2}\\.").matches(raw)) raw.dropLast(1) else raw
    private fun minor(raw: String): Long? {
        if (!exact.matches(raw)) return null
        return try { BigDecimal(raw.replace(",", "")).movePointRight(2).longValueExact().takeIf { it > 0 } }
        catch (_: ArithmeticException) { null }
        catch (_: NumberFormatException) { null }
    }
}
