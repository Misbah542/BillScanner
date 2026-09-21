package com.snaptab.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ExpenseDao {
    /** `kind` of null means every kind — the "All" column on the home screen. */
    @Query(
        """
        SELECT * FROM expenses
        WHERE status != 'VOID' AND (:kind IS NULL OR kind = :kind)
        ORDER BY occurredAtEpoch DESC, id DESC
        LIMIT :limit
        """
    )
    fun observe(kind: String?, limit: Int = 50): Flow<List<ExpenseEntity>>

    @Query("SELECT * FROM expenses WHERE id = :id")
    fun observeOne(id: String): Flow<ExpenseEntity?>

    @Query("SELECT * FROM expenses WHERE groupId = :groupId AND status != 'VOID' ORDER BY occurredAtEpoch DESC")
    fun observeForGroup(groupId: String): Flow<List<ExpenseEntity>>

    @Upsert
    suspend fun upsert(expenses: List<ExpenseEntity>)

    @Query("DELETE FROM expenses WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM expenses")
    suspend fun clear()
}

@Dao
interface AlertDao {
    @Query("SELECT * FROM alerts WHERE (:status IS NULL OR status = :status) ORDER BY occurredAtEpoch DESC LIMIT :limit")
    fun observe(status: String?, limit: Int = 60): Flow<List<AlertEntity>>

    @Query("SELECT COUNT(*) FROM alerts WHERE status = 'UNMATCHED'")
    fun observeUnmatchedCount(): Flow<Int>

    @Upsert
    suspend fun upsert(alerts: List<AlertEntity>)

    @Query("UPDATE alerts SET status = :status WHERE id = :id")
    suspend fun setStatus(id: String, status: String)

    @Query("DELETE FROM alerts")
    suspend fun clear()
}

@Dao
interface PendingAlertDao {
    /** Ignores a duplicate fingerprint rather than replacing it, so attempts are kept. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun enqueue(alert: PendingAlertEntity): Long

    @Query("SELECT * FROM pending_alerts ORDER BY queuedAtEpoch ASC LIMIT :limit")
    suspend fun take(limit: Int = 50): List<PendingAlertEntity>

    @Query("SELECT COUNT(*) FROM pending_alerts")
    fun observeCount(): Flow<Int>

    @Query("DELETE FROM pending_alerts WHERE fingerprint IN (:fingerprints)")
    suspend fun remove(fingerprints: List<String>)

    @Query("UPDATE pending_alerts SET attempts = attempts + 1 WHERE fingerprint IN (:fingerprints)")
    suspend fun bumpAttempts(fingerprints: List<String>)

    /** Gives up on anything that has failed this often; it is never going to be accepted. */
    @Query("DELETE FROM pending_alerts WHERE attempts >= :maxAttempts")
    suspend fun dropExhausted(maxAttempts: Int = 8)

    @Query("DELETE FROM pending_alerts")
    suspend fun clear()
}

@Dao
interface GroupDao {
    @Query("SELECT * FROM groups ORDER BY name COLLATE NOCASE ASC")
    fun observe(): Flow<List<GroupEntity>>

    @Query("SELECT * FROM groups WHERE id = :id")
    fun observeOne(id: String): Flow<GroupEntity?>

    @Upsert
    suspend fun upsert(groups: List<GroupEntity>)

    @Query("DELETE FROM groups")
    suspend fun clear()
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY sortOrder ASC, name COLLATE NOCASE ASC")
    fun observe(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE slug = :slug")
    suspend fun bySlug(slug: String): CategoryEntity?

    @Upsert
    suspend fun upsert(categories: List<CategoryEntity>)
}

@Dao
interface MonthlySummaryDao {
    @Query("SELECT * FROM monthly_summaries WHERE monthAndKind = :key")
    fun observe(key: String): Flow<MonthlySummaryEntity?>

    @Upsert
    suspend fun upsert(summary: MonthlySummaryEntity)

    @Query("DELETE FROM monthly_summaries")
    suspend fun clear()
}
