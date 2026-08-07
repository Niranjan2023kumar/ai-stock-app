package com.example.myapplication3.database.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.myapplication3.core.common.Constants
import com.example.myapplication3.database.AppDatabase
import com.example.myapplication3.database.dao.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    /**
     * v3 -> v4 (P0 #12): add TrackedTradeEntity.confidenceAtPick.
     *
     * Non-destructive by construction: a single ADDITIVE `ALTER TABLE ... ADD
     * COLUMN`. Every existing row (the user's real trade journal) is untouched;
     * the new column comes up NULL for those rows, which the calibration/trust
     * layer already treats as "confidence unknown".
     *
     * No `DEFAULT` clause on purpose: the entity declares `confidenceAtPick: Int?`
     * with a Kotlin default but NO `@ColumnInfo(defaultValue=...)`, so Room's
     * expected schema for this column carries no SQL default. Emitting `DEFAULT
     * NULL` here would make the live column's default differ from Room's expected
     * schema and fail validation on open. A plain nullable ADD COLUMN already
     * back-fills existing rows with NULL, so the behaviour is identical and the
     * schema matches exactly. Column type INTEGER = Room's affinity for Int?.
     */
    private val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE tracked_trades ADD COLUMN confidenceAtPick INTEGER")
        }
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, Constants.DATABASE_NAME)
            // MIGRATION SAFETY: tracked_trades holds the user's real-money trade
            // history — it must NEVER be wiped by an app update. Destructive
            // fallback is allowed ONLY from the pre-release dev versions 1 and 2
            // (no schema record exists for them). From version 3 onward, ANY
            // schema change MUST bump AppDatabase.version AND ship an explicit
            // Migration object registered here via .addMigrations(...). With no
            // matching Migration, Room now throws instead of silently deleting
            // the user's trade journal, open positions, and loss-limit state.
            .fallbackToDestructiveMigrationFrom(1, 2)
            .addMigrations(MIGRATION_3_4)
            .build()

    @Provides fun provideCandleDao(db: AppDatabase): CandleDao = db.candleDao()
    @Provides fun provideRecommendationDao(db: AppDatabase): RecommendationDao = db.recommendationDao()
    @Provides fun provideNewsDao(db: AppDatabase): NewsDao = db.newsDao()
    @Provides fun provideWatchlistDao(db: AppDatabase): WatchlistDao = db.watchlistDao()
    @Provides fun providePortfolioDao(db: AppDatabase): PortfolioDao = db.portfolioDao()
    @Provides fun provideAuditTrailDao(db: AppDatabase): AuditTrailDao = db.auditTrailDao()
    @Provides fun provideBacktestResultDao(db: AppDatabase): BacktestResultDao = db.backtestResultDao()
    @Provides fun provideSectorDataDao(db: AppDatabase): SectorDataDao = db.sectorDataDao()
    @Provides fun provideSearchHistoryDao(db: AppDatabase): SearchHistoryDao = db.searchHistoryDao()
    @Provides fun provideTrackedTradeDao(db: AppDatabase): TrackedTradeDao = db.trackedTradeDao()
}
