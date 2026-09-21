package com.snaptab.app.data.remote.fake

import com.snaptab.app.data.remote.dto.*
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * The hardcoded account the demo flavour runs on.
 *
 * Chosen to cover the cases that are easy to get wrong rather than to look tidy:
 *
 *   - a restaurant bill split four ways, one of whom has no SnapTab account
 *   - a solo Swiggy debit as a personal expense, with no items and no split
 *   - a grocery bill someone else paid, so the user owes rather than is owed
 *   - an unmatched card alert sitting in the inbox, and a credit that settles a debt
 *   - a flat-share group with a live balance, and a trip group where the user owes
 *
 * Amounts are integer paise, exactly as the server sends them.
 */
internal object DemoData {

    const val ME_ID = "00000000-0000-4000-8000-000000000001"
    const val ADITI_ID = "00000000-0000-4000-8000-000000000002"
    const val ROHAN_ID = "00000000-0000-4000-8000-000000000003"
    const val SNEHA_ID = "00000000-0000-4000-8000-000000000004"

    const val FLAT_GROUP_ID = "00000000-0000-4000-8000-0000000000a1"
    const val TRIP_GROUP_ID = "00000000-0000-4000-8000-0000000000a2"
    const val SATURDAY_GROUP_ID = "00000000-0000-4000-8000-0000000000a3"

    val me = UserDto(
        id = ME_ID,
        name = "Misbahul Haque",
        email = "misbahul8@gmail.com",
        phone = "+919820011234",
        status = "ACTIVE",
        emailVerified = true,
        phoneVerified = true,
        currency = "INR",
        timezone = "Asia/Kolkata",
        googleLinked = true,
        createdAt = iso(daysAgo(58))
    )

    val aditi = UserDto(id = ADITI_ID, name = "Aditi Deshpande", email = "aditi@example.com")
    val rohan = UserDto(id = ROHAN_ID, name = "Rohan Kamat", email = "rohan@example.com")

    /** Added to a split by phone number and has never signed in: still owes real money. */
    val sneha = UserDto(
        id = SNEHA_ID,
        name = "Sneha N.",
        phone = "+919930088214",
        status = "INVITED",
        pending = true
    )

    private fun category(slug: String, name: String, color: String, tint: String) =
        CategoryDto(slug = slug, name = name, iconKey = slug, colorHex = color, tintHex = tint)

    val restaurant = category("restaurant", "Restaurants", "#B4502A", "#F7E7DE")
    val groceries = category("groceries", "Groceries", "#4A6B14", "#EDF1DF")
    val shopping = category("shopping", "Shopping", "#6B3FA0", "#EEE7F7")
    val travel = category("travel", "Travel & transport", "#1E5F8A", "#E2EDF5")
    val utilities = category("utilities", "Bills & utilities", "#0F6B5C", "#E3EEEA")
    val fuel = category("fuel", "Fuel", "#8A5A00", "#F7EEDC")
    val other = category("other", "Uncategorised", "#8C867B", "#F0EBE0")

    val categories = listOf(restaurant, groceries, shopping, travel, utilities, fuel, other)

    // ------------------------------------------------------------------- expenses ---

    /** A restaurant bill the user paid for four people, split equally. */
    val socialOffline: ExpenseDto = run {
        val total = 257_500L
        // 257500 across 4 divides exactly; the engine would hand any remainder to the payer.
        val shares = listOf(ME_ID to 64_375L, ADITI_ID to 64_375L, ROHAN_ID to 64_375L, SNEHA_ID to 64_375L)
        ExpenseDto(
            id = "e0000000-0000-4000-8000-000000000001",
            kind = "SHARED",
            source = "SCAN",
            status = "OPEN",
            merchantName = "Social Offline, Bandra",
            occurredAt = iso(daysAgo(1)),
            category = restaurant,
            categorySource = "SUGGESTED",
            categoryConfidence = 0.94,
            group = GroupSummaryDto(SATURDAY_GROUP_ID, "Saturday people", "glass"),
            paidBy = me,
            totals = ExpenseTotalsDto(
                itemTotalMinor = 228_500,
                serviceChargeMinor = 22_850,
                taxMinor = 6_168,
                roundOffMinor = -18,
                totalMinor = total
            ),
            splitMethod = "EQUAL",
            items = listOf(
                ExpenseItemDto(id = "i1", name = "Aerated Beverages", quantityMilli = 2000, unitPriceMinor = 12_000, amountMinor = 24_000),
                ExpenseItemDto(id = "i2", name = "Chilli Cheese Toast", unitPriceMinor = 34_500, amountMinor = 34_500),
                ExpenseItemDto(id = "i3", name = "LIIT Pitcher", quantityMilli = 2000, unitPriceMinor = 65_000, amountMinor = 130_000),
                ExpenseItemDto(id = "i4", name = "Butter Chicken", unitPriceMinor = 40_000, amountMinor = 40_000)
            ),
            taxLines = listOf(
                TaxLineDto("Service Charge", "SERVICE_CHARGE", 1000, 22_850),
                TaxLineDto("CGST", "CGST", 250, 3_084),
                TaxLineDto("SGST", "SGST", 250, 3_084)
            ),
            shares = shares.map { (userId, amount) ->
                ExpenseShareDto(
                    userId = userId,
                    user = userById(userId),
                    amountMinor = amount,
                    paidMinor = if (userId == ME_ID) amount else 0,
                    weightBp = 2500,
                    settledAt = if (userId == ME_ID || userId == ROHAN_ID) iso(daysAgo(0)) else null
                )
            },
            matchedAlerts = listOf(
                MatchedAlertDto(
                    id = "a-social",
                    accountMask = "4471",
                    bankName = "HDFC Bank",
                    amountMinor = total,
                    occurredAt = iso(daysAgo(1)),
                    direction = "DEBIT"
                )
            ),
            viewer = ExpenseViewerDto(
                isPayer = true,
                yourShareMinor = 64_375,
                // Rohan has paid; Aditi and Sneha have not.
                youAreOwedMinor = 128_750,
                youOweMinor = 0
            ),
            hasReceipt = true
        )
    }

    /** The case the PERSONAL kind exists for: a card debit with no bill and nobody to split with. */
    val swiggy = ExpenseDto(
        id = "e0000000-0000-4000-8000-000000000002",
        kind = "PERSONAL",
        source = "ALERT",
        status = "OPEN",
        merchantName = "SWIGGY",
        occurredAt = iso(hoursAgo(5)),
        category = restaurant,
        categorySource = "SUGGESTED",
        categoryConfidence = 0.88,
        paidBy = me,
        totals = ExpenseTotalsDto(itemTotalMinor = 48_600, totalMinor = 48_600),
        matchedAlerts = listOf(
            MatchedAlertDto("a-swiggy", "4471", "HDFC Bank", 48_600, iso(hoursAgo(5)), "DEBIT")
        ),
        viewer = ExpenseViewerDto(isPayer = true, yourShareMinor = 48_600, youAreOwedMinor = 0, youOweMinor = 0)
    )

    val zudio = ExpenseDto(
        id = "e0000000-0000-4000-8000-000000000003",
        kind = "PERSONAL",
        source = "MANUAL",
        status = "OPEN",
        merchantName = "Zudio, Phoenix",
        occurredAt = iso(daysAgo(3)),
        category = shopping,
        categorySource = "USER",
        paidBy = me,
        totals = ExpenseTotalsDto(itemTotalMinor = 119_800, totalMinor = 119_800),
        viewer = ExpenseViewerDto(isPayer = true, yourShareMinor = 119_800, youAreOwedMinor = 0, youOweMinor = 0)
    )

    /** Someone else paid, so the user owes — the other side of the ledger. */
    val uber = ExpenseDto(
        id = "e0000000-0000-4000-8000-000000000004",
        kind = "SHARED",
        source = "MANUAL",
        status = "OPEN",
        merchantName = "Uber",
        occurredAt = iso(daysAgo(4)),
        category = travel,
        categorySource = "SUGGESTED",
        categoryConfidence = 0.91,
        group = GroupSummaryDto(TRIP_GROUP_ID, "Goa trip", "palm"),
        paidBy = aditi,
        totals = ExpenseTotalsDto(itemTotalMinor = 128_000, totalMinor = 128_000),
        splitMethod = "EQUAL",
        shares = listOf(
            ExpenseShareDto(ADITI_ID, aditi, 64_000, 64_000, 5000, settledAt = iso(daysAgo(4))),
            ExpenseShareDto(ME_ID, me, 64_000, 0, 5000)
        ),
        viewer = ExpenseViewerDto(isPayer = false, yourShareMinor = 64_000, youAreOwedMinor = 0, youOweMinor = 64_000)
    )

    val bigBasket = ExpenseDto(
        id = "e0000000-0000-4000-8000-000000000005",
        kind = "SHARED",
        source = "SCAN",
        status = "OPEN",
        merchantName = "BigBasket",
        occurredAt = iso(daysAgo(6)),
        category = groceries,
        categorySource = "SUGGESTED",
        categoryConfidence = 0.96,
        group = GroupSummaryDto(FLAT_GROUP_ID, "Flat 402", "home"),
        paidBy = me,
        totals = ExpenseTotalsDto(itemTotalMinor = 314_000, totalMinor = 314_000),
        splitMethod = "EQUAL",
        shares = listOf(
            // 314000 across 3 leaves two paise over, which go to the payer.
            ExpenseShareDto(ME_ID, me, 104_668, 104_668, 3333, settledAt = iso(daysAgo(6))),
            ExpenseShareDto(ADITI_ID, aditi, 104_666, 0, 3333),
            ExpenseShareDto(ROHAN_ID, rohan, 104_666, 0, 3333)
        ),
        viewer = ExpenseViewerDto(isPayer = true, yourShareMinor = 104_668, youAreOwedMinor = 209_332, youOweMinor = 0)
    )

    val fuelStop = ExpenseDto(
        id = "e0000000-0000-4000-8000-000000000006",
        kind = "PERSONAL",
        source = "ALERT",
        status = "OPEN",
        merchantName = "Indian Oil",
        occurredAt = iso(daysAgo(8)),
        category = fuel,
        categorySource = "SUGGESTED",
        categoryConfidence = 0.93,
        paidBy = me,
        totals = ExpenseTotalsDto(itemTotalMinor = 200_000, totalMinor = 200_000),
        viewer = ExpenseViewerDto(isPayer = true, yourShareMinor = 200_000, youAreOwedMinor = 0, youOweMinor = 0)
    )

    val actFibernet = ExpenseDto(
        id = "e0000000-0000-4000-8000-000000000007",
        kind = "SHARED",
        source = "MANUAL",
        status = "SETTLED",
        merchantName = "ACT Fibernet",
        occurredAt = iso(daysAgo(11)),
        category = utilities,
        categorySource = "USER",
        group = GroupSummaryDto(FLAT_GROUP_ID, "Flat 402", "home"),
        paidBy = aditi,
        totals = ExpenseTotalsDto(itemTotalMinor = 129_900, totalMinor = 129_900),
        splitMethod = "EQUAL",
        shares = listOf(
            ExpenseShareDto(ADITI_ID, aditi, 43_300, 43_300, 3333, settledAt = iso(daysAgo(11))),
            ExpenseShareDto(ME_ID, me, 43_300, 43_300, 3333, settledAt = iso(daysAgo(9))),
            ExpenseShareDto(ROHAN_ID, rohan, 43_300, 43_300, 3333, settledAt = iso(daysAgo(9)))
        ),
        viewer = ExpenseViewerDto(isPayer = false, yourShareMinor = 43_300, youAreOwedMinor = 0, youOweMinor = 0)
    )

    val expenses: List<ExpenseDto> = listOf(
        swiggy, socialOffline, zudio, uber, bigBasket, fuelStop, actFibernet
    )

    // --------------------------------------------------------------------- groups ---

    private fun member(user: UserDto, role: String = "MEMBER") =
        GroupMemberDto(role = role, joinedAt = iso(daysAgo(40)), user = user)

    val flat402 = GroupDto(
        id = FLAT_GROUP_ID,
        name = "Flat 402",
        description = "Rent, groceries, wifi",
        iconKey = "home",
        members = listOf(member(me, "OWNER"), member(aditi), member(rohan)),
        expenseCount = 2,
        balance = GroupBalanceSummaryDto(
            netMinor = 209_332,
            owedToYouMinor = 209_332,
            owedByYouMinor = 0
        )
    )

    val goaTrip = GroupDto(
        id = TRIP_GROUP_ID,
        name = "Goa trip",
        iconKey = "palm",
        members = listOf(member(me, "OWNER"), member(aditi), member(rohan), member(sneha)),
        expenseCount = 1,
        balance = GroupBalanceSummaryDto(netMinor = -64_000, owedToYouMinor = 0, owedByYouMinor = 64_000)
    )

    val saturdayPeople = GroupDto(
        id = SATURDAY_GROUP_ID,
        name = "Saturday people",
        iconKey = "glass",
        members = listOf(member(me, "OWNER"), member(aditi), member(rohan), member(sneha)),
        expenseCount = 1,
        balance = GroupBalanceSummaryDto(netMinor = 128_750, owedToYouMinor = 128_750, owedByYouMinor = 0)
    )

    val groups = listOf(flat402, saturdayPeople, goaTrip)

    // --------------------------------------------------------------- bank alerts ---

    /** Unmatched: the row the inbox asks you to route to personal or a group. */
    val cromaAlert = BankAlertDto(
        id = "b0000000-0000-4000-8000-000000000001",
        direction = "DEBIT",
        status = "UNMATCHED",
        amountMinor = 1_249_900,
        merchantRaw = "CROMA, PHOENIX",
        merchantNormalized = "croma phoenix",
        accountMask = "9082",
        accountKind = "CREDIT_CARD",
        bankName = "ICICI Bank",
        referenceNumber = "DEMO0000001",
        confidence = 0.9,
        occurredAt = iso(daysAgo(2)),
        suggestedCategory = shopping
    )

    val chaiAlert = BankAlertDto(
        id = "b0000000-0000-4000-8000-000000000002",
        direction = "DEBIT",
        status = "UNMATCHED",
        amountMinor = 12_000,
        merchantRaw = "BLUE TOKAI",
        merchantNormalized = "blue tokai",
        accountMask = "4471",
        accountKind = "ACCOUNT",
        bankName = "HDFC Bank",
        referenceNumber = "DEMO0000004",
        confidence = 0.86,
        occurredAt = iso(hoursAgo(2)),
        suggestedCategory = restaurant
    )

    /** A credit that exactly matches what Rohan owed, already auto-settled. */
    val rohanCredit = BankAlertDto(
        id = "b0000000-0000-4000-8000-000000000003",
        direction = "CREDIT",
        status = "SETTLED",
        amountMinor = 64_375,
        merchantRaw = "ROHANKAMAT@okhdfcbank",
        merchantNormalized = "rohankamat",
        accountMask = "4471",
        accountKind = "ACCOUNT",
        bankName = "HDFC Bank",
        referenceNumber = "DEMO0000002",
        confidence = 1.0,
        occurredAt = iso(hoursAgo(26)),
        settlementId = "s-rohan"
    )

    val swiggyAlert = BankAlertDto(
        id = "b0000000-0000-4000-8000-000000000004",
        direction = "DEBIT",
        status = "LINKED",
        amountMinor = 48_600,
        merchantRaw = "SWIGGY",
        merchantNormalized = "swiggy",
        accountMask = "4471",
        accountKind = "ACCOUNT",
        bankName = "HDFC Bank",
        referenceNumber = "DEMO0000003",
        confidence = 0.96,
        occurredAt = iso(hoursAgo(5)),
        expenseId = swiggy.id,
        suggestedCategory = restaurant
    )

    val alerts = listOf(chaiAlert, cromaAlert, rohanCredit, swiggyAlert)

    // ------------------------------------------------------------------- balance ---

    val balance = BalanceDto(
        currency = "INR",
        // Aditi owes her share of Social Offline and BigBasket; Sneha owes Social Offline;
        // Rohan owes his BigBasket share; the user owes Aditi for the Uber.
        owedToYouMinor = 64_375 + 104_666 + 64_375 + 104_666,
        owedByYouMinor = 64_000,
        netMinor = (64_375 + 104_666 + 64_375 + 104_666) - 64_000,
        people = listOf(
            PersonBalanceDto(ADITI_ID, aditi.name, null, "ACTIVE", 64_375 + 104_666 - 64_000),
            PersonBalanceDto(ROHAN_ID, rohan.name, null, "ACTIVE", 104_666),
            PersonBalanceDto(SNEHA_ID, sneha.name, null, "INVITED", 64_375)
        ),
        suggestedTransfers = listOf(
            SuggestedTransferDto(ADITI_ID, ME_ID, 105_041, aditi.name, "You"),
            SuggestedTransferDto(ROHAN_ID, ME_ID, 104_666, rohan.name, "You"),
            SuggestedTransferDto(SNEHA_ID, ME_ID, 64_375, sneha.name, "You")
        )
    )

    // ------------------------------------------------------------------ insights ---

    /**
     * Spending is personal in full plus the user's own share of the shared ones. Money
     * fronted for other people is in `paidOutMinor`, not in `spentMinor`.
     */
    fun monthly(month: String, kind: String): MonthlySummaryDto {
        val personal = listOf(swiggy, zudio, fuelStop).sumOf { it.totals.totalMinor }
        val sharedShare = listOf(socialOffline, uber, bigBasket, actFibernet)
            .sumOf { it.viewer.yourShareMinor }

        val personalPart = if (kind == "SHARED") 0 else personal
        val sharedPart = if (kind == "PERSONAL") 0 else sharedShare
        val spent = personalPart + sharedPart

        val buckets = buildList {
            add(Triple(restaurant, 48_600L + 64_375L, 2))
            add(Triple(fuel, 200_000L, 1))
            add(Triple(shopping, 119_800L, 1))
            add(Triple(groceries, 104_668L, 1))
            add(Triple(travel, 64_000L, 1))
            add(Triple(utilities, 43_300L, 1))
        }.filter { (category, _, _) ->
            when (kind) {
                "PERSONAL" -> category.slug in setOf("restaurant", "shopping", "fuel")
                "SHARED" -> category.slug in setOf("restaurant", "groceries", "travel", "utilities")
                else -> true
            }
        }
        val bucketTotal = buckets.sumOf { it.second }.coerceAtLeast(1)

        return MonthlySummaryDto(
            month = month,
            currency = "INR",
            personalMinor = personalPart,
            sharedShareMinor = sharedPart,
            spentMinor = spent,
            // Everything that left the account: the user's own spend plus what they covered.
            paidOutMinor = personal + socialOffline.totals.totalMinor + bigBasket.totals.totalMinor,
            owedToYouMinor = balance.owedToYouMinor,
            owedByYouMinor = balance.owedByYouMinor,
            expenseCount = 7,
            personalCount = 3,
            sharedCount = 4,
            byCategory = buckets.map { (category, amount, count) ->
                CategorySpendDto(
                    slug = category.slug,
                    name = category.name,
                    color = category.colorHex,
                    spentMinor = amount,
                    shareBp = ((amount * 10_000) / bucketTotal).toInt(),
                    count = count
                )
            },
            previousSpentMinor = (spent * 0.88).toLong()
        )
    }

    fun trend(months: Int): List<TrendPointDto> {
        val base = listOf(1_240_000L, 1_510_000L, 1_380_000L, 1_790_000L, 1_624_000L, 1_842_000L)
        val now = java.time.YearMonth.now()
        return (0 until months).map { index ->
            val month = now.minusMonths((months - 1 - index).toLong())
            val spent = base.getOrElse(base.size - months + index) { 1_500_000L }
            TrendPointDto(
                month = month.toString(),
                spentMinor = spent,
                personalMinor = (spent * 0.61).toLong(),
                sharedShareMinor = (spent * 0.39).toLong(),
                expenseCount = 12 + index
            )
        }
    }

    /** What the stub OCR provider returns, so the scan flow is exercisable offline. */
    fun parsedReceipt() = ParsedReceiptDto(
        merchantName = "SOCIAL OFFLINE",
        occurredAt = iso(Instant.now()),
        invoiceNumber = "SO/2026/4471",
        items = listOf(
            ParsedReceiptItemDto("Aerated Beverages", 2000, 12_000, 24_000),
            ParsedReceiptItemDto("Chilli Cheese Toast", 1000, 34_500, 34_500),
            ParsedReceiptItemDto("LIIT Pitcher", 2000, 65_000, 130_000),
            ParsedReceiptItemDto("Butter Chicken", 1000, 40_000, 40_000)
        ),
        taxLines = listOf(
            TaxLineDto("Service Charge", "SERVICE_CHARGE", 1000, 22_850),
            TaxLineDto("CGST", "CGST", 250, 3_084),
            TaxLineDto("SGST", "SGST", 250, 3_084)
        ),
        totals = ExpenseTotalsDto(
            itemTotalMinor = 228_500,
            serviceChargeMinor = 22_850,
            taxMinor = 6_168,
            roundOffMinor = -18,
            totalMinor = 257_500
        ),
        confidence = 0.92,
        suggestions = listOf(
            CategorySuggestionDto(
                slug = "restaurant",
                name = "Restaurants",
                colorHex = restaurant.colorHex,
                tintHex = restaurant.tintHex,
                confidence = 0.94,
                reasons = listOf("merchant looks like “social offline”", "items include “liit”")
            ),
            CategorySuggestionDto("groceries", "Groceries", groceries.colorHex, groceries.tintHex, 0.21),
            CategorySuggestionDto("shopping", "Shopping", shopping.colorHex, shopping.tintHex, 0.09)
        )
    )

    fun userById(id: String): UserDto = when (id) {
        ME_ID -> me
        ADITI_ID -> aditi
        ROHAN_ID -> rohan
        SNEHA_ID -> sneha
        else -> UserDto(id = id, name = "Someone")
    }

    fun daysAgo(days: Long): Instant = Instant.now().minus(days, ChronoUnit.DAYS)

    fun hoursAgo(hours: Long): Instant = Instant.now().minus(hours, ChronoUnit.HOURS)

    fun iso(instant: Instant): String =
        DateTimeFormatter.ISO_INSTANT.format(instant.atOffset(ZoneOffset.UTC))
}
