package com.snaptab.app.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * The wire format, one file so the whole contract is readable at a glance.
 *
 * Every amount is `Long` minor units, matching the API and Postgres. Names use
 * `@SerialName` where the JSON differs, and the app uses kotlinx-serialization
 * throughout — the old project annotated its models with `@SerialName` and then
 * installed Gson, which ignores those annotations, so every field of the totals
 * object silently deserialized as zero.
 */

// ------------------------------------------------------------------- errors ---

@Serializable
data class ApiErrorEnvelope(val error: ApiErrorBody)

@Serializable
data class ApiErrorBody(
    val code: String,
    val message: String,
    val details: JsonElement? = null
)

// --------------------------------------------------------------------- auth ---

@Serializable
data class OtpStartRequest(val contact: String)

@Serializable
data class OtpStartResponse(
    val channel: String,
    val sentTo: String,
    val expiresInSeconds: Int,
    /** Only present outside production, so a local build can sign in with no mailer. */
    val devCode: String? = null
)

@Serializable
data class DeviceRequest(
    val installId: String,
    val platform: String = "ANDROID",
    val pushToken: String? = null,
    val appVersion: String? = null,
    val osVersion: String? = null,
    val model: String? = null,
    val smsEnabled: Boolean? = null
)

@Serializable
data class OtpVerifyRequest(
    val contact: String,
    val code: String,
    val name: String? = null,
    val device: DeviceRequest? = null
)

@Serializable
data class GoogleSignInRequest(
    val idToken: String,
    val device: DeviceRequest? = null
)

@Serializable
data class RefreshRequest(val refreshToken: String)

@Serializable
data class AuthResponse(
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String = "Bearer",
    val expiresIn: Int,
    val user: UserDto? = null
)

@Serializable
data class AuthMethodsResponse(
    val email: Boolean,
    val phone: Boolean,
    val google: Boolean
)

// -------------------------------------------------------------------- users ---

@Serializable
data class UserDto(
    val id: String,
    val name: String? = null,
    val email: String? = null,
    val phone: String? = null,
    val avatarUrl: String? = null,
    val status: String = "ACTIVE",
    val emailVerified: Boolean = false,
    val phoneVerified: Boolean = false,
    val currency: String = "INR",
    val locale: String = "en-IN",
    val timezone: String = "Asia/Kolkata",
    val keepAlertBodies: Boolean = false,
    val googleLinked: Boolean = false,
    val createdAt: String? = null,
    /** Set on a member who was added by contact and has not signed in yet. */
    val pending: Boolean = false
)

@Serializable
data class MeResponse(val user: UserDto)

@Serializable
data class UpdateMeRequest(
    val name: String? = null,
    val currency: String? = null,
    val locale: String? = null,
    val timezone: String? = null,
    val keepAlertBodies: Boolean? = null
)

@Serializable
data class LookupResponse(
    val found: Boolean,
    val user: UserDto? = null,
    val kind: String
)

@Serializable
data class RecentPeopleResponse(val people: List<RecentPersonDto>)

@Serializable
data class RecentPersonDto(
    val user: UserDto,
    val lastSplitAt: String? = null,
    val count: Int = 0
)

@Serializable
data class DeviceResponse(val device: DeviceStateDto)

@Serializable
data class DeviceStateDto(
    val id: String,
    val platform: String,
    val smsEnabled: Boolean,
    val lastSeenAt: String
)

// --------------------------------------------------------------- categories ---

@Serializable
data class CategoryDto(
    val id: String? = null,
    val slug: String,
    val name: String,
    val iconKey: String = "dots",
    val colorHex: String = "#8C867B",
    val tintHex: String = "#F0EBE0",
    val kind: String = "SPEND",
    val custom: Boolean = false
)

@Serializable
data class CategoriesResponse(
    val categories: List<CategoryDto>,
    val taxonomyVersion: Int
)

@Serializable
data class CategorySuggestionDto(
    val slug: String,
    val name: String,
    val colorHex: String,
    val tintHex: String,
    val confidence: Double,
    val reasons: List<String> = emptyList()
)

@Serializable
data class SuggestionsResponse(val suggestions: List<CategorySuggestionDto>)

// ----------------------------------------------------------------- expenses ---

@Serializable
data class ExpenseTotalsDto(
    val itemTotalMinor: Long = 0,
    val serviceChargeMinor: Long = 0,
    val taxMinor: Long = 0,
    val discountMinor: Long = 0,
    val tipMinor: Long = 0,
    val roundOffMinor: Long = 0,
    val totalMinor: Long
)

@Serializable
data class ItemAssignmentDto(
    val userId: String,
    val weight: Int = 1,
    val amountMinor: Long
)

@Serializable
data class ExpenseItemDto(
    val id: String? = null,
    val name: String,
    val quantityMilli: Int = 1000,
    val unitPriceMinor: Long,
    val amountMinor: Long,
    val assignedTo: List<ItemAssignmentDto> = emptyList()
)

@Serializable
data class TaxLineDto(
    val label: String,
    val kind: String = "OTHER",
    val rateBp: Int? = null,
    val amountMinor: Long
)

@Serializable
data class ExpenseShareDto(
    val userId: String,
    val user: UserDto? = null,
    val amountMinor: Long,
    val paidMinor: Long = 0,
    val weightBp: Int = 0,
    val inputValue: Long? = null,
    val settledAt: String? = null
)

@Serializable
data class GroupSummaryDto(
    val id: String,
    val name: String,
    val iconKey: String = "home",
    val currency: String = "INR"
)

@Serializable
data class MatchedAlertDto(
    val id: String,
    val accountMask: String? = null,
    val bankName: String? = null,
    val amountMinor: Long,
    val occurredAt: String,
    val direction: String
)

/** What the viewer needs to see, computed server-side so the client cannot disagree. */
@Serializable
data class ExpenseViewerDto(
    val isPayer: Boolean,
    val yourShareMinor: Long,
    val youAreOwedMinor: Long,
    val youOweMinor: Long
)

@Serializable
data class ExpenseDto(
    val id: String,
    val kind: String,
    val source: String,
    val status: String,
    val merchantName: String? = null,
    val note: String? = null,
    val occurredAt: String,
    val currency: String = "INR",
    val category: CategoryDto? = null,
    val categorySource: String = "DEFAULT",
    val categoryConfidence: Double? = null,
    val group: GroupSummaryDto? = null,
    val paidBy: UserDto,
    val totals: ExpenseTotalsDto,
    val splitMethod: String? = null,
    val extrasMode: String? = null,
    val items: List<ExpenseItemDto> = emptyList(),
    val taxLines: List<TaxLineDto> = emptyList(),
    val shares: List<ExpenseShareDto> = emptyList(),
    val matchedAlerts: List<MatchedAlertDto> = emptyList(),
    val viewer: ExpenseViewerDto,
    val hasReceipt: Boolean = false,
    val createdAt: String? = null,
    val updatedAt: String? = null
)

@Serializable
data class ExpenseResponse(val expense: ExpenseDto)

@Serializable
data class ExpenseListResponse(
    val expenses: List<ExpenseDto>,
    val nextCursor: String? = null
)

@Serializable
data class ParticipantRequest(
    val userId: String? = null,
    val email: String? = null,
    val phone: String? = null,
    val displayName: String? = null,
    /** Minor units for EXACT, basis points for PERCENT, a count for SHARES. */
    val value: Long? = null
)

@Serializable
data class ItemAssignmentRequest(
    val itemId: String,
    val assignments: List<AssignmentEntry>
) {
    @Serializable
    data class AssignmentEntry(val userId: String, val weight: Int = 1)
}

@Serializable
data class ExpenseItemRequest(
    val name: String,
    val quantityMilli: Int = 1000,
    val unitPriceMinor: Long,
    val amountMinor: Long
)

@Serializable
data class CreateExpenseRequest(
    /** PERSONAL (all yours, may have no items) or SHARED (split with people). */
    val kind: String = "PERSONAL",
    val source: String = "MANUAL",
    val groupId: String? = null,
    val merchantName: String? = null,
    val note: String? = null,
    val occurredAt: String? = null,
    val currency: String = "INR",
    val categorySlug: String? = null,
    val itemTotalMinor: Long? = null,
    val serviceChargeMinor: Long = 0,
    val taxMinor: Long = 0,
    val discountMinor: Long = 0,
    val tipMinor: Long = 0,
    val roundOffMinor: Long = 0,
    val totalMinor: Long,
    val items: List<ExpenseItemRequest> = emptyList(),
    val taxLines: List<TaxLineDto> = emptyList(),
    val splitMethod: String? = null,
    val participants: List<ParticipantRequest>? = null,
    val itemAssignments: List<ItemAssignmentRequest>? = null,
    val extrasMode: String = "PROPORTIONAL",
    val scanId: String? = null,
    val alertId: String? = null,
    val receiptAssetKey: String? = null
)

@Serializable
data class UpdateExpenseRequest(
    val merchantName: String? = null,
    val note: String? = null,
    val occurredAt: String? = null,
    val categorySlug: String? = null,
    val kind: String? = null,
    val groupId: String? = null
)

@Serializable
data class SetSplitRequest(
    val method: String,
    val participants: List<ParticipantRequest> = emptyList(),
    val itemAssignments: List<ItemAssignmentRequest> = emptyList(),
    val extrasMode: String = "PROPORTIONAL"
)

// -------------------------------------------------------------------- scans ---

@Serializable
data class ParsedReceiptItemDto(
    val name: String,
    val quantityMilli: Int = 1000,
    val unitPriceMinor: Long,
    val amountMinor: Long,
    val sourceLine: String? = null
)

@Serializable
data class ParsedReceiptDto(
    val merchantName: String? = null,
    val occurredAt: String? = null,
    val invoiceNumber: String? = null,
    val currency: String = "INR",
    val items: List<ParsedReceiptItemDto> = emptyList(),
    val taxLines: List<TaxLineDto> = emptyList(),
    val totals: ExpenseTotalsDto,
    val confidence: Double = 0.0,
    val warnings: List<String> = emptyList(),
    val suggestions: List<CategorySuggestionDto> = emptyList()
)

@Serializable
data class ScanErrorDto(val code: String? = null, val message: String? = null)

@Serializable
data class ScanDto(
    val id: String,
    /** QUEUED, PROCESSING, SUCCEEDED or FAILED. */
    val status: String,
    val provider: String = "stub",
    val confidence: Double? = null,
    val result: ParsedReceiptDto? = null,
    val error: ScanErrorDto? = null,
    val attempts: Int = 0,
    val expenseId: String? = null,
    val imageUrl: String? = null,
    val queuedAt: String? = null,
    val startedAt: String? = null,
    val finishedAt: String? = null
)

@Serializable
data class ScanResponse(
    val scan: ScanDto,
    val deduplicated: Boolean = false,
    val pollAfterMs: Long = 1200
)

// --------------------------------------------------------------- bank alerts ---

@Serializable
data class IngestAlertDto(
    val direction: String,
    val amountMinor: Long,
    val currency: String = "INR",
    val merchantRaw: String? = null,
    val accountMask: String? = null,
    val accountKind: String = "UNKNOWN",
    val bankId: String? = null,
    val bankName: String? = null,
    val referenceNumber: String? = null,
    val channel: String = "SMS",
    val occurredAt: String,
    val fingerprint: String,
    val confidence: Double = 0.0,
    /** Sent only when the user opted in to keeping message text. */
    val rawBody: String? = null
)

@Serializable
data class IngestAlertsRequest(
    val alerts: List<IngestAlertDto>,
    val rulesVersion: Int? = null
)

@Serializable
data class IngestOutcomeDto(
    val alertId: String,
    val created: Boolean,
    val status: String,
    val suggestedCategorySlug: String? = null
)

@Serializable
data class IngestAlertsResponse(
    val results: List<IngestOutcomeDto>,
    val created: Int = 0,
    val duplicates: Int = 0,
    val serverRulesVersion: Int = 0
)

@Serializable
data class BankAlertDto(
    val id: String,
    val direction: String,
    val channel: String = "SMS",
    val status: String,
    val amountMinor: Long,
    val currency: String = "INR",
    val merchantRaw: String? = null,
    val merchantNormalized: String? = null,
    val accountMask: String? = null,
    val accountKind: String = "UNKNOWN",
    val bankName: String? = null,
    val referenceNumber: String? = null,
    val confidence: Double = 0.0,
    val occurredAt: String,
    val createdAt: String? = null,
    val expenseId: String? = null,
    val settlementId: String? = null,
    val suggestedCategory: CategoryDto? = null
)

@Serializable
data class AlertListResponse(
    val alerts: List<BankAlertDto>,
    val nextCursor: String? = null,
    val counts: Map<String, Int> = emptyMap()
)

/**
 * Turning an alert into an expense. `kind` is the whole point: a debit is either
 * yours alone, or it goes on a group you already have and gets split there.
 */
@Serializable
data class AlertToExpenseRequest(
    val kind: String = "PERSONAL",
    val categorySlug: String? = null,
    val note: String? = null,
    val groupId: String? = null,
    val splitMethod: String? = null,
    val participants: List<ParticipantRequest>? = null
)

@Serializable
data class LinkAlertRequest(val expenseId: String)

@Serializable
data class LinkAlertResponse(
    val alert: BankAlertDto,
    val amountsMatch: Boolean,
    val differenceMinor: Long
)

@Serializable
data class LinkCandidateDto(
    val id: String,
    val merchantName: String? = null,
    val totalMinor: Long,
    val occurredAt: String
)

@Serializable
data class AlertSuggestionsResponse(
    val categories: List<CategorySuggestionDto> = emptyList(),
    val linkCandidates: List<LinkCandidateDto> = emptyList()
)

// ------------------------------------------------------------------- groups ---

@Serializable
data class GroupMemberDto(
    val role: String = "MEMBER",
    val joinedAt: String? = null,
    val user: UserDto
)

@Serializable
data class GroupBalanceSummaryDto(
    val netMinor: Long = 0,
    val owedToYouMinor: Long = 0,
    val owedByYouMinor: Long = 0
)

@Serializable
data class GroupDto(
    val id: String,
    val name: String,
    val description: String? = null,
    val currency: String = "INR",
    val iconKey: String = "home",
    val defaultSplitMethod: String = "EQUAL",
    val expenseCount: Int = 0,
    val members: List<GroupMemberDto> = emptyList(),
    val shareBaseUrl: String? = null,
    val balance: GroupBalanceSummaryDto? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null
)

@Serializable
data class GroupListResponse(val groups: List<GroupDto>)

@Serializable
data class PendingInviteDto(
    val id: String,
    val channel: String,
    val destination: String? = null,
    val createdAt: String? = null,
    val expiresAt: String? = null
)

@Serializable
data class GroupDetailResponse(
    val group: GroupDto,
    val pendingInvites: List<PendingInviteDto> = emptyList(),
    val balance: BalanceDto
)

@Serializable
data class CreateGroupRequest(
    val name: String,
    val description: String? = null,
    val currency: String = "INR",
    val iconKey: String = "home",
    val defaultSplitMethod: String = "EQUAL",
    val invite: List<String> = emptyList()
)

@Serializable
data class InvitedPersonDto(
    val contact: String,
    val userId: String,
    val alreadyOnSnapTab: Boolean
)

@Serializable
data class CreateGroupResponse(
    val group: GroupDto,
    val invited: List<InvitedPersonDto> = emptyList()
)

@Serializable
data class AddMembersRequest(
    val contacts: List<String>,
    val displayNames: Map<String, String>? = null
)

@Serializable
data class AddMembersResponse(
    val group: GroupDto,
    val added: List<InvitedPersonDto> = emptyList()
)

@Serializable
data class GroupActivityEntryDto(
    val type: String,
    val at: String,
    val expense: ActivityExpenseDto? = null,
    val settlement: SettlementDto? = null
)

@Serializable
data class ActivityExpenseDto(
    val id: String,
    val merchantName: String? = null,
    val totalMinor: Long,
    val currency: String = "INR",
    val occurredAt: String,
    val paidBy: UserDto,
    val category: CategoryDto? = null,
    val yourShareMinor: Long = 0,
    val yourShareSettled: Boolean = false
)

@Serializable
data class GroupActivityResponse(val activity: List<GroupActivityEntryDto>)

// ------------------------------------------------------- balance & settling ---

@Serializable
data class PersonBalanceDto(
    val userId: String,
    val name: String? = null,
    val avatarUrl: String? = null,
    val status: String = "ACTIVE",
    /** Positive: they owe you. Negative: you owe them. */
    val netMinor: Long
)

@Serializable
data class SuggestedTransferDto(
    val fromUserId: String,
    val toUserId: String,
    val amountMinor: Long,
    val fromName: String? = null,
    val toName: String? = null
)

@Serializable
data class BalanceDto(
    val currency: String = "INR",
    val owedToYouMinor: Long = 0,
    val owedByYouMinor: Long = 0,
    val netMinor: Long = 0,
    val people: List<PersonBalanceDto> = emptyList(),
    val suggestedTransfers: List<SuggestedTransferDto> = emptyList()
)

@Serializable
data class BalanceResponse(val balance: BalanceDto)

@Serializable
data class SettlementDto(
    val id: String,
    val amountMinor: Long,
    val currency: String = "INR",
    val method: String = "UPI",
    val status: String = "CONFIRMED",
    val note: String? = null,
    val createdAt: String? = null,
    val fromUser: UserDto? = null,
    val toUser: UserDto? = null,
    val group: GroupSummaryDto? = null
)

@Serializable
data class CreateSettlementRequest(
    /** Exactly one of these; the other side is always you. */
    val fromUserId: String? = null,
    val toUserId: String? = null,
    val amountMinor: Long,
    val currency: String = "INR",
    val method: String = "UPI",
    val note: String? = null,
    val groupId: String? = null,
    val expenseId: String? = null
)

@Serializable
data class CreateSettlementResponse(
    val settlement: SettlementDto,
    val unappliedMinor: Long = 0
)

@Serializable
data class SettlementListResponse(val settlements: List<SettlementDto>)

@Serializable
data class RemindRequest(val userId: String, val message: String? = null)

@Serializable
data class RemindResponse(val remindedMinor: Long)

// ------------------------------------------------------------------ sharing ---

@Serializable
data class CreateShareLinkRequest(
    val expenseId: String? = null,
    val groupId: String? = null,
    val scope: String = "SUMMARY",
    val expiresInDays: Int? = null
)

@Serializable
data class ShareLinkDto(
    val id: String,
    val token: String,
    val scope: String,
    val url: String,
    val expiresAt: String? = null,
    val createdAt: String? = null
)

@Serializable
data class ShareLinkResponse(val link: ShareLinkDto)

// ----------------------------------------------------------------- insights ---

@Serializable
data class CategorySpendDto(
    val slug: String,
    val name: String,
    val color: String,
    val spentMinor: Long,
    val shareBp: Int,
    val count: Int
)

/**
 * `spentMinor` is personal expenses in full plus your share of shared ones.
 * `paidOutMinor` is everything that left your account, other people's shares
 * included — a loan, reported separately and never folded into spending.
 */
@Serializable
data class MonthlySummaryDto(
    val month: String,
    val currency: String = "INR",
    val personalMinor: Long = 0,
    val sharedShareMinor: Long = 0,
    val spentMinor: Long = 0,
    val paidOutMinor: Long = 0,
    val owedToYouMinor: Long = 0,
    val owedByYouMinor: Long = 0,
    val expenseCount: Int = 0,
    val personalCount: Int = 0,
    val sharedCount: Int = 0,
    val byCategory: List<CategorySpendDto> = emptyList(),
    val previousSpentMinor: Long? = null
)

@Serializable
data class MonthlySummaryResponse(val summary: MonthlySummaryDto)

@Serializable
data class TrendPointDto(
    val month: String,
    val spentMinor: Long,
    val personalMinor: Long = 0,
    val sharedShareMinor: Long = 0,
    val expenseCount: Int = 0
)

@Serializable
data class TrendResponse(
    val currency: String = "INR",
    val points: List<TrendPointDto> = emptyList()
)

@Serializable
data class MerchantSpendDto(
    val merchant: String? = null,
    val category: CategoryDto? = null,
    val spentMinor: Long,
    val count: Int
)

@Serializable
data class MerchantsResponse(val merchants: List<MerchantSpendDto>)

// --------------------------------------------------------- notifications ---

@Serializable
data class NotificationDto(
    val id: String,
    val kind: String,
    val title: String,
    val body: String,
    val data: JsonElement? = null,
    val readAt: String? = null,
    val createdAt: String
)

@Serializable
data class NotificationListResponse(
    val notifications: List<NotificationDto>,
    val unreadCount: Int = 0
)

@Serializable
data class MarkReadRequest(val ids: List<String> = emptyList())

// ------------------------------------------------------------------ config ---

@Serializable
data class ConfigResponse(
    val auth: AuthMethodsResponse,
    val scan: ScanConfigDto,
    val rules: RulesVersionDto,
    val shareBaseUrl: String? = null
)

@Serializable
data class ScanConfigDto(val maxBytes: Long, val provider: String)

@Serializable
data class RulesVersionDto(val sms: Int, val categories: Int)

@Serializable
data class RulesVersionSingle(val version: Int)
