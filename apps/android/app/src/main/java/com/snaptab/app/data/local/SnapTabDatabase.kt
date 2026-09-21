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
        MonthlySummaryEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class SnapTabDatabase : RoomDatabase() {
    abstract fun expenses(): ExpenseDao
    abstract fun alerts(): AlertDao
    abstract fun pendingAlerts(): PendingAlertDao
    abstract fun groups(): GroupDao
    abstract fun categories(): CategoryDao
    abstract fun monthlySummaries(): MonthlySummaryDao

    companion object {
        const val NAME = "snaptab.db"
    }
}
