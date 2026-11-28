package com.pockettrader.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

@Entity(
    tableName = "signals",
    indices = [Index("asset"), Index("createdAt"), Index("confluenceScore"), Index("accountMode")]
)
data class SignalEntity(
    @PrimaryKey val signalId: Long,
    val asset: String,
    val direction: String,
    val strategy: String,
    val reason: String,
    val entryAtSec: Long,
    val expirySeconds: Int,
    val confluenceScore: Int,
    val accountMode: String,
    val status: String,
    val createdAt: Long,
    val expiresAt: Long? = null,
    val rejectionReason: String? = null
)

@Entity(
    tableName = "signal_components",
    foreignKeys = [ForeignKey(
        entity = SignalEntity::class,
        parentColumns = ["signalId"],
        childColumns = ["signalId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("signalId")]
)
data class SignalComponentEntity(
    @PrimaryKey(autoGenerate = true) val componentId: Long = 0,
    val signalId: Long,
    val component: String,
    val direction: String,
    val rawScore: Double,
    val maximumScore: Double,
    val explanation: String?
)

@Entity(
    tableName = "validation_logs",
    indices = [Index("signalId"), Index("createdAt"), Index("asset"), Index("validationStatus")]
)
data class ValidationLogEntity(
    @PrimaryKey val validationId: String,
    val signalId: Long?,
    val asset: String,
    val accountMode: String,
    val quotePrice: Double?,
    val quoteTimestamp: Long?,
    val payout: Double?,
    val expirySeconds: Int?,
    val riskAmountMinor: Long?,
    val validationStatus: String,
    val rejectionReason: String?,
    val createdAt: Long
)

@Entity(
    tableName = "executed_orders",
    indices = [Index("signalId"), Index("submittedAt"), Index("accountMode"), Index("executionStatus")]
)
data class ExecutedOrderEntity(
    @PrimaryKey val orderId: String,
    val signalId: Long?,
    val brokerOrderId: String?,
    val asset: String,
    val direction: String,
    val accountMode: String,
    val stakeMinor: Long,
    val entryPrice: Double?,
    val exitPrice: Double?,
    val payout: Double?,
    val expirySeconds: Int,
    val executionStatus: String,
    val outcome: String?,
    val profitLossMinor: Long?,
    val submittedAt: Long,
    val closedAt: Long?
)

@Entity(
    tableName = "performance_analytics",
    indices = [Index(value = ["dateKey", "strategyVersion", "accountMode", "asset"], unique = true)]
)
data class PerformanceAnalyticsEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dateKey: String,
    val strategyVersion: String,
    val accountMode: String,
    val asset: String,
    val totalSignals: Int,
    val executedTrades: Int,
    val winningTrades: Int,
    val losingTrades: Int,
    val totalProfitLossMinor: Long,
    val averageConfluence: Double?
)

@Entity(tableName = "risk_state")
data class RiskStateEntity(
    @PrimaryKey val accountMode: String,
    val dayKey: String,
    val state: String,
    val dailyLossMinor: Long,
    val dailyProfitMinor: Long,
    val consecutiveLosses: Int,
    val tradesToday: Int,
    val openTrades: Int,
    val maximumDailyLossMinor: Long,
    val maximumDailyTrades: Int,
    val updatedAt: Long
)

@Dao
interface PocketTraderDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSignal(signal: SignalEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSignalComponents(components: List<SignalComponentEntity>)

    @Query("SELECT * FROM signals ORDER BY confluenceScore DESC, createdAt DESC LIMIT :limit")
    suspend fun rankedSignals(limit: Int = 100): List<SignalEntity>

    @Query("SELECT * FROM signals WHERE asset = :asset ORDER BY createdAt DESC LIMIT :limit")
    suspend fun signalsForAsset(asset: String, limit: Int = 100): List<SignalEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertValidationLog(log: ValidationLogEntity)

    @Query("SELECT * FROM validation_logs ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recentValidationLogs(limit: Int = 200): List<ValidationLogEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOrder(order: ExecutedOrderEntity)

    @Query("UPDATE executed_orders SET brokerOrderId = :brokerId, executionStatus = :status, entryPrice = :entryPrice WHERE orderId = :orderId")
    suspend fun markOrderOpened(orderId: String, brokerId: String?, status: String, entryPrice: Double?)

    @Query("UPDATE executed_orders SET executionStatus = :status, outcome = :outcome, exitPrice = :exitPrice, profitLossMinor = :profitLossMinor, closedAt = :closedAt WHERE orderId = :orderId")
    suspend fun settleOrder(orderId: String, status: String, outcome: String?, exitPrice: Double?, profitLossMinor: Long?, closedAt: Long)

    @Query("SELECT * FROM executed_orders ORDER BY submittedAt DESC LIMIT :limit")
    suspend fun recentOrders(limit: Int = 500): List<ExecutedOrderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPerformance(row: PerformanceAnalyticsEntity)

    @Query("SELECT * FROM performance_analytics WHERE dateKey = :dateKey AND strategyVersion = :strategyVersion AND accountMode = :accountMode AND asset = :asset LIMIT 1")
    suspend fun performance(dateKey: String, strategyVersion: String, accountMode: String, asset: String): PerformanceAnalyticsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRiskState(state: RiskStateEntity)

    @Query("SELECT * FROM risk_state WHERE accountMode = :accountMode LIMIT 1")
    suspend fun riskState(accountMode: String): RiskStateEntity?
}

@Database(
    entities = [
        SignalEntity::class,
        SignalComponentEntity::class,
        ValidationLogEntity::class,
        ExecutedOrderEntity::class,
        PerformanceAnalyticsEntity::class,
        RiskStateEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class PocketTraderDatabase : RoomDatabase() {
    abstract fun dao(): PocketTraderDao

    companion object {
        @Volatile private var INSTANCE: PocketTraderDatabase? = null

        fun get(context: Context): PocketTraderDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    PocketTraderDatabase::class.java,
                    "pockettrader.db"
                ).build().also { INSTANCE = it }
            }
    }
}
