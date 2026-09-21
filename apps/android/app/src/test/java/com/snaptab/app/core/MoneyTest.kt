package com.snaptab.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * The client's money arithmetic has to agree with the server's to the paisa, because the
 * split screen previews a split before saving it and a preview that disagrees with what
 * gets saved is worse than no preview.
 */
class MoneyTest {

    @Test
    fun `parses what a person would type`() {
        assertEquals(257_500L, Money.parseOrNull("2575.00"))
        assertEquals(257_500L, Money.parseOrNull("2,575"))
        assertEquals(48_600L, Money.parseOrNull("486"))
        assertEquals(1L, Money.parseOrNull("0.01"))
        assertEquals(64_375L, Money.parseOrNull("643.75"))
        assertEquals(257_500L, Money.parseOrNull(" ₹ 2575.00 "))
    }

    @Test
    fun `rounds half up when given more precision than paise`() {
        assertEquals(1001L, Money.parseOrNull("10.005"))
        assertEquals(1000L, Money.parseOrNull("10.004"))
    }

    @Test
    fun `returns null rather than guessing at junk`() {
        assertNull(Money.parseOrNull(""))
        assertNull(Money.parseOrNull("abc"))
        assertNull(Money.parseOrNull("12.3.4"))
        assertNull(Money.parseOrNull("."))
    }

    @Test
    fun `round-trips through the editable form`() {
        for (value in listOf(0L, 1L, 99L, 100L, 64_375L, 257_500L, 99_999_999L)) {
            assertEquals(value, Money.parseOrNull(Money.toEditable(value)))
        }
    }

    @Test
    fun `formats with the locale's own grouping`() {
        // 257500 paise is 2,575 rupees, and en-IN groups by lakh.
        val formatted = Money.format(257_500L, "INR", Locale("en", "IN"))
        assertTrue(formatted, formatted.contains("2,575.00"))
    }

    @Test
    fun `compact form drops the paise only when they are zero`() {
        assertEquals("₹2,575", Money.formatCompact(257_500L, "INR", Locale("en", "IN")).replace(" ", ""))
        assertTrue(Money.formatCompact(257_501L, "INR", Locale("en", "IN")).contains("2,575.01"))
    }

    @Test
    fun `signed form uses a real minus sign`() {
        assertTrue(Money.formatSigned(-48_600L, "INR", Locale("en", "IN")).startsWith("−"))
        assertTrue(Money.formatSigned(48_600L, "INR", Locale("en", "IN")).startsWith("+"))
        assertTrue(Money.formatSigned(0L, "INR", Locale("en", "IN")).first().isDigit().not())
    }

    @Test
    fun `apportion never loses or invents a paisa`() {
        for (total in listOf(1L, 7L, 100L, 257_500L, 999_999L)) {
            for (people in listOf(1, 2, 3, 4, 7, 11)) {
                val shares = Money.apportion(total, List(people) { 1L })
                assertEquals("$total across $people", total, shares.sum())
            }
        }
    }

    @Test
    fun `apportion spreads the remainder one paisa at a time`() {
        // 100 across 3 is 34 + 33 + 33, never 34 + 34 + 32.
        assertEquals(listOf(34L, 33L, 33L), Money.apportion(100L, listOf(1L, 1L, 1L)))
    }

    @Test
    fun `the payer takes the odd paisa`() {
        assertEquals(listOf(33L, 33L, 34L), Money.apportion(100L, listOf(1L, 1L, 1L), preferIndex = 2))
    }

    @Test
    fun `apportion respects weights, matching the server's percentage split`() {
        assertEquals(listOf(750L, 250L), Money.apportion(1000L, listOf(3L, 1L)))
        // The same figures the API's PERCENT test asserts: 35/25/22/18 of 2575.00.
        assertEquals(
            listOf(90_125L, 64_375L, 56_650L, 46_350L),
            Money.apportion(257_500L, listOf(35L, 25L, 22L, 18L))
        )
    }

    @Test
    fun `apportion skips people carrying no weight`() {
        assertEquals(listOf(50L, 0L, 50L), Money.apportion(100L, listOf(1L, 0L, 1L)))
    }

    @Test
    fun `apportion falls back to an even split when every weight is zero`() {
        assertEquals(100L, Money.apportion(100L, listOf(0L, 0L, 0L)).sum())
    }

    @Test
    fun `apportion handles a refund exactly`() {
        val shares = Money.apportion(-100L, listOf(1L, 1L, 1L))
        assertEquals(-100L, shares.sum())
        assertEquals(listOf(-34L, -33L, -33L), shares)
    }

    @Test
    fun `basis points read as a percentage`() {
        assertEquals("25%", Money.formatBasisPoints(2500))
        assertEquals("33.33%", Money.formatBasisPoints(3333))
        assertEquals("12.5%", Money.formatBasisPoints(1250))
        assertEquals("100%", Money.formatBasisPoints(10_000))
    }

    @Test
    fun `currencies with other exponents keep their own precision`() {
        assertEquals(1000L, Money.parseOrNull("1000", "JPY"))
        assertEquals("1000", Money.toEditable(1000L, "JPY"))
    }
}
