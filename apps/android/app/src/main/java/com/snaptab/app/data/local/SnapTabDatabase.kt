package com.snaptab.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        ExpenseEntity::class,
        AlertEntity::class,
        PendingAlertEntity::class,
        GroupEntity::class,
        CategoryEntity::class,
        MonthlySummaryEntity::class,
        BalanceEntity::class
    ],
    // 2: added the balances cache.
    version = 2,
    exportSchema = true
)
abstract class SnapTabDatabase : RoomDatabase() {
    abstract fun expenses(): ExpenseDao
    abstract fun alerts(): AlertDao
    abstract fun pendingAlerts(): PendingAlertDao
    abstract fun groups(): GroupDao
    abstract fun categories(): CategoryDao
    abstract fun monthlySummaries(): MonthlySummaryDao
    abstract fun balances(): BalanceDao

    companion object {
        const val NAME = "snaptab.db"
    }
}
