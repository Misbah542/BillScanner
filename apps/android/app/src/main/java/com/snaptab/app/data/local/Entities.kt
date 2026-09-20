package com.snaptab.app.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The offline cache. Not a second source of truth — the server owns the data — but
 * enough that opening the app on the train shows last night's dinner rather than a
 * spinner. The original app had no persistence at all: a scanned bill lived in a
 * MutableStateFlow and was gone on process death.
 */

@Entity(tableName = "expenses", indices = [Index("occurredAtEpoch"), Index("kind")])
data class ExpenseEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val source: String,
    val status: String,
    val merchantName: String?,
    val note: String?,
    val occurredAtEpoch: Long,
    val currency: String,
    val categorySlug: String?,
    val categoryName: String?,
    val categoryColorHex: String?,
    val groupId: String?,
    val groupName: String?,
    val paidByUserId: String,
    val paidByName: String?,
    val totalMinor: Long,
    val itemTotalMinor: Long,
    val splitMethod: String?,
    val yourShareMinor: Long,
    val youAreOwedMinor: Long,
    val youOweMinor: Long,
    val isPayer: Boolean,
    val shareCount: Int,
    val hasReceipt: Boolean,
    /** When this row was last refreshed from the server, for staleness display. */
    val cachedAtEpoch: Long
)

@Entity(tableName = "alerts", indices = [Index("status"), Index("occurredAtEpoch")])
data class AlertEntity(
    @PrimaryKey val id: String,
    val direction: String,
    val status: String,
    val amountMinor: Long,
    val currency: String,
    val merchantRaw: String?,
    val accountMask: String?,
    val accountKind: String,
    val bankName: String?,
    val referenceNumber: String?,
    val confidence: Double,
    val occurredAtEpoch: Long,
    val expenseId: String?,
    val suggestedCategorySlug: String?,
    val suggestedCategoryName: String?,
    val cachedAtEpoch: Long
)

/**
 * An alert parsed on the device but not yet accepted by the server — because the phone
 * was offline, or the user was not signed in when it arrived. Drained by
 * AlertSyncWorker.
 *
 * `fingerprint` is the primary key, so the same message re-read after a permission
 * re-grant cannot queue twice.
 */
@Entity(tableName = "pending_alerts")
data class PendingAlertEntity(
    @PrimaryKey val fingerprint: String,
    val direction: String,
    val amountMinor: Long,
    val currency: String,
    val merchantRaw: String?,
    val accountMask: String?,
    val accountKind: String,
    val bankId: String?,
    val bankName: String?,
    val referenceNumber: String?,
    val confidence: Double,
    val occurredAtEpoch: Long,
    /** Held only while the user has opted in to keeping message text. */
    @ColumnInfo(defaultValue = "NULL") val rawBody: String?,
    val attempts: Int = 0,
    val queuedAtEpoch: Long
)

@Entity(tableName = "groups")
data class GroupEntity(
    @PrimaryKey val id: String,
    val name: String,
    val iconKey: String,
    val currency: String,
    val memberCount: Int,
    val pendingCount: Int,
    val netMinor: Long,
    val owedToYouMinor: Long,
    val owedByYouMinor: Long,
    val cachedAtEpoch: Long
)

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey val slug: String,
    val name: String,
    val iconKey: String,
    val colorHex: String,
    val tintHex: String,
    val kind: String,
    val custom: Boolean,
    val sortOrder: Int
)

@Entity(tableName = "monthly_summaries")
data class MonthlySummaryEntity(
    @PrimaryKey val monthAndKind: String,
    val month: String,
    val kind: String,
    val currency: String,
    val personalMinor: Long,
    val sharedShareMinor: Long,
    val spentMinor: Long,
    val paidOutMinor: Long,
    val owedToYouMinor: Long,
    val owedByYouMinor: Long,
    val personalCount: Int,
    val sharedCount: Int,
    val previousSpentMinor: Long?,
    /** The category breakdown, kept as JSON because it is only ever read whole. */
    val byCategoryJson: String,
    val cachedAtEpoch: Long
)
