package com.snaptab.app.data.sms

import com.snaptab.app.core.Money
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What a parsed bank alert looks like on the device. Only these fields ever leave the
 * phone: the message body stays local unless the user explicitly opts in.
 */
data class ParsedAlert(
    val direction: Direction,
    val amountMinor: Long,
    val currency: String = "INR",
    val merchantRaw: String? = null,
    val merchantNormalized: String? = null,
    /** Last 3–6 digits only. A full account number is never kept. */
    val accountMask: String? = null,
    val accountKind: AccountKind = AccountKind.UNKNOWN,
    val bankId: String? = null,
    val bankName: String? = null,
    val referenceNumber: String? = null,
    val balanceMinor: Long? = null,
    /** 0..1, how much of the message was understood. */
    val confidence: Double,
    val fingerprint: String,
    val occurredAt: Instant,
    /** Kept in memory only; sent up only when the user turned that on. */
    val rawBody: String
) {
    enum class Direction { DEBIT, CREDIT }
    enum class AccountKind { ACCOUNT, DEBIT_CARD, CREDIT_CARD, WALLET, UNKNOWN }

    val occurredAtIso: String
        get() = DateTimeFormatter.ISO_INSTANT.format(occurredAt.atOffset(ZoneOffset.UTC))
}

/**
 * Parses one SMS into a transaction, or refuses.
 *
 * Refusing matters more than parsing: a false positive silently invents an expense in
 * someone's month, so an OTP, a due-date reminder, a collect request, a promo, a
 * balance enquiry and a failed or reversed payment are all rejected before anything
 * else is attempted.
 *
 * This is the exact same sequence the TypeScript parser in packages/shared runs, off
 * the same rule file, so the server and the phone always agree — including on the
 * fingerprint, which is what stops a re-read inbox from doubling every expense.
 */
@Singleton
class BankAlertParser @Inject constructor(private val loader: SmsRulesLoader) {

    val rulesVersion: Int get() = loader.rules().version

    fun parse(body: String, sender: String?, receivedAt: Instant = Instant.now()): ParsedAlert? {
        if (body.isBlank() || body.trim().length < 12) return null
        val rules = loader.rules()
        val text = body.replace(Regex("\\s+"), " ").trim()

        if (rules.reject.any { it.containsMatchIn(text) }) return null

        val debit = rules.debitMarkers.any { it.containsMatchIn(text) }
        val credit = rules.creditMarkers.any { it.containsMatchIn(text) }
        if (!debit && !credit) return null
        // "Sent Rs.500 … credited to beneficiary": the debit verb is about the user's own
        // account, which is the side being recorded, so it wins.
        val direction = if (debit) ParsedAlert.Direction.DEBIT else ParsedAlert.Direction.CREDIT

        val amountText = firstGroup(text, rules.amount, "amount") ?: return null
        val amountMinor = Money.parseOrNull(amountText.replace(",", "")) ?: return null
        if (amountMinor <= 0) return null

        val accountMask = firstGroup(text, rules.account, "mask")
        val merchantRaw = cleanMerchant(firstGroup(text, rules.merchant, "merchant"))
        val reference = firstGroup(text, rules.reference, "ref")
        val balanceMinor = firstGroup(text, rules.balance, "balance")
            ?.let { Money.parseOrNull(it.replace(",", "")) }

        val accountKind = rules.cardKindHints
            .firstOrNull { (_, patterns) -> patterns.any { it.containsMatchIn(text) } }
            ?.first
            ?.let { runCatching { ParsedAlert.AccountKind.valueOf(it) }.getOrNull() }
            ?: ParsedAlert.AccountKind.UNKNOWN

        val bank = detectBank(rules, text, sender)

        // Confidence is simply how many of the five useful fields came through.
        var understood = 2.0 // direction and amount are already known good
        if (accountMask != null) understood += 1
        if (merchantRaw != null) understood += 1
        if (reference != null) understood += 0.5
        if (bank != null) understood += 0.5
        val confidence = (understood / 5.0).coerceAtMost(1.0).roundTo2()

        val normalized = merchantRaw?.let { normalizeMerchant(it) }

        return ParsedAlert(
            direction = direction,
            amountMinor = amountMinor,
            merchantRaw = merchantRaw,
            merchantNormalized = normalized,
            accountMask = accountMask,
            accountKind = accountKind,
            bankId = bank?.id,
            bankName = bank?.name,
            referenceNumber = reference,
            balanceMinor = balanceMinor,
            confidence = confidence,
            fingerprint = fingerprintOf(
                direction = direction,
                amountMinor = amountMinor,
                accountMask = accountMask,
                referenceNumber = reference,
                merchantNormalized = normalized,
                receivedAt = receivedAt
            ),
            occurredAt = receivedAt,
            rawBody = body
        )
    }

    /**
     * The de-duplication key, computed identically here and on the server: exact on the
     * bank's own reference number when there is one, otherwise amount + account +
     * merchant + the day. Two same-amount debits on different days stay separate; the
     * same alert read twice does not.
     */
    fun fingerprintOf(
        direction: ParsedAlert.Direction,
        amountMinor: Long,
        accountMask: String?,
        referenceNumber: String?,
        merchantNormalized: String?,
        receivedAt: Instant
    ): String {
        val day = receivedAt.atOffset(ZoneOffset.UTC).toLocalDate().toString()
        val key = if (referenceNumber != null) {
            listOf("ref", referenceNumber.lowercase(), amountMinor.toString()).joinToString("|")
        } else {
            listOf(
                "heur",
                direction.name,
                amountMinor.toString(),
                accountMask ?: "-",
                merchantNormalized ?: "-",
                day
            ).joinToString("|")
        }
        return djb2(key)
    }

    /**
     * Strips the payment-rail decoration banks wrap a merchant in, so the same shop
     * matches itself across channels: `UPI/SWIGGY*ORDER/HDFC` and `SWIGGY` both become
     * `swiggy`. Mirrors normalizeMerchant() in packages/shared.
     */
    fun normalizeMerchant(raw: String): String {
        var text = raw.lowercase().replace(Regex("\\s+"), " ").trim()
        text = text.replace(
            Regex("^(upi|pos|imps|neft|ach|nach|ecs|atw|vps|mmt|bil|inf)[/\\-* ]+"),
            ""
        )
        if (text.contains('*')) text = text.substringBefore('*')
        text = text.replace(
            Regex("[/\\-*](upi|pos|hdfc|icici|sbi|axis|kotak|ybl|okhdfcbank|okaxis|oksbi|paytm|ibl|apl)\\b.*$"),
            ""
        )
        text = text.replace(
            Regex("\\b(pvt|private|ltd|limited|llp|inc|corp|co|company|india|in|pmts|payments|online|store|stores|retail|enterprises)\\b"),
            " "
        )
        text = text.replace(Regex("\\b\\d{4,}\\b"), " ")
        text = text.replace(Regex("[^a-z0-9 &']"), " ")
        return text.replace(Regex("\\s+"), " ").trim()
    }

    private fun detectBank(
        rules: CompiledSmsRules,
        text: String,
        sender: String?
    ): SmsRuleSet.BankRule? {
        val haystacks = buildList {
            if (!sender.isNullOrBlank()) {
                val token = rules.senderShape.find(sender.uppercase())?.groups?.get("token")?.value
                add((token ?: sender).lowercase())
                add(sender.lowercase())
            }
            add(text.lowercase())
        }
        for (haystack in haystacks) {
            rules.banks.firstOrNull { bank -> bank.tokens.any { haystack.contains(it) } }
                ?.let { return it }
        }
        return null
    }

    private fun firstGroup(text: String, patterns: List<Regex>, group: String): String? {
        for (pattern in patterns) {
            val value = runCatching {
                pattern.find(text)?.groups?.get(group)?.value
            }.getOrNull()
            if (!value.isNullOrBlank()) return value.trim()
        }
        return null
    }

    private fun cleanMerchant(raw: String?): String? {
        if (raw == null) return null
        var text = raw.trim().trimEnd('.', ',', ';', ':', '-', '*', ' ')
        // A bare date or amount caught by a greedy pattern is not a merchant.
        if (Regex("^\\d[\\d\\s.,/-]*$").matches(text)) return null
        text = text.replace(Regex("\\s+"), " ")
        return if (text.length < 3 || text.length > 64) null else text
    }

    private fun Double.roundTo2(): Double = Math.round(this * 100.0) / 100.0

    private fun djb2(input: String): String {
        var hash = 5381
        for (char in input) hash = (hash shl 5) + hash + char.code
        return (hash.toLong() and 0xFFFFFFFFL).toString(16).padStart(8, '0')
    }
}
