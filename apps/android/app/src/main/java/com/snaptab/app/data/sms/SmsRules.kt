package com.snaptab.app.data.sms

import android.content.Context
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The bank-alert rules, loaded from `assets/shared/sms-rules.json`.
 *
 * That file is a copy of `packages/shared/data/sms-rules.json`, kept in step by
 * `npm run sync:assets`, so the Kotlin parser on the phone and the TypeScript one on
 * the server are driven by the same table. A new bank's message format is one JSON
 * edit in one place, and the server's `/v1/alerts/rules/version` tells the app when
 * its copy is behind.
 */
@Serializable
data class SmsRuleSet(
    val version: Int,
    val reject: List<String> = emptyList(),
    val debitMarkers: List<String> = emptyList(),
    val creditMarkers: List<String> = emptyList(),
    val amount: List<String> = emptyList(),
    val account: List<String> = emptyList(),
    val merchant: List<String> = emptyList(),
    val reference: List<String> = emptyList(),
    val balance: List<String> = emptyList(),
    val banks: List<BankRule> = emptyList(),
    val senderShape: String = "^(?:[A-Z]{2}-)?(?<token>[A-Z]{2,12})(?:-[A-Z])?$",
    val cardKindHints: Map<String, List<String>> = emptyMap()
) {
    @Serializable
    data class BankRule(val id: String, val name: String, val tokens: List<String>)
}

/** The compiled form, built once and reused for every message. */
class CompiledSmsRules(raw: SmsRuleSet) {
    val version: Int = raw.version
    val reject: List<Regex> = raw.reject.map { it.toIgnoreCaseRegex() }
    val debitMarkers: List<Regex> = raw.debitMarkers.map { it.toIgnoreCaseRegex() }
    val creditMarkers: List<Regex> = raw.creditMarkers.map { it.toIgnoreCaseRegex() }
    val amount: List<Regex> = raw.amount.map { it.toIgnoreCaseRegex() }
    val account: List<Regex> = raw.account.map { it.toIgnoreCaseRegex() }
    val merchant: List<Regex> = raw.merchant.map { it.toIgnoreCaseRegex() }
    val reference: List<Regex> = raw.reference.map { it.toIgnoreCaseRegex() }
    val balance: List<Regex> = raw.balance.map { it.toIgnoreCaseRegex() }
    val senderShape: Regex = Regex(raw.senderShape)
    val banks: List<SmsRuleSet.BankRule> = raw.banks
    val cardKindHints: List<Pair<String, List<Regex>>> =
        raw.cardKindHints.map { (kind, patterns) -> kind to patterns.map { it.toIgnoreCaseRegex() } }

    private companion object {
        /**
         * A pattern that will not compile is dropped rather than crashing the receiver.
         * A broadcast receiver that throws takes the SMS app's delivery with it.
         */
        fun String.toIgnoreCaseRegex(): Regex = Regex(this, RegexOption.IGNORE_CASE)
    }
}

@Singleton
class SmsRulesLoader @Inject constructor(
    private val context: Context,
    private val json: Json
) {
    @Volatile
    private var cached: CompiledSmsRules? = null

    fun rules(): CompiledSmsRules = cached ?: synchronized(this) {
        cached ?: load().also { cached = it }
    }

    private fun load(): CompiledSmsRules {
        val raw = runCatching {
            context.assets.open(ASSET_PATH).bufferedReader().use { it.readText() }
        }.getOrNull()

        val parsed = raw
            ?.let { runCatching { json.decodeFromString(SmsRuleSet.serializer(), it) }.getOrNull() }
            ?: SmsRuleSet(version = 0)

        // Individually compile, so one bad pattern in a shipped rule file cannot stop
        // every other rule from working.
        val safe = parsed.copy(
            reject = parsed.reject.filter { it.compiles() },
            debitMarkers = parsed.debitMarkers.filter { it.compiles() },
            creditMarkers = parsed.creditMarkers.filter { it.compiles() },
            amount = parsed.amount.filter { it.compiles() },
            account = parsed.account.filter { it.compiles() },
            merchant = parsed.merchant.filter { it.compiles() },
            reference = parsed.reference.filter { it.compiles() },
            balance = parsed.balance.filter { it.compiles() },
            cardKindHints = parsed.cardKindHints.mapValues { (_, v) -> v.filter { it.compiles() } }
        )
        return CompiledSmsRules(safe)
    }

    private fun String.compiles(): Boolean = runCatching { Regex(this) }.isSuccess

    private companion object {
        const val ASSET_PATH = "shared/sms-rules.json"
    }
}
