package com.snaptab.app.data.remote.fake

import com.snaptab.app.core.Money
import com.snaptab.app.data.remote.SnapTabApi
import com.snaptab.app.data.remote.dto.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * A working SnapTabApi with no server behind it.
 *
 * Used by the `demo` product flavour so the whole app can be driven before the backend is
 * running. It is a genuine implementation rather than a stub: state is held in memory and
 * mutated, so logging an alert really does remove it from the inbox, splitting really does
 * produce shares that sum to the exact total (through the same largest-remainder
 * arithmetic the server uses), and a settlement really does clear a balance.
 *
 * Deliberate behaviours worth knowing:
 *   - every call waits a beat, so loading states are visible rather than skipped
 *   - a scan goes QUEUED then SUCCEEDED across two polls, so the progress screen is real
 *   - `/auth/google` returns 501, matching a server with no client id configured
 *   - errors come back in the API's `{ error: { code, message } }` envelope, so the app's
 *     error handling is exercised too
 */
class FakeSnapTabApi : SnapTabApi {

    private val mutex = Mutex()

    private var user: UserDto = DemoData.me
    private val expenses = DemoData.expenses.toMutableList()
    private val alerts = DemoData.alerts.toMutableList()
    private val groups = DemoData.groups.toMutableList()
    private val settlements = mutableListOf<SettlementDto>()
    private var balance: BalanceDto = DemoData.balance

    /** Scans need at least one poll in PROCESSING for the progress screen to mean anything. */
    private val scans = mutableMapOf<String, ScanDto>()
    private val scanPolls = mutableMapOf<String, AtomicInteger>()

    private val notifications = mutableListOf(
        NotificationDto(
            id = "n1",
            kind = "SETTLEMENT_RECEIVED",
            title = "Rohan paid you ₹643.75",
            body = "SnapTab spotted the credit and closed it out.",
            createdAt = DemoData.iso(DemoData.hoursAgo(26))
        ),
        NotificationDto(
            id = "n2",
            kind = "ALERT_NEEDS_EXPENSE",
            title = "₹12,499 at CROMA, PHOENIX",
            body = "Log it under Shopping, or scan the bill if you are splitting it.",
            createdAt = DemoData.iso(DemoData.daysAgo(2))
        )
    )

    // ------------------------------------------------------------------- config ---

    override suspend fun config(): Response<ConfigResponse> = ok(
        ConfigResponse(
            auth = AuthMethodsResponse(email = true, phone = true, google = false),
            scan = ScanConfigDto(maxBytes = 12 * 1024 * 1024, provider = "demo"),
            rules = RulesVersionDto(sms = 2, categories = 2),
            shareBaseUrl = "https://snaptab.app/t"
        )
    )

    // --------------------------------------------------------------------- auth ---

    override suspend fun startOtp(body: OtpStartRequest): Response<OtpStartResponse> = ok(
        OtpStartResponse(
            channel = if (body.contact.contains('@')) "EMAIL" else "PHONE",
            sentTo = maskForDemo(body.contact),
            expiresInSeconds = 600,
            // Any code is accepted in the demo, and this one is pre-filled.
            devCode = DEMO_OTP
        )
    )

    override suspend fun verifyOtp(body: OtpVerifyRequest): Response<AuthResponse> {
        if (body.code.length < 4) {
            return error(400, "CODE_INCORRECT", "That code is not right.")
        }
        return ok(session())
    }

    override suspend fun signInWithGoogle(body: GoogleSignInRequest): Response<AuthResponse> =
        error(
            501,
            "GOOGLE_SIGN_IN_DISABLED",
            "Google sign-in is not available in the demo build."
        )

    override suspend fun refresh(body: RefreshRequest): Response<AuthResponse> = ok(session())

    override suspend fun logout(): Response<Unit> = ok(Unit)

    override suspend fun logoutEverywhere(): Response<Unit> = ok(Unit)

    override suspend fun authMethods(): Response<AuthMethodsResponse> =
        ok(AuthMethodsResponse(email = true, phone = true, google = false))

    // -------------------------------------------------------------------- users ---

    override suspend fun me(): Response<MeResponse> = ok(MeResponse(user))

    override suspend fun updateMe(body: UpdateMeRequest): Response<MeResponse> = mutate {
        user = user.copy(
            name = body.name ?: user.name,
            currency = body.currency ?: user.currency,
            timezone = body.timezone ?: user.timezone,
            keepAlertBodies = body.keepAlertBodies ?: user.keepAlertBodies
        )
        MeResponse(user)
    }

    override suspend fun deleteAccount(): Response<Unit> = ok(Unit)

    override suspend fun lookup(contact: String): Response<LookupResponse> {
        val match = listOf(DemoData.aditi, DemoData.rohan).firstOrNull { candidate ->
            candidate.email?.equals(contact.trim(), ignoreCase = true) == true ||
                candidate.phone == contact.trim()
        }
        return ok(
            LookupResponse(
                found = match != null,
                user = match,
                kind = if (contact.contains('@')) "EMAIL" else "PHONE"
            )
        )
    }

    override suspend fun recentPeople(): Response<RecentPeopleResponse> = ok(
        RecentPeopleResponse(
            listOf(
                RecentPersonDto(DemoData.aditi, DemoData.iso(DemoData.daysAgo(1)), 6),
                RecentPersonDto(DemoData.rohan, DemoData.iso(DemoData.daysAgo(1)), 5),
                RecentPersonDto(DemoData.sneha, DemoData.iso(DemoData.daysAgo(1)), 2)
            )
        )
    )

    override suspend fun registerDevice(body: DeviceRequest): Response<DeviceResponse> = ok(
        DeviceResponse(
            DeviceStateDto(
                id = "demo-device",
                platform = body.platform,
                smsEnabled = body.smsEnabled ?: true,
                lastSeenAt = DemoData.iso(Instant.now())
            )
        )
    )

    override suspend fun notifications(
        unreadOnly: Boolean,
        limit: Int
    ): Response<NotificationListResponse> = ok(
        NotificationListResponse(
            notifications = notifications.filter { !unreadOnly || it.readAt == null }.take(limit),
            unreadCount = notifications.count { it.readAt == null }
        )
    )

    override suspend fun markNotificationsRead(body: MarkReadRequest): Response<Unit> = mutate {
        notifications.replaceAll { notification ->
            if (body.ids.isEmpty() || notification.id in body.ids) {
                notification.copy(readAt = DemoData.iso(Instant.now()))
            } else {
                notification
            }
        }
        Unit
    }

    // --------------------------------------------------------------- categories ---

    override suspend fun categories(): Response<CategoriesResponse> =
        ok(CategoriesResponse(DemoData.categories, taxonomyVersion = 2))

    // ----------------------------------------------------------------- expenses ---

    override suspend fun expenses(
        kind: String,
        groupId: String?,
        categorySlug: String?,
        from: String?,
        to: String?,
        search: String?,
        limit: Int,
        cursor: String?
    ): Response<ExpenseListResponse> {
        val filtered = expenses
            .filter { it.status != "VOID" }
            .filter {
                when (kind) {
                    "personal" -> it.kind == "PERSONAL"
                    "shared" -> it.kind == "SHARED"
                    else -> true
                }
            }
            .filter { groupId == null || it.group?.id == groupId }
            .filter { categorySlug == null || it.category?.slug == categorySlug }
            .filter { search == null || it.merchantName?.contains(search, ignoreCase = true) == true }
            .sortedByDescending { it.occurredAt }
        return ok(ExpenseListResponse(filtered.take(limit), nextCursor = null))
    }

    override suspend fun createExpense(body: CreateExpenseRequest): Response<ExpenseResponse> = mutate {
        val created = buildExpense(body)
        expenses.add(0, created)
        body.alertId?.let { markAlertLinked(it, created.id) }
        recomputeBalance()
        ExpenseResponse(created)
    }

    override suspend fun expense(id: String): Response<ExpenseResponse> {
        val found = expenses.firstOrNull { it.id == id }
            ?: return error(404, "NOT_FOUND", "That expense could not be found.")
        return ok(ExpenseResponse(found))
    }

    override suspend fun updateExpense(
        id: String,
        body: UpdateExpenseRequest
    ): Response<ExpenseResponse> = mutateOrError(id) { existing ->
        existing.copy(
            merchantName = body.merchantName ?: existing.merchantName,
            note = body.note ?: existing.note,
            occurredAt = body.occurredAt ?: existing.occurredAt,
            kind = body.kind ?: existing.kind,
            category = body.categorySlug
                ?.let { slug -> DemoData.categories.firstOrNull { it.slug == slug } }
                ?: existing.category,
            categorySource = if (body.categorySlug != null) "USER" else existing.categorySource
        )
    }

    override suspend fun deleteExpense(id: String): Response<Unit> = mutate {
        expenses.replaceAll { if (it.id == id) it.copy(status = "VOID") else it }
        recomputeBalance()
        Unit
    }

    override suspend fun setSplit(id: String, body: SetSplitRequest): Response<ExpenseResponse> =
        mutateOrError(id) { existing ->
            val participants = body.participants.ifEmpty {
                // Itemised splits carry their people on the assignments instead.
                body.itemAssignments.flatMap { it.assignments }
                    .map { ParticipantRequest(userId = it.userId) }
                    .distinctBy { it.userId }
            }
            existing.copy(
                kind = "SHARED",
                splitMethod = body.method,
                extrasMode = body.extrasMode,
                shares = shareOut(existing.totals.totalMinor, body.method, participants, existing.paidBy.id),
                viewer = viewerFor(existing.totals.totalMinor, existing.paidBy.id, participants, body.method)
            )
        }

    override suspend fun removeSplit(id: String): Response<ExpenseResponse> =
        mutateOrError(id) { existing ->
            existing.copy(
                kind = "PERSONAL",
                splitMethod = null,
                group = null,
                shares = emptyList(),
                viewer = ExpenseViewerDto(
                    isPayer = true,
                    yourShareMinor = existing.totals.totalMinor,
                    youAreOwedMinor = 0,
                    youOweMinor = 0
                )
            )
        }

    override suspend fun suggestCategory(
        merchant: String?,
        items: String?,
        direction: String?
    ): Response<SuggestionsResponse> {
        val text = "${merchant.orEmpty()} ${items.orEmpty()}".lowercase()
        val ranked = when {
            listOf("swiggy", "zomato", "social", "cafe", "restaurant", "tokai", "bar")
                .any { text.contains(it) } -> listOf(DemoData.restaurant to 0.94, DemoData.groceries to 0.2)
            listOf("bigbasket", "blinkit", "zepto", "kirana", "grocer", "dmart")
                .any { text.contains(it) } -> listOf(DemoData.groceries to 0.95, DemoData.restaurant to 0.18)
            listOf("croma", "zudio", "amazon", "flipkart", "myntra", "store")
                .any { text.contains(it) } -> listOf(DemoData.shopping to 0.92, DemoData.other to 0.1)
            listOf("uber", "ola", "indigo", "irctc", "hotel", "rapido")
                .any { text.contains(it) } -> listOf(DemoData.travel to 0.93, DemoData.other to 0.1)
            listOf("indian oil", "petrol", "diesel", "hpcl", "bpcl")
                .any { text.contains(it) } -> listOf(DemoData.fuel to 0.93, DemoData.other to 0.1)
            listOf("act ", "airtel", "jio", "electricity", "broadband")
                .any { text.contains(it) } -> listOf(DemoData.utilities to 0.9, DemoData.other to 0.1)
            else -> listOf(DemoData.other to 0.0)
        }
        return ok(
            SuggestionsResponse(
                ranked.map { (category, confidence) ->
                    CategorySuggestionDto(
                        slug = category.slug,
                        name = category.name,
                        colorHex = category.colorHex,
                        tintHex = category.tintHex,
                        confidence = confidence,
                        reasons = if (confidence > 0) {
                            listOf("merchant looks like “${merchant?.lowercase()?.trim()}”")
                        } else {
                            listOf("nothing matched")
                        }
                    )
                }
            )
        )
    }

    // -------------------------------------------------------------------- scans ---

    override suspend fun uploadScan(
        image: MultipartBody.Part,
        idempotencyKey: String?
    ): Response<ScanResponse> = mutate {
        val id = UUID.randomUUID().toString()
        val queued = ScanDto(
            id = id,
            status = "QUEUED",
            provider = "demo",
            queuedAt = DemoData.iso(Instant.now())
        )
        scans[id] = queued
        scanPolls[id] = AtomicInteger(0)
        ScanResponse(scan = queued, deduplicated = false, pollAfterMs = 700)
    }

    /**
     * QUEUED, then PROCESSING, then SUCCEEDED across successive polls — the same shape the
     * real pipeline has, so the progress screen is exercised rather than skipped.
     */
    override suspend fun scan(id: String): Response<ScanResponse> = mutate {
        val polls = scanPolls.getOrPut(id) { AtomicInteger(0) }.incrementAndGet()
        val current = scans[id] ?: ScanDto(id = id, status = "QUEUED")
        val next = when {
            polls < 2 -> current.copy(status = "PROCESSING", startedAt = DemoData.iso(Instant.now()))
            else -> current.copy(
                status = "SUCCEEDED",
                confidence = 0.92,
                result = DemoData.parsedReceipt(),
                finishedAt = DemoData.iso(Instant.now())
            )
        }
        scans[id] = next
        ScanResponse(scan = next)
    }

    override suspend fun retryScan(id: String): Response<ScanResponse> = mutate {
        scanPolls[id] = AtomicInteger(0)
        val queued = ScanDto(id = id, status = "QUEUED", provider = "demo")
        scans[id] = queued
        ScanResponse(scan = queued)
    }

    // ------------------------------------------------------------------- alerts ---

    override suspend fun ingestAlerts(body: IngestAlertsRequest): Response<IngestAlertsResponse> =
        mutate {
            var created = 0
            var duplicates = 0
            val results = body.alerts.map { incoming ->
                val existing = alerts.firstOrNull {
                    it.referenceNumber != null && it.referenceNumber == incoming.referenceNumber
                }
                if (existing != null) {
                    duplicates += 1
                    IngestOutcomeDto(existing.id, created = false, status = existing.status)
                } else {
                    created += 1
                    val suggested = suggestFor(incoming.merchantRaw, incoming.direction)
                    val row = BankAlertDto(
                        id = UUID.randomUUID().toString(),
                        direction = incoming.direction,
                        status = "UNMATCHED",
                        amountMinor = incoming.amountMinor,
                        currency = incoming.currency,
                        merchantRaw = incoming.merchantRaw,
                        accountMask = incoming.accountMask,
                        accountKind = incoming.accountKind,
                        bankName = incoming.bankName,
                        referenceNumber = incoming.referenceNumber,
                        confidence = incoming.confidence,
                        occurredAt = incoming.occurredAt,
                        suggestedCategory = suggested
                    )
                    alerts.add(0, row)
                    IngestOutcomeDto(row.id, created = true, status = row.status, suggestedCategorySlug = suggested?.slug)
                }
            }
            IngestAlertsResponse(results, created, duplicates, serverRulesVersion = 2)
        }

    override suspend fun alerts(
        status: String?,
        direction: String?,
        limit: Int,
        cursor: String?
    ): Response<AlertListResponse> {
        val filtered = alerts
            .filter { status == null || it.status == status }
            .filter { direction == null || it.direction == direction }
            .sortedByDescending { it.occurredAt }
        return ok(
            AlertListResponse(
                alerts = filtered.take(limit),
                counts = alerts.groupingBy { it.status }.eachCount()
            )
        )
    }

    /**
     * The personal-or-group decision. `SHARED` with a groupId splits it equally across that
     * group's current members, exactly as the server does.
     */
    override suspend fun alertToExpense(
        id: String,
        body: AlertToExpenseRequest
    ): Response<ExpenseResponse> {
        val alert = alerts.firstOrNull { it.id == id }
            ?: return error(404, "NOT_FOUND", "That alert could not be found.")
        if (alert.expenseId != null) {
            return error(409, "ALREADY_LINKED", "That alert is already on an expense.")
        }
        if (alert.direction == "CREDIT") {
            return error(
                400,
                "CREDIT_NOT_AN_EXPENSE",
                "That was money coming in. Record it as a settlement instead."
            )
        }
        val group = body.groupId?.let { wanted -> groups.firstOrNull { it.id == wanted } }
        if (body.kind == "SHARED" && group == null && body.participants.isNullOrEmpty()) {
            return error(
                422,
                "VALIDATION_FAILED",
                "Splitting an alert needs a group, or the people to split it with."
            )
        }

        return mutate {
            val participants = body.participants
                ?: group?.members?.map { ParticipantRequest(userId = it.user.id) }
                ?: emptyList()

            val created = buildExpense(
                CreateExpenseRequest(
                    kind = body.kind,
                    source = "ALERT",
                    groupId = body.groupId,
                    merchantName = alert.merchantRaw,
                    note = body.note,
                    occurredAt = alert.occurredAt,
                    currency = alert.currency,
                    categorySlug = body.categorySlug ?: alert.suggestedCategory?.slug,
                    itemTotalMinor = alert.amountMinor,
                    totalMinor = alert.amountMinor,
                    splitMethod = if (body.kind == "SHARED") body.splitMethod ?: "EQUAL" else null,
                    participants = participants.takeIf { body.kind == "SHARED" },
                    alertId = alert.id
                )
            )
            expenses.add(0, created)
            markAlertLinked(alert.id, created.id)
            recomputeBalance()
            ExpenseResponse(created)
        }
    }

    override suspend fun linkAlert(id: String, body: LinkAlertRequest): Response<LinkAlertResponse> {
        val alert = alerts.firstOrNull { it.id == id }
            ?: return error(404, "NOT_FOUND", "That alert could not be found.")
        val expense = expenses.firstOrNull { it.id == body.expenseId }
            ?: return error(404, "NOT_FOUND", "That expense could not be found.")
        return mutate {
            markAlertLinked(id, expense.id)
            LinkAlertResponse(
                alert = alerts.first { it.id == id },
                amountsMatch = alert.amountMinor == expense.totals.totalMinor,
                differenceMinor = expense.totals.totalMinor - alert.amountMinor
            )
        }
    }

    override suspend fun ignoreAlert(id: String): Response<Unit> = mutate {
        alerts.replaceAll { if (it.id == id) it.copy(status = "IGNORED") else it }
        Unit
    }

    override suspend fun alertSuggestions(id: String): Response<AlertSuggestionsResponse> {
        val alert = alerts.firstOrNull { it.id == id }
            ?: return error(404, "NOT_FOUND", "That alert could not be found.")
        val suggested = alert.suggestedCategory ?: DemoData.other
        return ok(
            AlertSuggestionsResponse(
                categories = listOf(
                    CategorySuggestionDto(
                        suggested.slug,
                        suggested.name,
                        suggested.colorHex,
                        suggested.tintHex,
                        0.9,
                        listOf("merchant looks like “${alert.merchantRaw?.lowercase()}”")
                    )
                ),
                linkCandidates = expenses
                    .filter { it.totals.totalMinor == alert.amountMinor && it.matchedAlerts.isEmpty() }
                    .take(3)
                    .map { LinkCandidateDto(it.id, it.merchantName, it.totals.totalMinor, it.occurredAt) }
            )
        )
    }

    override suspend fun smsRulesVersion(): Response<RulesVersionSingle> = ok(RulesVersionSingle(2))

    // ------------------------------------------------------------------- groups ---

    override suspend fun groups(): Response<GroupListResponse> = ok(GroupListResponse(groups))

    override suspend fun createGroup(body: CreateGroupRequest): Response<CreateGroupResponse> = mutate {
        val invited = body.invite.map { contact ->
            val existing = listOf(DemoData.aditi, DemoData.rohan).firstOrNull {
                it.email.equals(contact.trim(), ignoreCase = true) || it.phone == contact.trim()
            }
            existing ?: UserDto(
                id = UUID.randomUUID().toString(),
                name = contact.substringBefore('@').replaceFirstChar { it.uppercase() },
                email = contact.takeIf { it.contains('@') },
                phone = contact.takeUnless { it.contains('@') },
                status = "INVITED",
                pending = true
            )
        }
        val created = GroupDto(
            id = UUID.randomUUID().toString(),
            name = body.name,
            description = body.description,
            currency = body.currency,
            iconKey = body.iconKey,
            defaultSplitMethod = body.defaultSplitMethod,
            members = listOf(GroupMemberDto("OWNER", DemoData.iso(Instant.now()), user)) +
                invited.map { GroupMemberDto("MEMBER", DemoData.iso(Instant.now()), it) },
            balance = GroupBalanceSummaryDto()
        )
        groups.add(0, created)
        CreateGroupResponse(
            group = created,
            invited = invited.map {
                InvitedPersonDto(
                    contact = maskForDemo(it.email ?: it.phone.orEmpty()),
                    userId = it.id,
                    alreadyOnSnapTab = it.status == "ACTIVE"
                )
            }
        )
    }

    override suspend fun group(id: String): Response<GroupDetailResponse> {
        val found = groups.firstOrNull { it.id == id }
            ?: return error(404, "NOT_FOUND", "That group could not be found.")
        val people = balance.people.filter { person ->
            found.members.any { it.user.id == person.userId }
        }
        return ok(
            GroupDetailResponse(
                group = found,
                pendingInvites = emptyList(),
                balance = balance.copy(
                    people = people,
                    owedToYouMinor = people.filter { it.netMinor > 0 }.sumOf { it.netMinor },
                    owedByYouMinor = people.filter { it.netMinor < 0 }.sumOf { -it.netMinor },
                    netMinor = people.sumOf { it.netMinor }
                )
            )
        )
    }

    override suspend fun updateGroup(
        id: String,
        body: CreateGroupRequest
    ): Response<CreateGroupResponse> = mutate {
        groups.replaceAll { if (it.id == id) it.copy(name = body.name, iconKey = body.iconKey) else it }
        CreateGroupResponse(groups.first { it.id == id })
    }

    override suspend fun addMembers(
        id: String,
        body: AddMembersRequest
    ): Response<AddMembersResponse> = mutate {
        val added = body.contacts.map { contact ->
            listOf(DemoData.aditi, DemoData.rohan, DemoData.sneha).firstOrNull {
                it.email.equals(contact.trim(), ignoreCase = true) || it.phone == contact.trim()
            } ?: UserDto(
                id = UUID.randomUUID().toString(),
                name = body.displayNames?.get(contact)
                    ?: contact.substringBefore('@').replaceFirstChar { it.uppercase() },
                email = contact.takeIf { it.contains('@') },
                phone = contact.takeUnless { it.contains('@') },
                status = "INVITED",
                pending = true
            )
        }
        groups.replaceAll { group ->
            if (group.id != id) {
                group
            } else {
                val existingIds = group.members.map { it.user.id }.toSet()
                group.copy(
                    members = group.members + added
                        .filter { it.id !in existingIds }
                        .map { GroupMemberDto("MEMBER", DemoData.iso(Instant.now()), it) }
                )
            }
        }
        AddMembersResponse(
            group = groups.first { it.id == id },
            added = added.map {
                InvitedPersonDto(
                    contact = maskForDemo(it.email ?: it.phone.orEmpty()),
                    userId = it.id,
                    alreadyOnSnapTab = it.status == "ACTIVE"
                )
            }
        )
    }

    override suspend fun removeMember(id: String, userId: String): Response<Unit> {
        val owes = balance.people.firstOrNull { it.userId == userId }?.netMinor ?: 0
        if (owes != 0L) {
            return error(
                409,
                "MEMBER_HAS_BALANCE",
                "Settle up with this person before removing them from the group."
            )
        }
        return mutate {
            groups.replaceAll { group ->
                if (group.id == id) {
                    group.copy(members = group.members.filterNot { it.user.id == userId })
                } else {
                    group
                }
            }
            Unit
        }
    }

    override suspend fun groupBalance(id: String): Response<BalanceResponse> {
        val detail = group(id).body() ?: return error(404, "NOT_FOUND", "That group could not be found.")
        return ok(BalanceResponse(detail.balance))
    }

    override suspend fun groupActivity(id: String, limit: Int): Response<GroupActivityResponse> {
        val feed = expenses
            .filter { it.group?.id == id && it.status != "VOID" }
            .map { expense ->
                GroupActivityEntryDto(
                    type = "EXPENSE",
                    at = expense.occurredAt,
                    expense = ActivityExpenseDto(
                        id = expense.id,
                        merchantName = expense.merchantName,
                        totalMinor = expense.totals.totalMinor,
                        currency = expense.currency,
                        occurredAt = expense.occurredAt,
                        paidBy = expense.paidBy,
                        category = expense.category,
                        yourShareMinor = expense.viewer.yourShareMinor,
                        yourShareSettled = expense.shares
                            .firstOrNull { it.userId == DemoData.ME_ID }?.settledAt != null
                    )
                )
            } + settlements
            .filter { it.group?.id == id }
            .map { GroupActivityEntryDto(type = "SETTLEMENT", at = it.createdAt.orEmpty(), settlement = it) }

        return ok(GroupActivityResponse(feed.sortedByDescending { it.at }.take(limit)))
    }

    // -------------------------------------------------------------- settlements ---

    override suspend fun balance(groupId: String?): Response<BalanceResponse> =
        if (groupId == null) ok(BalanceResponse(balance)) else groupBalance(groupId)

    override suspend fun createSettlement(
        body: CreateSettlementRequest
    ): Response<CreateSettlementResponse> {
        if ((body.fromUserId == null) == (body.toUserId == null)) {
            return error(
                422,
                "VALIDATION_FAILED",
                "Give exactly one of fromUserId or toUserId — the other side is you."
            )
        }
        return mutate {
            val otherId = body.fromUserId ?: body.toUserId!!
            val incoming = body.fromUserId != null
            val settlement = SettlementDto(
                id = UUID.randomUUID().toString(),
                amountMinor = body.amountMinor,
                currency = body.currency,
                method = body.method,
                note = body.note,
                createdAt = DemoData.iso(Instant.now()),
                fromUser = if (incoming) DemoData.userById(otherId) else user,
                toUser = if (incoming) user else DemoData.userById(otherId),
                group = body.groupId?.let { id ->
                    groups.firstOrNull { it.id == id }?.let { GroupSummaryDto(it.id, it.name, it.iconKey) }
                }
            )
            settlements.add(0, settlement)

            // Move the balance, and report anything paid over what was owed.
            val outstanding = balance.people.firstOrNull { it.userId == otherId }?.netMinor ?: 0
            val applied = if (incoming) {
                minOf(body.amountMinor, maxOf(outstanding, 0))
            } else {
                minOf(body.amountMinor, maxOf(-outstanding, 0))
            }
            adjustPerson(otherId, if (incoming) -applied else applied)
            markSharesSettled(otherId, incoming)

            CreateSettlementResponse(settlement, unappliedMinor = body.amountMinor - applied)
        }
    }

    override suspend fun settlements(groupId: String?, limit: Int): Response<SettlementListResponse> =
        ok(
            SettlementListResponse(
                settlements.filter { groupId == null || it.group?.id == groupId }.take(limit)
            )
        )

    override suspend fun reverseSettlement(id: String): Response<Unit> = mutate {
        val settlement = settlements.firstOrNull { it.id == id }
        if (settlement != null) {
            val otherId = if (settlement.fromUser?.id == DemoData.ME_ID) {
                settlement.toUser?.id
            } else {
                settlement.fromUser?.id
            }
            val incoming = settlement.toUser?.id == DemoData.ME_ID
            otherId?.let { adjustPerson(it, if (incoming) settlement.amountMinor else -settlement.amountMinor) }
            settlements.replaceAll { if (it.id == id) it.copy(status = "REJECTED") else it }
        }
        Unit
    }

    override suspend fun remind(body: RemindRequest): Response<RemindResponse> {
        val owed = balance.people.firstOrNull { it.userId == body.userId }?.netMinor ?: 0
        if (owed <= 0) {
            return error(400, "NOTHING_OWED", "That person does not owe you anything right now.")
        }
        return ok(RemindResponse(owed))
    }

    // -------------------------------------------------------------------- share ---

    override suspend fun createShareLink(
        body: CreateShareLinkRequest
    ): Response<ShareLinkResponse> {
        val token = UUID.randomUUID().toString().take(6)
        return ok(
            ShareLinkResponse(
                ShareLinkDto(
                    id = UUID.randomUUID().toString(),
                    token = token,
                    scope = body.scope,
                    url = "https://snaptab.app/t/$token",
                    createdAt = DemoData.iso(Instant.now())
                )
            )
        )
    }

    override suspend fun revokeShareLink(id: String): Response<Unit> = ok(Unit)

    // ----------------------------------------------------------------- insights ---

    override suspend fun monthlySummary(month: String?, kind: String): Response<MonthlySummaryResponse> =
        ok(MonthlySummaryResponse(DemoData.monthly(month ?: java.time.YearMonth.now().toString(), kind)))

    override suspend fun trend(months: Int, kind: String): Response<TrendResponse> =
        ok(TrendResponse("INR", DemoData.trend(months)))

    override suspend fun topMerchants(month: String?, limit: Int): Response<MerchantsResponse> = ok(
        MerchantsResponse(
            expenses
                .filter { it.status != "VOID" }
                .groupBy { it.merchantName.orEmpty() }
                .map { (merchant, rows) ->
                    MerchantSpendDto(
                        merchant = merchant,
                        category = rows.first().category,
                        spentMinor = rows.sumOf { it.viewer.yourShareMinor },
                        count = rows.size
                    )
                }
                .sortedByDescending { it.spentMinor }
                .take(limit)
        )
    )

    // ------------------------------------------------------------------ helpers ---

    private fun buildExpense(body: CreateExpenseRequest): ExpenseDto {
        val id = UUID.randomUUID().toString()
        val category = body.categorySlug
            ?.let { slug -> DemoData.categories.firstOrNull { it.slug == slug } }
            ?: guessCategory(body.merchantName)
        val payerId = DemoData.ME_ID
        val participants = body.participants.orEmpty()

        return ExpenseDto(
            id = id,
            kind = body.kind,
            source = body.source,
            status = "OPEN",
            merchantName = body.merchantName,
            note = body.note,
            occurredAt = body.occurredAt ?: DemoData.iso(Instant.now()),
            currency = body.currency,
            category = category,
            categorySource = if (body.categorySlug != null) "USER" else "SUGGESTED",
            categoryConfidence = if (body.categorySlug != null) null else 0.9,
            group = body.groupId?.let { groupId ->
                groups.firstOrNull { it.id == groupId }?.let { GroupSummaryDto(it.id, it.name, it.iconKey) }
            },
            paidBy = user,
            totals = ExpenseTotalsDto(
                itemTotalMinor = body.itemTotalMinor ?: body.totalMinor,
                serviceChargeMinor = body.serviceChargeMinor,
                taxMinor = body.taxMinor,
                discountMinor = body.discountMinor,
                tipMinor = body.tipMinor,
                roundOffMinor = body.roundOffMinor,
                totalMinor = body.totalMinor
            ),
            splitMethod = body.splitMethod,
            extrasMode = body.extrasMode,
            items = body.items.mapIndexed { index, item ->
                ExpenseItemDto(
                    id = "$id-item-$index",
                    name = item.name,
                    quantityMilli = item.quantityMilli,
                    unitPriceMinor = item.unitPriceMinor,
                    amountMinor = item.amountMinor
                )
            },
            taxLines = body.taxLines,
            shares = if (body.kind == "SHARED") {
                shareOut(body.totalMinor, body.splitMethod ?: "EQUAL", participants, payerId)
            } else {
                emptyList()
            },
            viewer = if (body.kind == "SHARED") {
                viewerFor(body.totalMinor, payerId, participants, body.splitMethod ?: "EQUAL")
            } else {
                ExpenseViewerDto(true, body.totalMinor, 0, 0)
            }
        )
    }

    /**
     * The same arithmetic the server uses, so a demo split adds up to the exact total and
     * a preview never disagrees with what would be saved.
     */
    private fun shareOut(
        totalMinor: Long,
        method: String,
        participants: List<ParticipantRequest>,
        payerId: String
    ): List<ExpenseShareDto> {
        if (participants.isEmpty()) return emptyList()
        val weights = participants.map { participant ->
            when (method) {
                "EXACT", "PERCENT", "SHARES" -> participant.value ?: 0L
                else -> 1L
            }
        }
        val preferIndex = participants.indexOfFirst { it.userId == payerId }.coerceAtLeast(0)
        val amounts = if (method == "EXACT") {
            weights
        } else {
            Money.apportion(totalMinor, weights, preferIndex)
        }

        return participants.mapIndexed { index, participant ->
            val userId = participant.userId ?: UUID.randomUUID().toString()
            val amount = amounts.getOrElse(index) { 0L }
            ExpenseShareDto(
                userId = userId,
                user = DemoData.userById(userId),
                amountMinor = amount,
                paidMinor = if (userId == payerId) amount else 0,
                weightBp = if (totalMinor == 0L) 0 else ((amount * 10_000) / totalMinor).toInt(),
                inputValue = participant.value,
                settledAt = if (userId == payerId) DemoData.iso(Instant.now()) else null
            )
        }
    }

    private fun viewerFor(
        totalMinor: Long,
        payerId: String,
        participants: List<ParticipantRequest>,
        method: String
    ): ExpenseViewerDto {
        val shares = shareOut(totalMinor, method, participants, payerId)
        val mine = shares.firstOrNull { it.userId == DemoData.ME_ID }
        val isPayer = payerId == DemoData.ME_ID
        return ExpenseViewerDto(
            isPayer = isPayer,
            yourShareMinor = mine?.amountMinor ?: 0,
            youAreOwedMinor = if (isPayer) {
                shares.filter { it.userId != DemoData.ME_ID }.sumOf { it.amountMinor - it.paidMinor }
            } else {
                0
            },
            youOweMinor = if (isPayer) 0 else (mine?.let { it.amountMinor - it.paidMinor } ?: 0)
        )
    }

    private fun markAlertLinked(alertId: String, expenseId: String) {
        alerts.replaceAll {
            if (it.id == alertId) it.copy(status = "LINKED", expenseId = expenseId) else it
        }
    }

    private fun markSharesSettled(otherId: String, incoming: Boolean) {
        expenses.replaceAll { expense ->
            expense.copy(
                shares = expense.shares.map { share ->
                    val relevant = if (incoming) share.userId == otherId else share.userId == DemoData.ME_ID
                    if (relevant && share.settledAt == null) {
                        share.copy(paidMinor = share.amountMinor, settledAt = DemoData.iso(Instant.now()))
                    } else {
                        share
                    }
                }
            )
        }
        recomputeBalance()
    }

    private fun adjustPerson(userId: String, delta: Long) {
        balance = balance.copy(
            people = balance.people.map {
                if (it.userId == userId) it.copy(netMinor = it.netMinor + delta) else it
            }
        ).let { updated ->
            val people = updated.people.filter { it.netMinor != 0L }
            updated.copy(
                people = people,
                owedToYouMinor = people.filter { it.netMinor > 0 }.sumOf { it.netMinor },
                owedByYouMinor = people.filter { it.netMinor < 0 }.sumOf { -it.netMinor },
                netMinor = people.sumOf { it.netMinor },
                suggestedTransfers = people.filter { it.netMinor > 0 }.map {
                    SuggestedTransferDto(it.userId, DemoData.ME_ID, it.netMinor, it.name, "You")
                }
            )
        }
    }

    /** Rebuilds the balance from the shares, so a new split immediately shows up in it. */
    private fun recomputeBalance() {
        val net = mutableMapOf<String, Long>()
        expenses.filter { it.status != "VOID" && it.kind == "SHARED" }.forEach { expense ->
            expense.shares.forEach { share ->
                val outstanding = share.amountMinor - share.paidMinor
                if (outstanding == 0L) return@forEach
                when {
                    expense.paidBy.id == DemoData.ME_ID && share.userId != DemoData.ME_ID ->
                        net[share.userId] = (net[share.userId] ?: 0) + outstanding
                    expense.paidBy.id != DemoData.ME_ID && share.userId == DemoData.ME_ID ->
                        net[expense.paidBy.id] = (net[expense.paidBy.id] ?: 0) - outstanding
                }
            }
        }
        val people = net.filterValues { it != 0L }.map { (userId, amount) ->
            val person = DemoData.userById(userId)
            PersonBalanceDto(userId, person.name, person.avatarUrl, person.status, amount)
        }.sortedByDescending { it.netMinor }

        balance = BalanceDto(
            currency = "INR",
            owedToYouMinor = people.filter { it.netMinor > 0 }.sumOf { it.netMinor },
            owedByYouMinor = people.filter { it.netMinor < 0 }.sumOf { -it.netMinor },
            netMinor = people.sumOf { it.netMinor },
            people = people,
            suggestedTransfers = people.filter { it.netMinor > 0 }.map {
                SuggestedTransferDto(it.userId, DemoData.ME_ID, it.netMinor, it.name, "You")
            } + people.filter { it.netMinor < 0 }.map {
                SuggestedTransferDto(DemoData.ME_ID, it.userId, -it.netMinor, "You", it.name)
            }
        )
    }

    private fun suggestFor(merchant: String?, direction: String): CategoryDto? {
        if (direction == "CREDIT") return null
        return guessCategory(merchant)
    }

    private fun guessCategory(merchant: String?): CategoryDto {
        val text = merchant?.lowercase().orEmpty()
        return when {
            listOf("swiggy", "zomato", "social", "cafe", "tokai", "bar").any { text.contains(it) } -> DemoData.restaurant
            listOf("bigbasket", "blinkit", "zepto", "kirana", "dmart").any { text.contains(it) } -> DemoData.groceries
            listOf("croma", "zudio", "amazon", "flipkart").any { text.contains(it) } -> DemoData.shopping
            listOf("uber", "ola", "indigo", "irctc", "hotel").any { text.contains(it) } -> DemoData.travel
            listOf("oil", "petrol", "diesel", "hpcl").any { text.contains(it) } -> DemoData.fuel
            listOf("act ", "airtel", "jio", "fibernet").any { text.contains(it) } -> DemoData.utilities
            else -> DemoData.other
        }
    }

    private fun session() = AuthResponse(
        accessToken = "demo-access-token",
        refreshToken = "demo-refresh-token",
        expiresIn = 60 * 60 * 24 * 365,
        user = user
    )

    private fun maskForDemo(contact: String): String = when {
        contact.contains('@') -> {
            val local = contact.substringBefore('@')
            "${local.take(1)}${"*".repeat(maxOf(1, local.length - 2))}${local.takeLast(1)}@${contact.substringAfter('@')}"
        }
        else -> {
            val digits = contact.filter(Char::isDigit)
            "+${digits.take(2)} ••••• ${digits.takeLast(4)}"
        }
    }

    /** Every call pauses briefly, so loading states are real rather than skipped over. */
    private suspend fun <T> ok(body: T): Response<T> {
        delay(LATENCY_MS)
        return Response.success(body)
    }

    private suspend fun <T> mutate(block: () -> T): Response<T> {
        delay(LATENCY_MS)
        return mutex.withLock { Response.success(block()) }
    }

    private suspend fun mutateOrError(
        expenseId: String,
        transform: (ExpenseDto) -> ExpenseDto
    ): Response<ExpenseResponse> {
        delay(LATENCY_MS)
        return mutex.withLock {
            val index = expenses.indexOfFirst { it.id == expenseId }
            if (index < 0) {
                errorNow(404, "NOT_FOUND", "That expense could not be found.")
            } else {
                val updated = transform(expenses[index])
                expenses[index] = updated
                recomputeBalance()
                Response.success(ExpenseResponse(updated))
            }
        }
    }

    /** Failures use the API's real envelope, so the app's error handling is exercised. */
    private suspend fun <T> error(status: Int, code: String, message: String): Response<T> {
        delay(LATENCY_MS)
        return errorNow(status, code, message)
    }

    private fun <T> errorNow(status: Int, code: String, message: String): Response<T> =
        Response.error(
            status,
            """{"error":{"code":"$code","message":"$message"}}"""
                .toResponseBody("application/json".toMediaType())
        )

    companion object {
        const val ME_ID = DemoData.ME_ID
        const val DEMO_OTP = "492026"
        private const val LATENCY_MS = 280L
    }
}
