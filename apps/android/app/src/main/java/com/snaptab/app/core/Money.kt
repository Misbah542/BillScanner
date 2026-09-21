package com.snaptab.app.core

import java.text.NumberFormat
import java.util.Currency
import java.util.Locale
import kotlin.math.abs

/**
 * Money on this side of the wire is the same integer count of minor units the API
 * and Postgres use — paise for INR. Nothing is ever held as a Double.
 *
 * The old app formatted with `DecimalFormat("#,##0.00")` and a hardcoded "₹" in six
 * different places, which put the symbol on the wrong side for half the world's
 * locales and grouped digits the Western way even in India.
 */
object Money {

    private const val DEFAULT_CURRENCY = "INR"

    private fun exponentOf(currency: String): Int = when (currency.uppercase(Locale.ROOT)) {
        "BHD", "IQD", "JOD", "KWD", "OMR", "TND" -> 3
        "CLP", "ISK", "JPY", "KRW", "VND" -> 0
        else -> 2
    }

    /** `format(257500)` → "₹2,575.00" in en-IN, with correct lakh grouping. */
    fun format(
        minor: Long,
        currency: String = DEFAULT_CURRENCY,
        locale: Locale = Locale.getDefault(),
        showDecimals: Boolean = true
    ): String {
        val exponent = exponentOf(currency)
        val formatter = NumberFormat.getCurrencyInstance(locale).apply {
            runCatching { this.currency = Currency.getInstance(currency.uppercase(Locale.ROOT)) }
            minimumFractionDigits = if (showDecimals) exponent else 0
            maximumFractionDigits = if (showDecimals) exponent else 0
        }
        val divisor = Math.pow(10.0, exponent.toDouble())
        return formatter.format(minor / divisor)
    }

    /**
     * Drops the paise when they are zero: "₹2,575" rather than "₹2,575.00". Used in
     * lists and on notifications, where the decimals are noise.
     */
    fun formatCompact(minor: Long, currency: String = DEFAULT_CURRENCY, locale: Locale = Locale.getDefault()): String {
        val exponent = exponentOf(currency)
        val unit = Math.pow(10.0, exponent.toDouble()).toLong()
        return format(minor, currency, locale, showDecimals = minor % unit != 0L)
    }

    /** Signed, for a ledger row: "+₹643" or "−₹486" with a real minus sign. */
    fun formatSigned(minor: Long, currency: String = DEFAULT_CURRENCY, locale: Locale = Locale.getDefault()): String {
        val magnitude = formatCompact(abs(minor), currency, locale)
        return when {
            minor > 0 -> "+$magnitude"
            minor < 0 -> "−$magnitude"
            else -> magnitude
        }
    }

    /** What goes in a text field the user then edits: a plain "2575.00", no symbol. */
    fun toEditable(minor: Long, currency: String = DEFAULT_CURRENCY): String {
        val exponent = exponentOf(currency)
        if (exponent == 0) return minor.toString()
        val negative = minor < 0
        val digits = abs(minor).toString().padStart(exponent + 1, '0')
        val whole = digits.dropLast(exponent)
        val fraction = digits.takeLast(exponent)
        return "${if (negative) "-" else ""}$whole.$fraction"
    }

    /**
     * Parses what the user typed. Returns null rather than throwing or guessing — the
     * caller shows a field error instead of quietly saving a wrong amount.
     */
    fun parseOrNull(text: String, currency: String = DEFAULT_CURRENCY): Long? {
        val cleaned = text.trim().replace(" ", "").replace(" ", "")
            .replace("₹", "").replace(",", "")
        if (cleaned.isEmpty()) return null
        val match = Regex("^(-)?(\\d*)(?:\\.(\\d{0,6}))?$").matchEntire(cleaned) ?: return null
        val (sign, whole, fractionRaw) = match.destructured
        if (whole.isEmpty() && fractionRaw.isEmpty()) return null

        val exponent = exponentOf(currency)
        val fraction = fractionRaw.padEnd(exponent, '0')
        val kept = fraction.take(exponent)
        // More precision than the currency has: round half up.
        val roundUp = fraction.length > exponent && fraction[exponent].digitToIntOrNull()?.let { it >= 5 } == true

        val wholeValue = (whole.ifEmpty { "0" }).toLongOrNull() ?: return null
        val unit = Math.pow(10.0, exponent.toDouble()).toLong()
        val value = wholeValue * unit + (kept.ifEmpty { "0" }).toLong() + if (roundUp) 1 else 0
        return if (sign == "-") -value else value
    }

    /** Basis points as a percentage string: 2500 → "25%", 3333 → "33.33%". */
    fun formatBasisPoints(bp: Int): String {
        val whole = bp / 100
        val remainder = bp % 100
        return when {
            remainder == 0 -> "$whole%"
            remainder % 10 == 0 -> "$whole.${remainder / 10}%"
            else -> "$whole.${remainder.toString().padStart(2, '0')}%"
        }
    }

    /**
     * Splits `totalMinor` in the given weight ratio with no minor unit lost or
     * invented, by the same largest-remainder method the server and packages/shared
     * use. The client needs it to preview a split before saving one, and the preview
     * must agree with what the server will compute to the paisa.
     */
    fun apportion(totalMinor: Long, weights: List<Long>, preferIndex: Int = 0): List<Long> {
        if (weights.isEmpty()) return emptyList()
        if (weights.any { it < 0 }) return weights.map { 0L }

        val weightTotal = weights.sum()
        if (weightTotal == 0L) return apportion(totalMinor, weights.map { 1L }, preferIndex)

        val negative = totalMinor < 0
        val magnitude = abs(totalMinor)

        val exact = weights.map { magnitude.toDouble() * it / weightTotal }
        val shares = exact.map { kotlin.math.floor(it).toLong() }.toMutableList()
        var leftover = magnitude - shares.sum()

        val order = exact.indices.sortedWith(
            compareByDescending<Int> { exact[it] - kotlin.math.floor(exact[it]) }
                .thenBy { if (it == preferIndex) 0 else 1 }
                .thenBy { it }
        )

        var cursor = 0
        while (leftover > 0 && order.isNotEmpty()) {
            val index = order[cursor % order.size]
            if (weights[index] > 0 || weights.all { it == 0L }) {
                shares[index] = shares[index] + 1
                leftover -= 1
            }
            cursor += 1
            // Guard against an impossible loop if every weight is zero.
            if (cursor > order.size * (magnitude + 1)) break
        }

        return if (negative) shares.map { -it } else shares
    }
}
