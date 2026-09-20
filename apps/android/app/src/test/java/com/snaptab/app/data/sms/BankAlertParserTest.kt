package com.snaptab.app.data.sms

import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/**
 * The device-side parser, tested against the same messages the TypeScript one is, because
 * the two are driven by the same rule file and must never disagree — the fingerprint they
 * compute is what stops a re-read inbox from doubling every expense.
 *
 * Robolectric is here only to supply a Context so the rule file can be read from assets.
 */
@RunWith(RobolectricTestRunner::class)
class BankAlertParserTest {

    private lateinit var parser: BankAlertParser
    private val receivedAt: Instant = Instant.parse("2026-09-20T07:42:00Z")

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        parser = BankAlertParser(SmsRulesLoader(context, Json { ignoreUnknownKeys = true }))
    }

    // ------------------------------------------------------------- real messages ---

    @Test
    fun `reads an HDFC UPI debit`() {
        val parsed = parser.parse(
            "Sent Rs.486.00 from HDFC Bank A/C x4471 to SWIGGY on 20-09-26. UPI Ref 528113094412. Not you? Call 18002586161",
            sender = "VM-HDFCBK",
            receivedAt = receivedAt
        )
        assertNotNull(parsed)
        requireNotNull(parsed)
        assertEquals(ParsedAlert.Direction.DEBIT, parsed.direction)
        assertEquals(48_600L, parsed.amountMinor)
        assertEquals("4471", parsed.accountMask)
        assertEquals("SWIGGY", parsed.merchantRaw)
        assertEquals("swiggy", parsed.merchantNormalized)
        assertEquals("528113094412", parsed.referenceNumber)
        assertEquals("hdfc", parsed.bankId)
        assertTrue(parsed.confidence > 0.8)
    }

    @Test
    fun `reads an ICICI credit-card spend and spots the card`() {
        val parsed = parser.parse(
            "INR 12,499.00 spent on ICICI Bank Card XX9082 on 19-Sep-26 at CROMA. Avl Lmt: INR 87,501.00",
            sender = "AD-ICICIB",
            receivedAt = receivedAt
        )
        requireNotNull(parsed)
        assertEquals(1_249_900L, parsed.amountMinor)
        assertEquals("9082", parsed.accountMask)
        assertEquals(ParsedAlert.AccountKind.CREDIT_CARD, parsed.accountKind)
        assertEquals("CROMA", parsed.merchantRaw)
        assertEquals("icici", parsed.bankId)
    }

    @Test
    fun `reads a UPI credit and keeps the payer's handle`() {
        val parsed = parser.parse(
            "Credited Rs.643.00 to HDFC Bank A/C x4471 from ROHANKAMAT@okhdfcbank on 20-09-26. UPI Ref 528007712330",
            sender = "VM-HDFCBK",
            receivedAt = receivedAt
        )
        requireNotNull(parsed)
        assertEquals(ParsedAlert.Direction.CREDIT, parsed.direction)
        assertEquals(64_300L, parsed.amountMinor)
        assertEquals("ROHANKAMAT@okhdfcbank", parsed.merchantRaw)
    }

    @Test
    fun `reads an SBI debit with a narration and the balance after it`() {
        val parsed = parser.parse(
            "Dear Customer, Rs.2575.00 debited from A/c XXXXX1234 on 19-09-26. Info: POS/SOCIAL OFFLINE BANDRA. Avl Bal Rs.41,225.50 -SBI",
            sender = "JD-SBIINB",
            receivedAt = receivedAt
        )
        requireNotNull(parsed)
        assertEquals(257_500L, parsed.amountMinor)
        assertEquals("1234", parsed.accountMask)
        assertEquals("social offline bandra", parsed.merchantNormalized)
        assertEquals(4_122_550L, parsed.balanceMinor)
        assertEquals("sbi", parsed.bankId)
    }

    @Test
    fun `handles the rupee symbol and a merchant with punctuation`() {
        val parsed = parser.parse(
            "You have paid ₹1,198.00 to ZUDIO - PHOENIX MALL from Kotak Bank A/c XX5566. Ref 8812771221",
            sender = "VK-KOTAKB",
            receivedAt = receivedAt
        )
        requireNotNull(parsed)
        assertEquals(119_800L, parsed.amountMinor)
        assertEquals("ZUDIO - PHOENIX MALL", parsed.merchantRaw)
        assertEquals("kotak", parsed.bankId)
    }

    @Test
    fun `reads an ATM withdrawal`() {
        val parsed = parser.parse(
            "INR 5000 withdrawn from Axis Bank A/c XX7788 at ATM on 18-09-26. Avl Bal INR 12000.00",
            sender = "AX-AXISBK",
            receivedAt = receivedAt
        )
        requireNotNull(parsed)
        assertEquals(500_000L, parsed.amountMinor)
        assertEquals(ParsedAlert.AccountKind.DEBIT_CARD, parsed.accountKind)
    }

    // ----------------------------------------------------- what it must refuse ---

    @Test
    fun `refuses everything that is not a completed transaction`() {
        val rejected = mapOf(
            "an OTP" to "123456 is your OTP for a transaction of Rs.2000 on HDFC Bank Card xx4471. Do not share it with anyone.",
            "a future debit" to "Rs.1,299.00 will be debited from your A/c xx4471 on 25-09-26 towards ACT Fibernet.",
            "a collect request" to "PAYTM has requested Rs.500.00 from your A/c. Approve in your UPI app.",
            "a bill reminder" to "Your ICICI Bank Credit Card bill of Rs.45,120.00 is due on 28-Sep-26. Min amount due Rs.2,300.",
            "a failed payment" to "Your payment of Rs.999.00 to NETFLIX has failed. Please retry.",
            "a balance enquiry" to "Available balance in your A/c XX4471 is Rs.41,225.50 as on 20-09-26.",
            "a loan advert" to "Pre-approved personal loan of Rs.5,00,000 for you! Apply now, T&C apply, click bit.ly/x",
            "a mandate notice" to "E-mandate registered for Rs.199.00 per month towards SPOTIFY on your Card xx9082.",
            "a reversal" to "Rs.486.00 debited on 20-09-26 has been reversed to your A/c xx4471.",
            "an unrelated message" to "Hey, are we still on for dinner tonight?"
        )

        for ((label, body) in rejected) {
            assertNull(label, parser.parse(body, sender = "VM-HDFCBK", receivedAt = receivedAt))
        }
    }

    @Test
    fun `refuses a message with a verb but no amount`() {
        assertNull(parser.parse("Your account has been debited today. Check the app.", null, receivedAt))
    }

    @Test
    fun `refuses something too short to be an alert`() {
        assertNull(parser.parse("Hi", null, receivedAt))
        assertNull(parser.parse("", null, receivedAt))
    }

    // ---------------------------------------------------------- de-duplication ---

    @Test
    fun `the same alert twice produces the same fingerprint`() {
        val body = "Sent Rs.486.00 from HDFC Bank A/C x4471 to SWIGGY on 20-09-26. UPI Ref 528113094412"
        val first = parser.parse(body, "VM-HDFCBK", receivedAt)
        val second = parser.parse(body, "VM-HDFCBK", receivedAt)
        assertEquals(first?.fingerprint, second?.fingerprint)
    }

    @Test
    fun `a different amount at the same merchant is a different alert`() {
        val a = parser.parse(
            "Sent Rs.486.00 from A/C x4471 to SWIGGY on 20-09-26. Ref 111111111",
            null,
            receivedAt
        )
        val b = parser.parse(
            "Sent Rs.487.00 from A/C x4471 to SWIGGY on 20-09-26. Ref 222222222",
            null,
            receivedAt
        )
        assertTrue(a?.fingerprint != b?.fingerprint)
    }

    @Test
    fun `a reference number makes the fingerprint independent of arrival time`() {
        val day1 = parser.fingerprintOf(
            ParsedAlert.Direction.DEBIT, 48_600L, "4471", "ABC123456", "swiggy",
            Instant.parse("2026-09-20T12:00:00Z")
        )
        val day5 = parser.fingerprintOf(
            ParsedAlert.Direction.DEBIT, 48_600L, "4471", "ABC123456", "swiggy",
            Instant.parse("2026-09-25T18:30:00Z")
        )
        assertEquals(day1, day5)
    }

    @Test
    fun `without a reference, two same-amount debits on different days stay separate`() {
        val day1 = parser.fingerprintOf(
            ParsedAlert.Direction.DEBIT, 48_600L, "4471", null, "swiggy",
            Instant.parse("2026-09-20T12:00:00Z")
        )
        val day2 = parser.fingerprintOf(
            ParsedAlert.Direction.DEBIT, 48_600L, "4471", null, "swiggy",
            Instant.parse("2026-09-21T12:00:00Z")
        )
        assertTrue(day1 != day2)
    }

    // -------------------------------------------------------------- normalising ---

    @Test
    fun `strips the payment-rail decoration banks wrap a merchant in`() {
        assertEquals("swiggy", parser.normalizeMerchant("UPI/SWIGGY*ORDER/HDFC"))
        assertEquals("social offline bandra", parser.normalizeMerchant("POS/SOCIAL OFFLINE BANDRA"))
        assertEquals("amazon", parser.normalizeMerchant("AMAZON.IN-PMTS"))
        assertEquals("croma", parser.normalizeMerchant("CROMA RETAIL PVT LTD 889210"))
    }

    @Test
    fun `the same shop matches itself however the bank wrote it`() {
        assertEquals(
            parser.normalizeMerchant("ZUDIO - PHOENIX MALL"),
            parser.normalizeMerchant("zudio   phoenix mall")
        )
    }

    @Test
    fun `the rule file loaded and has a version`() {
        // A version of 0 means the asset was missing, which would silently disable parsing.
        assertTrue("rules version was ${parser.rulesVersion}", parser.rulesVersion >= 1)
    }
}
