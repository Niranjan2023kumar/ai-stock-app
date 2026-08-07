

package com.example.myapplication3.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.example.myapplication3.database.dao.*
import com.example.myapplication3.database.entity.*

@Database(
    entities = [
        CandleEntity::class,
        RecommendationEntity::class,
        NewsEntity::class,
        WatchlistEntity::class,
        PortfolioPositionEntity::class,
        AuditTrailEntity::class,
        BacktestResultEntity::class,
        SectorDataEntity::class,
        SearchHistoryEntity::class,
        TrackedTradeEntity::class
    ],
    // Version history: 1 and 2 were pre-release dev versions (no schema saved),
    // 3 is the first version shipped to real users. 4 adds
    // TrackedTradeEntity.confidenceAtPick (P0 #12) via a real, non-destructive
    // Migration(3,4) in DatabaseModule. Any future schema change MUST bump this
    // number AND ship a Migration in DatabaseModule.addMigrations() — never rely
    // on destructive fallback; it would wipe the user's trade history.
    // exportSchema = true + room.schemaLocation (database/build.gradle.kts) dump
    // each version's schema JSON into database/schemas/ at build time, so every
    // future Migration can be written against the real old schema and tested.
    version = 4,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun candleDao(): CandleDao
    abstract fun recommendationDao(): RecommendationDao
    abstract fun newsDao(): NewsDao
    abstract fun watchlistDao(): WatchlistDao
    abstract fun portfolioDao(): PortfolioDao
    abstract fun auditTrailDao(): AuditTrailDao
    abstract fun backtestResultDao(): BacktestResultDao
    abstract fun sectorDataDao(): SectorDataDao
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun trackedTradeDao(): TrackedTradeDao
}
