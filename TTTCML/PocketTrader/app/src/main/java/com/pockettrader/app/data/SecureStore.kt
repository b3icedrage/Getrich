package com.pockettrader.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.pockettrader.app.protocol.SessionInfo
import com.pockettrader.app.protocol.SsidParser
import com.pockettrader.app.signals.StrategyConfig
import com.pockettrader.app.signals.StrategyType
import com.pockettrader.app.signals.TrendTrigger
import com.pockettrader.app.trading.RiskConfig

/** Sessions are stored only in EncryptedSharedPreferences (AES-256, key in Android Keystore). */
class SecureStore(context: Context) {
    private val app = context.applicationContext
    private val secure: SharedPreferences = openEncrypted()
    private val plain: SharedPreferences = app.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private fun openEncrypted(): SharedPreferences {
        fun create(): SharedPreferences {
            val key = MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            return EncryptedSharedPreferences.create(app, "secure_sessions", key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
        }
        return try { create() } catch (e: Exception) {
            // Keystore key lost (e.g. backup restore): wipe and start fresh.
            app.deleteSharedPreferences("secure_sessions")
            create()
        }
    }

    /** NVIDIA API credentials are encrypted with Android Keystore-backed preferences. */
    fun saveNvidiaApiKey(key: String) {
        secure.edit().putString("nvidia_api_key", key.trim()).apply()
    }

    fun loadNvidiaApiKey(): String = secure.getString("nvidia_api_key", "") ?: ""

    fun clearNvidiaApiKey() {
        secure.edit().remove("nvidia_api_key").apply()
    }

    fun saveSession(s: SessionInfo) {
        val p = if (s.isDemo) "demo" else "real"
        secure.edit()
            .putString("${p}_frame", s.authFrame())
            .putString("${p}_url", s.wsUrl)
            .putString("${p}_ua", s.userAgent)
            .apply()
    }

    fun loadSession(demo: Boolean): SessionInfo? {
        val p = if (demo) "demo" else "real"
        val frame = secure.getString("${p}_frame", null) ?: return null
        return try {
            SsidParser.parse(frame, secure.getString("${p}_url", null), secure.getString("${p}_ua", null))
        } catch (e: Exception) { null }
    }

    fun clearSession(demo: Boolean) {
        val p = if (demo) "demo" else "real"
        secure.edit().remove("${p}_frame").remove("${p}_url").remove("${p}_ua").apply()
    }

    fun saveStrategy(c: StrategyConfig) {
        plain.edit()
            .putString("st_type", c.type.name).putInt("st_tf", c.timeframeSec).putInt("st_htf", c.higherTimeframeSec)
            .putInt("st_exp", c.expirySec).putInt("st_htf_ema", c.htfEmaPeriod).putString("st_trig", c.trendTrigger.name)
            .putFloat("st_rsi_lo", c.rsiOversold.toFloat()).putFloat("st_rsi_hi", c.rsiOverbought.toFloat())
            .putFloat("st_adx_t", c.adxTrend.toFloat()).putFloat("st_adx_r", c.adxRange.toFloat())
            .putFloat("st_vol", c.volumeMult.toFloat()).putBoolean("st_req_vol", c.requireVolume)
            .putInt("st_min_pay", c.minPayout)
            .putInt("st_pull_ema", c.pullbackEma).putInt("st_pull_lb", c.pullbackLookback)
            .putInt("st_macd_fast", c.macdFast).putInt("st_macd_slow", c.macdSlow).putInt("st_macd_sig", c.macdSignal)
            .putInt("st_st_period", c.supertrendPeriod).putFloat("st_st_mult", c.supertrendMult.toFloat())
            .putInt("st_bb_period", c.bbPeriod).putFloat("st_bb_k", c.bbK.toFloat())
            .putInt("st_rsi_period", c.rsiPeriod).putInt("st_donchian", c.donchianPeriod)
            .putInt("st_vol_avg", c.volumeAvgPeriod).putInt("st_sweep", c.sweepLookback)
            .putInt("st_adx_period", c.adxPeriod).putInt("st_ema_fast", c.emaFast).putInt("st_ema_slow", c.emaSlow)
            .putFloat("st_adapt_conf", c.adaptiveMinRegimeConfidence.toFloat())
            .putFloat("st_adapt_tail", c.adaptiveMaxTailProbability.toFloat()).apply()
    }

    fun loadStrategy(): StrategyConfig {
        val d = StrategyConfig()
        return d.copy(
            type = runCatching { StrategyType.valueOf(plain.getString("st_type", d.type.name)!!) }.getOrDefault(d.type),
            timeframeSec = plain.getInt("st_tf", d.timeframeSec),
            higherTimeframeSec = plain.getInt("st_htf", d.higherTimeframeSec),
            expirySec = plain.getInt("st_exp", d.expirySec),
            htfEmaPeriod = plain.getInt("st_htf_ema", d.htfEmaPeriod),
            trendTrigger = runCatching { TrendTrigger.valueOf(plain.getString("st_trig", d.trendTrigger.name)!!) }.getOrDefault(d.trendTrigger),
            rsiOversold = plain.getFloat("st_rsi_lo", d.rsiOversold.toFloat()).toDouble(),
            rsiOverbought = plain.getFloat("st_rsi_hi", d.rsiOverbought.toFloat()).toDouble(),
            adxTrend = plain.getFloat("st_adx_t", d.adxTrend.toFloat()).toDouble(),
            adxRange = plain.getFloat("st_adx_r", d.adxRange.toFloat()).toDouble(),
            volumeMult = plain.getFloat("st_vol", d.volumeMult.toFloat()).toDouble(),
            requireVolume = plain.getBoolean("st_req_vol", d.requireVolume),
            minPayout = plain.getInt("st_min_pay", d.minPayout),
            pullbackEma = plain.getInt("st_pull_ema", d.pullbackEma),
            pullbackLookback = plain.getInt("st_pull_lb", d.pullbackLookback),
            macdFast = plain.getInt("st_macd_fast", d.macdFast),
            macdSlow = plain.getInt("st_macd_slow", d.macdSlow),
            macdSignal = plain.getInt("st_macd_sig", d.macdSignal),
            supertrendPeriod = plain.getInt("st_st_period", d.supertrendPeriod),
            supertrendMult = plain.getFloat("st_st_mult", d.supertrendMult.toFloat()).toDouble(),
            bbPeriod = plain.getInt("st_bb_period", d.bbPeriod),
            bbK = plain.getFloat("st_bb_k", d.bbK.toFloat()).toDouble(),
            rsiPeriod = plain.getInt("st_rsi_period", d.rsiPeriod),
            donchianPeriod = plain.getInt("st_donchian", d.donchianPeriod),
            volumeAvgPeriod = plain.getInt("st_vol_avg", d.volumeAvgPeriod),
            sweepLookback = plain.getInt("st_sweep", d.sweepLookback),
            adxPeriod = plain.getInt("st_adx_period", d.adxPeriod),
            emaFast = plain.getInt("st_ema_fast", d.emaFast),
            emaSlow = plain.getInt("st_ema_slow", d.emaSlow),
            adaptiveMinRegimeConfidence = plain.getFloat("st_adapt_conf", d.adaptiveMinRegimeConfidence.toFloat()).toDouble(),
            adaptiveMaxTailProbability = plain.getFloat("st_adapt_tail", d.adaptiveMaxTailProbability.toFloat()).toDouble(),
        )
    }

    fun saveRisk(r: RiskConfig) {
        plain.edit().putFloat("rk_stake", r.stake.toFloat()).putFloat("rk_pct", r.stakePercent.toFloat()).putFloat("rk_min", r.minimumStake.toFloat()).putInt("rk_max", r.maxTradesPerSession)
            .putFloat("rk_sl", r.dailyStopLoss.toFloat()).putFloat("rk_tp", r.dailyTakeProfit.toFloat())
            .putInt("rk_cl", r.maxConsecutiveLosses).putInt("rk_open", r.maxOpenTrades).putInt("rk_delay", r.maxSignalDelaySec).apply()
    }

    fun loadRisk(): RiskConfig {
        val d = RiskConfig()
        return d.copy(
            stake = plain.getFloat("rk_stake", d.stake.toFloat()).toDouble(),
            stakePercent = plain.getFloat("rk_pct", d.stakePercent.toFloat()).toDouble().coerceIn(0.1, 5.0),
            minimumStake = plain.getFloat("rk_min", d.minimumStake.toFloat()).toDouble().coerceAtLeast(0.1),
            maxTradesPerSession = plain.getInt("rk_max", d.maxTradesPerSession),
            dailyStopLoss = plain.getFloat("rk_sl", d.dailyStopLoss.toFloat()).toDouble(),
            dailyTakeProfit = plain.getFloat("rk_tp", d.dailyTakeProfit.toFloat()).toDouble(),
            maxConsecutiveLosses = plain.getInt("rk_cl", d.maxConsecutiveLosses),
            maxOpenTrades = plain.getInt("rk_open", d.maxOpenTrades),
            maxSignalDelaySec = plain.getInt("rk_delay", d.maxSignalDelaySec),
        )
    }

    fun saveMinConfluence(value: Int) {
        plain.edit().putInt("min_confluence", value.coerceIn(40, 90)).apply()
    }

    fun loadMinConfluence(): Int = plain.getInt("min_confluence", 80).let {
        if (it in listOf(40, 50, 60, 70, 80, 90)) it else 80
    }

    var lastAsset: String
        get() = plain.getString("asset", "EURUSD_otc")!!
        set(v) { plain.edit().putString("asset", v).apply() }
}
