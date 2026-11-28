package com.pockettrader.app.trading

import android.content.Context
import com.pockettrader.app.Notifier
import com.pockettrader.app.TradingService
import com.pockettrader.app.data.SecureStore
import com.pockettrader.app.protocol.Candle
import com.pockettrader.app.analytics.analyseTechnicalMarket
import org.json.JSONObject
import org.json.JSONArray
import com.pockettrader.app.protocol.Asset
import com.pockettrader.app.protocol.ConnState
import com.pockettrader.app.protocol.Deal
import com.pockettrader.app.protocol.Direction
import com.pockettrader.app.protocol.PocketClient
import com.pockettrader.app.protocol.PocketEvent
import com.pockettrader.app.protocol.SessionInfo
import com.pockettrader.app.protocol.TradeOutcome
import com.pockettrader.app.protocol.settle
import com.pockettrader.app.signals.Signal
import com.pockettrader.app.signals.SignalEngine
import com.pockettrader.app.signals.Strategies
import com.pockettrader.app.signals.StrategyConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Calendar
import java.util.Locale

enum class AccountType { DEMO, REAL }
enum class TradeSource { MANUAL, AUTO, TT }
enum class TradeStatus { PENDING, OPEN, WIN, LOSS, DRAW, FAILED, UNKNOWN }

data class TradeRecord(
    val requestId: String,
    val dealId: String? = null,
    val asset: String,
    val direction: Direction,
    val amount: Double,
    val expirySec: Int,
    val isDemo: Boolean,
    val source: TradeSource,
    val signalId: Long? = null,
    val placedAtMs: Long,
    val status: TradeStatus = TradeStatus.PENDING,
    val netProfit: Double? = null,
    val note: String? = null,
    val entryPrice: Double? = null,
)

data class LogLine(val timeMs: Long, val text: String)

data class SignalAuditRecord(
    val signal: Signal,
    val account: AccountType,
    val emittedPrice: Double?,
    val entryPrice: Double? = null,
    val expiryAtMs: Long? = null,
    val brokerResult: TradeStatus = TradeStatus.UNKNOWN,
    val netReturn: Double? = null,
    val tradeRequestId: String? = null,
)

/**
 * App-wide trading core: one live session (demo or real), the selected asset's price feed,
 * signal generation, the autotrader with its risk limits, and the trade log.
 */
class TradingEngine(context: Context) {
    private val app = context.applicationContext
    val store = SecureStore(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val client = PocketClient(scope)
    private val nemotron = NemotronClient()
    private val lock = Any()
    @Volatile private var backgroundReconnectEnabled = false

    val connState: StateFlow<ConnState> = client.state
    private val _account = MutableStateFlow(AccountType.DEMO)   // always start on demo
    val account: StateFlow<AccountType> = _account
    private val _balance = MutableStateFlow<Double?>(null); val balance: StateFlow<Double?> = _balance
    private val _assets = MutableStateFlow<List<Asset>>(emptyList()); val assets: StateFlow<List<Asset>> = _assets
    private val _asset = MutableStateFlow(store.lastAsset); val selectedAsset: StateFlow<String> = _asset
    private val _price = MutableStateFlow<Double?>(null); val price: StateFlow<Double?> = _price
    private val _signals = MutableStateFlow<List<Signal>>(emptyList()); val signals: StateFlow<List<Signal>> = _signals
    private val _ttSignals = MutableStateFlow<List<Signal>>(emptyList()); val ttSignals: StateFlow<List<Signal>> = _ttSignals
    private val _pendingSignal = MutableStateFlow<Signal?>(null); val pendingSignal: StateFlow<Signal?> = _pendingSignal
    private val _minConfluence = MutableStateFlow(store.loadMinConfluence()); val minConfluence: StateFlow<Int> = _minConfluence
    private val _trades = MutableStateFlow<List<TradeRecord>>(emptyList()); val trades: StateFlow<List<TradeRecord>> = _trades
    private val _signalJournal = MutableStateFlow(loadSignalJournal())
    val signalJournal: StateFlow<List<SignalAuditRecord>> = _signalJournal
    private val claimedSignalIds = LinkedHashSet<Long>()
    private val _logs = MutableStateFlow<List<LogLine>>(emptyList()); val logs: StateFlow<List<LogLine>> = _logs
    private val _strategy = MutableStateFlow(store.loadStrategy()); val strategy: StateFlow<StrategyConfig> = _strategy
    private val _risk = MutableStateFlow(store.loadRisk()); val risk: StateFlow<RiskConfig> = _risk
    private val riskManagers = mutableMapOf(AccountType.DEMO to RiskManager(_risk.value), AccountType.REAL to RiskManager(_risk.value))
    private val riskManager: RiskManager get() = riskManagers.getValue(_account.value)
    private val _riskState = MutableStateFlow(riskManager.state); val riskState: StateFlow<RiskState> = _riskState
    private val _aiConfigured = MutableStateFlow(store.loadNvidiaApiKey().isNotBlank()); val aiConfigured: StateFlow<Boolean> = _aiConfigured
    private val _aiStatus = MutableStateFlow(if (_aiConfigured.value) "NVIDIA key saved · chat ready" else "API key required for AI chat")
    val aiStatus: StateFlow<String> = _aiStatus
    data class ChatMessage(val role: String, val content: String, val timeMs: Long = System.currentTimeMillis())
    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(listOf(ChatMessage("assistant", "Hi! I’m Nemotron. Ask me about trading concepts, indicators, risk management, or how PocketTrader works.")))
    val chatMessages: StateFlow<List<ChatMessage>> = _chatMessages
    private val _chatBusy = MutableStateFlow(false)
    val chatBusy: StateFlow<Boolean> = _chatBusy
    private val _auto = MutableStateFlow(false); val autoEnabled: StateFlow<Boolean> = _auto
    private val _autoStatus = MutableStateFlow("Off"); val autoStatus: StateFlow<String> = _autoStatus
    private val _sessions = MutableStateFlow(sessionFlags()); val sessions: StateFlow<Pair<Boolean, Boolean>> = _sessions
    // Independent candle engines let TT scan every active broker-listed pair.
    private val marketEngines = mutableMapOf<String, SignalEngine>()
    private val marketTickAtMs = mutableMapOf<String, Long>()
    private val signalEngine = SignalEngine(_strategy.value)
    init { signalEngine.minConfluenceScore = _minConfluence.value }
    private val _candleCount = MutableStateFlow(0 to 0); val candleCount: StateFlow<Pair<Int, Int>> = _candleCount
    private val _lastTickAtMs = MutableStateFlow(0L)
    val lastTickAtMs: StateFlow<Long> = _lastTickAtMs
    private val _priceHistory = MutableStateFlow<List<Pair<Long, Double>>>(emptyList())
    val priceHistory: StateFlow<List<Pair<Long, Double>>> = _priceHistory
    private val _candleHistory = MutableStateFlow<List<Candle>>(emptyList())
    val candleHistory: StateFlow<List<Candle>> = _candleHistory

    init {
        scope.launch { client.events.collect { onEvent(it) } }
        scope.launch {
            var wasConnected = false
            client.state.collect { state ->
                when (state) {
                    ConnState.CONNECTED -> {
                        wasConnected = true
                        TradingService.start(app, _account.value)
                    }
                    ConnState.AUTH_FAILED -> TradingService.stop(app)
                    ConnState.DISCONNECTED -> if (wasConnected && backgroundReconnectEnabled) {
                        delay(3_000)
                        if (client.state.value == ConnState.DISCONNECTED) {
                            log("Background connection lost; reconnecting...")
                            connect()
                        }
                    }
                    else -> Unit
                }
            }
        }
        scope.launch {
            while (isActive) {
                delay(250)
                tickClock()
            }
        }
    }

    private fun sessionFlags() = (store.loadSession(true) != null) to (store.loadSession(false) != null)

    fun log(text: String) {
        _logs.update { (listOf(LogLine(System.currentTimeMillis(), text)) + it).take(300) }
    }

    private fun dayKey(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    // ---------------- sessions / connection ----------------

    fun saveSession(s: SessionInfo) {
        store.saveSession(s)
        _sessions.value = sessionFlags()
        log("Saved ${s.describe()}")
    }

    fun clearSessions() {
        disconnect()
        store.clearSession(true); store.clearSession(false)
        _sessions.value = sessionFlags()
        log("Cleared saved sessions")
    }

    fun connect(): String? {
        val demo = _account.value == AccountType.DEMO
        val s = store.loadSession(demo) ?: return "No ${if (demo) "demo" else "real"} session saved. Sign in on the Account tab first."
        if (s.isDemo != demo) return "Saved session type does not match the selected account"
        backgroundReconnectEnabled = true
        TradingService.start(app, _account.value)
        signalEngine.reset(); _price.value = null; _balance.value = null; _lastTickAtMs.value = 0L
        _priceHistory.value = emptyList(); _candleHistory.value = emptyList()
        synchronized(lock) { marketEngines.clear(); marketTickAtMs.clear() }
        client.watch(_asset.value, _strategy.value.timeframeSec)
        client.connect(s)
        return null
    }

    fun disconnect() {
        backgroundReconnectEnabled = false
        setAuto(false, "Disconnected")
        TradingService.stop(app)
        _lastTickAtMs.value = 0L
        client.disconnect()
    }

    /** Called only by the sticky foreground service after Android recreates the process. */
    fun resumeBackgroundConnection(accountName: String) {
        val wanted = runCatching { AccountType.valueOf(accountName) }.getOrDefault(AccountType.DEMO)
        if (_account.value != wanted) _account.value = wanted
        if (client.state.value == ConnState.DISCONNECTED || client.state.value == ConnState.AUTH_FAILED) {
            connect()?.let { log("Background reconnect blocked: $it") }
        }
    }

    /** Caller (UI) must already have confirmed a switch to REAL. */
    fun switchAccount(type: AccountType): String? {
        if (type == _account.value) return null
        setAuto(false, "Account switched")
        client.disconnect()
        _account.value = type
        _riskState.value = riskManager.state
        log("Account switched to $type")
        return connect()
    }

    // ---------------- market data ----------------

    fun selectAsset(symbol: String) {
        if (symbol == _asset.value) return
        _asset.value = symbol; store.lastAsset = symbol
        _price.value = null
        _lastTickAtMs.value = 0L
        _priceHistory.value = emptyList(); _candleHistory.value = emptyList()
        signalEngine.reset()
        client.watch(symbol, _strategy.value.timeframeSec)
        requestHistory()
    }

    private fun requestHistory() {
        val c = _strategy.value
        client.requestHistory(_asset.value, c.timeframeSec, 300)
        scope.launch {
            delay(250); client.requestHistory(_asset.value, 300, 240)
            delay(250); client.requestHistory(_asset.value, 1800, 240)
            delay(250); client.requestHistory(_asset.value, c.higherTimeframeSec, maxOf(c.htfEmaPeriod + 20, 250))
        }
    }

    fun updateStrategy(c: StrategyConfig) {
        val safe = c.copy(minPayout = c.minPayout.coerceIn(70, 100))
        val old = _strategy.value
        _strategy.value = safe; store.saveStrategy(safe)
        synchronized(lock) {
            signalEngine.reconfigure(safe)
            marketEngines.values.forEach { it.reconfigure(safe); it.minConfluenceScore = _minConfluence.value }
        }
        if (old.timeframeSec != safe.timeframeSec || old.higherTimeframeSec != safe.higherTimeframeSec) {
            client.watch(_asset.value, safe.timeframeSec); requestHistory()
        }
    }

    fun updateRisk(r: RiskConfig) {
        _risk.value = r; store.saveRisk(r)
        riskManagers.values.forEach { it.config = r }; _riskState.value = riskManager.state
    }

    fun resetSession() { riskManager.resetSession(); _riskState.value = riskManager.state; log("Autotrader session counters reset") }

    fun saveNvidiaApiKey(key: String): String? {
        val cleaned = key.trim()
        if (cleaned.length < 20) return "That API key looks too short. Paste the NVIDIA API key from build.nvidia.com."
        store.saveNvidiaApiKey(cleaned)
        _aiConfigured.value = true
        _aiStatus.value = "NVIDIA key saved securely · chat ready"
        log("NVIDIA Nemotron API key saved (value hidden)")
        return null
    }

    fun clearNvidiaApiKey() {
        store.clearNvidiaApiKey()
        _aiConfigured.value = false
        _aiStatus.value = "API key required for AI chat"
    }

    fun sendChatMessage(raw: String) {
        val message = raw.trim()
        if (message.isBlank() || _chatBusy.value) return
        if (!_aiConfigured.value) {
            _chatMessages.update { it + ChatMessage("assistant", "Add your NVIDIA API key in this Chat tab to start chatting.") }
            return
        }
        val prior = _chatMessages.value
        _chatMessages.value = prior + ChatMessage("user", message)
        _chatBusy.value = true
        _aiStatus.value = "Nemotron is replying…"
        scope.launch {
            try {
                val history = prior.filter { it.role == "user" || it.role == "assistant" }.map { ChatTurn(it.role, it.content) }
                val reply = nemotron.chat(store.loadNvidiaApiKey(), history, message)
                _chatMessages.update { it + ChatMessage("assistant", reply) }
                _aiStatus.value = "Chat connected"
            } catch (e: Exception) {
                val detail = (e.message ?: "Unknown NVIDIA API error").take(240)
                _chatMessages.update { it + ChatMessage("assistant", "I couldn’t reach Nemotron: $detail. Check your connection and API key, then try again.") }
                _aiStatus.value = "Chat error · check API key or connection"
            } finally {
                _chatBusy.value = false
            }
        }
    }

    fun clearChat() {
        _chatMessages.value = listOf(ChatMessage("assistant", "Chat cleared. What would you like to explore?"))
    }

    fun exportTradesCsv(): String {
        val rows = _trades.value.asReversed()
        val header = "request_id,deal_id,asset,direction,amount,expiry_seconds,account,source,placed_at,status,net_profit,note"
        fun csv(v: Any?): String = "\"" + (v?.toString() ?: "").replace("\"", "\"\"") + "\""
        return (listOf(header) + rows.map { t ->
            listOf(t.requestId, t.dealId, t.asset, t.direction.label, t.amount, t.expirySec,
                if (t.isDemo) "DEMO" else "REAL", t.source, t.placedAtMs, t.status, t.netProfit, t.note)
                .joinToString(",") { csv(it) }
        }).joinToString("\n")
    }

    /** Backup excludes Pocket Option sessions, passwords and NVIDIA API credentials. */
    fun settingsBackupJson(): String {
        val c = _strategy.value; val r = _risk.value
        val st = JSONObject()
            .put("type", c.type.name).put("timeframeSec", c.timeframeSec).put("higherTimeframeSec", c.higherTimeframeSec)
            .put("expirySec", c.expirySec).put("htfEmaPeriod", c.htfEmaPeriod).put("trendTrigger", c.trendTrigger.name)
            .put("pullbackEma", c.pullbackEma).put("pullbackLookback", c.pullbackLookback)
            .put("macdFast", c.macdFast).put("macdSlow", c.macdSlow).put("macdSignal", c.macdSignal)
            .put("supertrendPeriod", c.supertrendPeriod).put("supertrendMult", c.supertrendMult)
            .put("bbPeriod", c.bbPeriod).put("bbK", c.bbK).put("rsiPeriod", c.rsiPeriod)
            .put("rsiOversold", c.rsiOversold).put("rsiOverbought", c.rsiOverbought)
            .put("donchianPeriod", c.donchianPeriod).put("volumeAvgPeriod", c.volumeAvgPeriod)
            .put("volumeMult", c.volumeMult).put("requireVolume", c.requireVolume)
            .put("sweepLookback", c.sweepLookback).put("adxPeriod", c.adxPeriod)
            .put("adxTrend", c.adxTrend).put("adxRange", c.adxRange).put("emaFast", c.emaFast)
            .put("emaSlow", c.emaSlow).put("minPayout", c.minPayout)
            .put("adaptiveMinRegimeConfidence", c.adaptiveMinRegimeConfidence)
            .put("adaptiveMaxTailProbability", c.adaptiveMaxTailProbability)
        val rk = JSONObject().put("stake", r.stake).put("stakePercent", r.stakePercent).put("minimumStake", r.minimumStake)
            .put("sessionStartHour", r.sessionStartHour).put("sessionEndHour", r.sessionEndHour).put("maxTradesPerSession", r.maxTradesPerSession)
            .put("dailyStopLoss", r.dailyStopLoss).put("dailyTakeProfit", r.dailyTakeProfit)
            .put("maxConsecutiveLosses", r.maxConsecutiveLosses).put("maxOpenTrades", r.maxOpenTrades)
            .put("maxSignalDelaySec", r.maxSignalDelaySec)
        return JSONObject().put("schemaVersion", 1).put("strategy", st).put("risk", rk).toString(2)
    }

    fun restoreSettingsBackup(raw: String): String? = try {
        val root = JSONObject(raw)
        require(root.optInt("schemaVersion") == 1) { "Unsupported backup version" }
        val st = root.getJSONObject("strategy"); val d = _strategy.value
        val strategy = d.copy(
            type = com.pockettrader.app.signals.StrategyType.valueOf(st.optString("type", d.type.name)),
            timeframeSec = st.optInt("timeframeSec", d.timeframeSec).coerceIn(15, 900),
            higherTimeframeSec = st.optInt("higherTimeframeSec", d.higherTimeframeSec).coerceIn(3600, 14400),
            expirySec = st.optInt("expirySec", d.expirySec).coerceIn(5, 3600),
            htfEmaPeriod = st.optInt("htfEmaPeriod", d.htfEmaPeriod).coerceIn(2, 500),
            trendTrigger = com.pockettrader.app.signals.TrendTrigger.valueOf(st.optString("trendTrigger", d.trendTrigger.name)),
            pullbackEma = st.optInt("pullbackEma", d.pullbackEma).coerceIn(2, 200),
            pullbackLookback = st.optInt("pullbackLookback", d.pullbackLookback).coerceIn(1, 100),
            macdFast = st.optInt("macdFast", d.macdFast).coerceIn(2, 100),
            macdSlow = st.optInt("macdSlow", d.macdSlow).coerceIn(3, 200),
            macdSignal = st.optInt("macdSignal", d.macdSignal).coerceIn(2, 100),
            supertrendPeriod = st.optInt("supertrendPeriod", d.supertrendPeriod).coerceIn(2, 100),
            supertrendMult = st.optDouble("supertrendMult", d.supertrendMult).coerceIn(0.5, 10.0),
            bbPeriod = st.optInt("bbPeriod", d.bbPeriod).coerceIn(5, 100),
            bbK = st.optDouble("bbK", d.bbK).coerceIn(0.5, 5.0),
            rsiPeriod = st.optInt("rsiPeriod", d.rsiPeriod).coerceIn(2, 100),
            rsiOversold = st.optDouble("rsiOversold", d.rsiOversold).coerceIn(1.0, 49.0),
            rsiOverbought = st.optDouble("rsiOverbought", d.rsiOverbought).coerceIn(51.0, 99.0),
            donchianPeriod = st.optInt("donchianPeriod", d.donchianPeriod).coerceIn(5, 100),
            volumeAvgPeriod = st.optInt("volumeAvgPeriod", d.volumeAvgPeriod).coerceIn(5, 100),
            volumeMult = st.optDouble("volumeMult", d.volumeMult).coerceIn(0.5, 5.0),
            requireVolume = st.optBoolean("requireVolume", d.requireVolume),
            sweepLookback = st.optInt("sweepLookback", d.sweepLookback).coerceIn(5, 100),
            adxPeriod = st.optInt("adxPeriod", d.adxPeriod).coerceIn(2, 100),
            adxTrend = st.optDouble("adxTrend", d.adxTrend).coerceIn(10.0, 60.0),
            adxRange = st.optDouble("adxRange", d.adxRange).coerceIn(5.0, 40.0),
            emaFast = st.optInt("emaFast", d.emaFast).coerceIn(2, 100),
            emaSlow = st.optInt("emaSlow", d.emaSlow).coerceIn(3, 200),
            minPayout = st.optInt("minPayout", d.minPayout).coerceIn(70, 100),
            adaptiveMinRegimeConfidence = st.optDouble("adaptiveMinRegimeConfidence", d.adaptiveMinRegimeConfidence).coerceIn(0.1, 1.0),
            adaptiveMaxTailProbability = st.optDouble("adaptiveMaxTailProbability", d.adaptiveMaxTailProbability).coerceIn(0.001, 0.5)
        )
        val rk = root.getJSONObject("risk"); val rd = _risk.value
        val risk = rd.copy(
            stake = rk.optDouble("stake", rd.stake).coerceIn(0.1, 100000.0),
            stakePercent = rk.optDouble("stakePercent", rd.stakePercent).coerceIn(0.1, 5.0),
            minimumStake = rk.optDouble("minimumStake", rd.minimumStake).coerceIn(0.1, 100000.0),
            sessionStartHour = rk.optInt("sessionStartHour", rd.sessionStartHour).coerceIn(0, 23),
            sessionEndHour = rk.optInt("sessionEndHour", rd.sessionEndHour).coerceIn(0, 23),
            maxTradesPerSession = rk.optInt("maxTradesPerSession", rd.maxTradesPerSession).coerceIn(1, 1000),
            dailyStopLoss = rk.optDouble("dailyStopLoss", rd.dailyStopLoss).coerceAtLeast(0.0),
            dailyTakeProfit = rk.optDouble("dailyTakeProfit", rd.dailyTakeProfit).coerceAtLeast(0.0),
            maxConsecutiveLosses = rk.optInt("maxConsecutiveLosses", rd.maxConsecutiveLosses).coerceIn(0, 100),
            maxOpenTrades = rk.optInt("maxOpenTrades", rd.maxOpenTrades).coerceIn(1, 10),
            maxSignalDelaySec = rk.optInt("maxSignalDelaySec", rd.maxSignalDelaySec).coerceIn(1, 30)
        )
        updateStrategy(strategy); updateRisk(risk)
        log("Settings backup restored (sessions and API keys were not changed)")
        null
    } catch (e: Exception) { "Could not restore backup: ${e.message ?: "invalid JSON"}" }

    private fun onEvent(e: PocketEvent) {
        when (e) {
            is PocketEvent.Log -> log(e.message)
            is PocketEvent.Balance -> {
                val demoNow = _account.value == AccountType.DEMO
                if (e.isDemo == null || e.isDemo == demoNow) _balance.value = e.amount
            }
            is PocketEvent.Assets -> {
                val listed = e.assets.sortedWith(compareByDescending<Asset> { it.isActive }.thenByDescending { it.payout }.thenBy { it.name })
                _assets.value = listed
                if (connState.value == ConnState.CONNECTED) {
                    // Scan all active Pocket Option pairs, including OTC pairs. The chart
                    // selection remains independent; only TT uses this market-wide feed.
                    val activeAssets = listed.filter { it.isActive }
                    synchronized(lock) {
                        val symbols = activeAssets.map { it.symbol }.toSet()
                        marketEngines.keys.retainAll(symbols)
                        marketTickAtMs.keys.retainAll(symbols)
                        activeAssets.forEach { asset ->
                            if (!marketEngines.containsKey(asset.symbol)) {
                                val scanner = SignalEngine(_strategy.value).also {
                                    it.minConfluenceScore = _minConfluence.value
                                    it.isCryptoAsset = asset.type.contains("crypto", ignoreCase = true)
                                }
                                marketEngines[asset.symbol] = scanner
                                client.subscribeMarket(asset.symbol)
                                client.requestHistory(asset.symbol, _strategy.value.timeframeSec, 240)
                                client.requestHistory(asset.symbol, 300, 240)
                                client.requestHistory(asset.symbol, 1800, 240)
                                client.requestHistory(asset.symbol, _strategy.value.higherTimeframeSec, 240)
                            }
                        }
                    }
                    if (signalEngine.ltf.candles.isEmpty()) requestHistory()
                    log("TT market scan subscribed to " + activeAssets.size + " active pairs (including OTC where listed)")
                }
            }
            is PocketEvent.History -> onHistory(e)
            is PocketEvent.Ticks -> {
                val selected = _asset.value
                val now = System.currentTimeMillis()
                for (t in e.ticks) {
                    synchronized(lock) { marketTickAtMs[t.symbol] = now }
                    if (t.symbol == selected) {
                        _price.value = t.price
                        _lastTickAtMs.value = now
                        _priceHistory.update { (it + (now to t.price)).takeLast(180) }
                        // Keep the selected chart/manual-analysis engine warm. The
                        // market-wide signal engine below generates TT signals.
                        synchronized(lock) { signalEngine.onTick(t.symbol, t.timeSec, t.price, now) }
                    }
                    synchronized(lock) { marketEngines[t.symbol]?.onTick(t.symbol, t.timeSec, t.price, now) }?.let { onSignal(it) }
                }
                synchronized(lock) { _candleHistory.value = signalEngine.ltf.candles }
                _candleCount.value = signalEngine.ltf.candles.size to signalEngine.htf.candles.size
            }
            is PocketEvent.Opened -> onOpened(e.deal)
            is PocketEvent.OpenFailed -> onOpenFailed(e.failure.error, e.failure.asset)
            is PocketEvent.Closed -> e.deals.forEach { onClosed(it) }
        }
    }

    private fun onHistory(e: PocketEvent.History) {
        val snap = e.snapshot
        if (snap.asset.isEmpty()) return
        synchronized(lock) {
            val c = _strategy.value
            val targets = buildList {
                if (snap.asset == _asset.value) add(signalEngine)
                marketEngines[snap.asset]?.let { if (it !== signalEngine) add(it) }
            }
            targets.forEach { target ->
                listOf(target.ltf, target.htf, target.m5, target.m30).distinctBy { it.periodSec }.forEach { agg ->
                    val candles = snap.candles.takeIf { snap.period == agg.periodSec } ?: emptyList()
                    // Only seed when it adds data; live candles are never thrown away for less history.
                    if (candles.size > agg.candles.size) agg.seed(candles, snap.ticks.filter { it.first > (candles.lastOrNull()?.startSec ?: 0L).toDouble() })
                    else if (candles.isEmpty() && agg.candles.isEmpty() && snap.ticks.isNotEmpty()) agg.seed(emptyList(), snap.ticks)
                }
            }
            if (snap.asset == _asset.value) {
                _candleHistory.value = signalEngine.ltf.candles
                _candleCount.value = signalEngine.ltf.candles.size to signalEngine.htf.candles.size
                snap.ticks.lastOrNull()?.let { if (_price.value == null) _price.value = it.second }
            }
            if (c.timeframeSec == snap.period || c.higherTimeframeSec == snap.period || snap.period == 300 || snap.period == 1800)
                log("History ${snap.asset} ${snap.period}s: ${snap.candles.size} candles, ${snap.ticks.size} ticks")
        }
    }

    private fun tickClock() {
        if (connState.value != ConnState.CONNECTED) return
        val now = System.currentTimeMillis()
        val freshSignals = synchronized(lock) {
            marketEngines.entries.mapNotNull { (symbol, scanner) ->
                if (now - (marketTickAtMs[symbol] ?: 0L) <= MAX_TICK_AGE_MS)
                    scanner.onClock(symbol, client.serverNowSec(), now)
                else null
            }
        }
        freshSignals.forEach { onSignal(it) }
        expireStaleTrades()
    }

    /** Manual signal generation independent of Auto Trader's candle-close listener. */
    /** Manual signal view reuses the exact same market-wide SignalEngine output for Demo and Real. */
    fun generateSignalForSelectedAsset(): Signal? {
        if (connState.value != ConnState.CONNECTED) return null
        val now = System.currentTimeMillis()
        if (_lastTickAtMs.value == 0L || now - _lastTickAtMs.value > MAX_TICK_AGE_MS) {
            log("Signal skipped: live market feed is stale or unavailable")
            return null
        }
        val signal = _ttSignals.value.firstOrNull {
            it.asset == _asset.value &&
                it.confluenceScore >= _minConfluence.value &&
                now - it.emittedAtMs in 0..MAX_TT_SIGNAL_AGE_MS
        } ?: run {
            log("No fresh qualifying signal from the shared market analysis engine for ${_asset.value}")
            return null
        }
        _signals.update { (listOf(signal) + it).distinctBy { row -> row.id }.take(100) }
        _pendingSignal.value = signal
        log("Selected shared-engine signal: ${signal.direction.label} ${signal.asset} at ${signal.confluenceScore}%")
        return signal
    }

    fun setMinConfluence(value: Int) {
        val selected = value.takeIf { it in listOf(40, 50, 60, 70, 80, 90) } ?: 80
        _minConfluence.value = selected
        store.saveMinConfluence(selected)
        synchronized(lock) {
            signalEngine.minConfluenceScore = selected
            marketEngines.values.forEach { it.minConfluenceScore = selected }
        }
        log("Minimum signal confluence set to $selected%")
    }

    /** Accept the latest live-feed signal only after an explicit user tap. */
    fun acceptPendingSignal(signal: Signal, amount: Double): String? {
        if (_pendingSignal.value?.id != signal.id) return "This signal is no longer pending. Wait for the latest signal."
        val result = placeSignalTrade(signal, amount)
        if (result == null) _pendingSignal.value = null
        return result
    }

    /** Refreshes the signal's own broker stream, then submits only against a fresh quote. */
    suspend fun placeTtSignalTrade(signal: Signal, amount: Double): String? {
        if (connState.value != ConnState.CONNECTED) return "Connect to Pocket Option first."
        if (client.isDemo != (_account.value == AccountType.DEMO)) return "Session/account mismatch. Reconnect to the selected account."
        if (signal.confluenceScore < _minConfluence.value) return "This TT signal is below your selected threshold and is watch-only."
        if (_signals.value.none { it.id == signal.id }) return "This signal did not pass the trade filters or is no longer eligible."
        if (System.currentTimeMillis() - signal.emittedAtMs > MAX_TT_SIGNAL_AGE_MS)
            return "This \${signal.asset} signal has expired. Wait for a fresh BUY/SELL signal."
        var quoteAt = synchronized(lock) { marketTickAtMs[signal.asset] ?: 0L }
        if (quoteAt <= 0L || System.currentTimeMillis() - quoteAt > MAX_TICK_AGE_MS) {
            client.subscribeMarket(signal.asset)
            for (attempt in 0 until 20) {
                delay(250)
                quoteAt = synchronized(lock) { marketTickAtMs[signal.asset] ?: 0L }
                if (quoteAt > 0L && System.currentTimeMillis() - quoteAt <= MAX_TICK_AGE_MS) break
            }
        }
        if (quoteAt <= 0L || System.currentTimeMillis() - quoteAt > MAX_TICK_AGE_MS)
            return "No fresh Pocket Option quote received for \${signal.asset}. Check the broker connection and try a newer signal."
        val payout = _assets.value.firstOrNull { it.symbol == signal.asset }?.payout
        if (payout == null || payout < _strategy.value.minPayout)
            return "\${signal.asset} payout is unavailable or below your configured minimum."
        riskManager.blockReason(dayKey())?.let { return "Risk limit blocked this trade: $it" }
        return place(signal.asset, amount, signal.direction, signal.expirySec, TradeSource.TT, signal.id)
    }

    /** Uses the existing authenticated order route for the generated signal. */
    fun placeSignalTrade(signal: Signal, amount: Double): String? {
        if (signal.asset != _asset.value) return "Selected pair changed. Generate a new signal for the current pair."
        return place(signal.asset, amount, signal.direction, signal.expirySec, TradeSource.MANUAL, signal.id)
    }

    private fun loadSignalJournal(): List<SignalAuditRecord> = runCatching {
        val array = JSONArray(store.loadSignalJournalJson())
        buildList {
            for (index in 0 until array.length()) {
                val row = array.optJSONObject(index) ?: continue
                val signal = Signal(
                    id = row.optLong("id"),
                    asset = row.optString("asset"),
                    direction = Direction.valueOf(row.optString("direction")),
                    strategy = com.pockettrader.app.signals.StrategyType.valueOf(row.optString("strategy")),
                    reason = row.optString("reason"),
                    entryAtSec = row.optLong("entryAtSec"),
                    expirySec = row.optInt("expirySec"),
                    emittedAtMs = row.optLong("emittedAtMs"),
                    confluenceScore = row.optInt("confluenceScore"),
                    referencePrice = row.optDouble("referencePrice", Double.NaN).takeIf { it.isFinite() }
                )
                add(SignalAuditRecord(
                    signal = signal,
                    account = AccountType.valueOf(row.optString("account", AccountType.DEMO.name)),
                    emittedPrice = row.optDouble("emittedPrice", Double.NaN).takeIf { it.isFinite() },
                    entryPrice = row.optDouble("entryPrice", Double.NaN).takeIf { it.isFinite() },
                    expiryAtMs = row.optLong("expiryAtMs").takeIf { it > 0L },
                    brokerResult = TradeStatus.valueOf(row.optString("brokerResult", TradeStatus.UNKNOWN.name)),
                    netReturn = row.optDouble("netReturn", Double.NaN).takeIf { it.isFinite() },
                    tradeRequestId = row.optString("tradeRequestId").takeIf { it.isNotBlank() }
                ))
            }
        }.take(1000)
    }.getOrDefault(emptyList())

    private fun persistSignalJournal() {
        val array = JSONArray()
        _signalJournal.value.forEach { row ->
            array.put(JSONObject()
                .put("id", row.signal.id).put("asset", row.signal.asset)
                .put("direction", row.signal.direction.name).put("strategy", row.signal.strategy.name)
                .put("reason", row.signal.reason).put("entryAtSec", row.signal.entryAtSec)
                .put("expirySec", row.signal.expirySec).put("emittedAtMs", row.signal.emittedAtMs)
                .put("confluenceScore", row.signal.confluenceScore)
                .put("referencePrice", row.signal.referencePrice ?: JSONObject.NULL)
                .put("account", row.account.name).put("emittedPrice", row.emittedPrice ?: JSONObject.NULL)
                .put("entryPrice", row.entryPrice ?: JSONObject.NULL).put("expiryAtMs", row.expiryAtMs ?: JSONObject.NULL)
                .put("brokerResult", row.brokerResult.name).put("netReturn", row.netReturn ?: JSONObject.NULL)
                .put("tradeRequestId", row.tradeRequestId ?: JSONObject.NULL))
        }
        store.saveSignalJournalJson(array.toString())
    }

    private fun recordSignal(signal: Signal) {
        _signalJournal.update { rows ->
            if (rows.any { it.signal.id == signal.id }) rows
            else (listOf(SignalAuditRecord(signal, _account.value, signal.referencePrice)) + rows).take(1000)
        }
        persistSignalJournal()
    }

    private fun updateSignalAudit(id: Long, change: (SignalAuditRecord) -> SignalAuditRecord) {
        _signalJournal.update { rows -> rows.map { if (it.signal.id == id) change(it) else it } }
        persistSignalJournal()
    }

    fun exportSignalJournalCsv(): String {
        val header = "signal_id,asset,direction,confluence,account,emitted_at,reference_price,entry_price,expiry_seconds,expiry_at,broker_result,net_return,trade_request_id"
        fun csv(value: Any?): String = "\"" + (value?.toString() ?: "").replace("\"", "\"\"") + "\""
        return (listOf(header) + _signalJournal.value.asReversed().map { row ->
            listOf(row.signal.id, row.signal.asset, row.signal.direction.label, row.signal.confluenceScore,
                row.account, row.signal.emittedAtMs, row.emittedPrice, row.entryPrice, row.signal.expirySec,
                row.expiryAtMs, row.brokerResult, row.netReturn, row.tradeRequestId)
                .joinToString(",") { csv(it) }
        }).joinToString("\n")
    }

    // ---------------- signals & autotrader ----------------

    private fun onSignal(sig: Signal) {
        // TT must show market-wide confluence candidates even when the separate
        // execution-quality filter rejects them. Keep discovery and trade approval apart.
        _ttSignals.update { (listOf(sig) + it).distinctBy { item -> item.id }.take(100) }
        recordSignal(sig)
        if (sig.confluenceScore < _minConfluence.value) {
            log("TT watchlist: ${sig.asset} ${sig.direction.label} at ${sig.confluenceScore}% is near the ${_minConfluence.value}% threshold; no order placed")
            return
        }
        // Signal discovery is independent; order eligibility is decided by validateOrder().
        _signals.update { (listOf(sig) + it).take(100) }
        Notifier.signal(app, sig)
        log("Signal ${sig.direction.label} ${sig.asset} (${sig.strategy.label}) at ${sig.confluenceScore}% confluence: ${sig.reason}")
        if (_auto.value) {
            // Auto mode follows every eligible market-wide TT signal, not only the
            // chart-selected pair. Submit immediately from the live tick event path.
            if (sig.asset == _asset.value) _pendingSignal.value = null
            autoExecute(sig)
        } else if (sig.asset == _asset.value) {
            _pendingSignal.value = sig
        }    }

    fun setAuto(on: Boolean, reason: String? = null) {
        if (on && connState.value != ConnState.CONNECTED) {
            _autoStatus.value = "Blocked: connect to the selected account first"
            log("Autotrader start blocked: account is not connected")
            return
        }
        if (on && client.isDemo != (_account.value == AccountType.DEMO)) {
            _autoStatus.value = "Blocked: selected account does not match the connected session"
            log("Autotrader start blocked: session/account mismatch")
            return
        }
        if (on && (_lastTickAtMs.value == 0L || System.currentTimeMillis() - _lastTickAtMs.value > MAX_TICK_AGE_MS)) {
            _autoStatus.value = "Blocked: waiting for fresh live market data"
            log("Autotrader start blocked: live feed is stale")
            return
        }
        if (on && calculatedAutoStake() == null) {
            _autoStatus.value = "Blocked: waiting for balance or percentage stake is below broker minimum"
            log("Autotrader start blocked: percentage stake cannot be calculated safely")
            return
        }
        if (_auto.value == on) return
        _auto.value = on
        _autoStatus.value = if (on) "Running on ${_account.value}" else "Stopped" + (reason?.let { ": $it" } ?: "")
        log("Autotrader ${if (on) "STARTED on ${_account.value}" else "STOPPED"}${reason?.let { " ($it)" } ?: ""}")
        if (on) TradingService.start(app, _account.value)
    }

    fun emergencyStop() = setAuto(false, "STOP pressed")

    private fun autoBlockReason(sig: Signal): String? {
        val cfg = _strategy.value
        val payout = _assets.value.firstOrNull { it.symbol == sig.asset }?.payout
        return when {
            connState.value != ConnState.CONNECTED -> "not connected"
            sessionTimeBlockReason() != null -> sessionTimeBlockReason()!!
            (synchronized(lock) { marketTickAtMs[sig.asset] ?: 0L }).let { it <= 0L || System.currentTimeMillis() - it > MAX_TICK_AGE_MS } -> "live quote for ${sig.asset} is stale"
            riskManager.isLate(sig.entryAtSec, client.serverNowSec()) -> "signal late (> ${_risk.value.maxSignalDelaySec}s)"
            payout == null -> "payout data unavailable for ${sig.asset}"
            payout < cfg.minPayout -> "payout $payout% < ${cfg.minPayout}%"
            calculatedAutoStake() == null -> autoStakeBlockMessage()
            else -> {
                riskManager.config = _risk.value.copy(stake = calculatedAutoStake()!!)
                riskManager.blockReason(dayKey())
            }
        }
    }

    /** Automatic orders follow the selected technical strategy and live broker feed; Nemotron is chat-only. */
    private fun autoExecute(sig: Signal) {
        val block = autoBlockReason(sig)
        if (block != null) {
            _autoStatus.value = "Skipped signal #${sig.id}: $block"
            log("Autotrader skipped signal #${sig.id}: $block")
            if (riskLimitHit(block)) setAuto(false, block)
            return
        }
        val stake = calculatedAutoStake() ?: run {
            _autoStatus.value = autoStakeBlockMessage()
            log("Autotrader skipped signal #${sig.id}: ${autoStakeBlockMessage()}")
            return
        }
        val err = place(sig.asset, stake, sig.direction, sig.expirySec, TradeSource.AUTO, sig.id)
        if (err != null) {
            _autoStatus.value = "Order failed: $err"
            log("Autotrader order error: $err")
        } else {
            _autoStatus.value = "Placed ${sig.direction.label} ${sig.asset} from live-feed signal"
        }
    }

    private fun sessionTimeBlockReason(): String? {
        val start = _risk.value.sessionStartHour.coerceIn(0, 23)
        val end = _risk.value.sessionEndHour.coerceIn(0, 23)
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val allowed = when {
            start == end -> false
            start < end -> hour >= start && hour < end
            else -> hour >= start || hour < end
        }
        return if (allowed) null else "outside configured local session ${start}:00–${end}:00"
    }

    private fun calculatedAutoStake(): Double? {
        val currentBalance = _balance.value ?: return null
        if (!currentBalance.isFinite() || currentBalance <= 0.0) return null
        val amount = currentBalance * (_risk.value.stakePercent / 100.0)
        return amount.takeIf { it.isFinite() && it >= _risk.value.minimumStake }
    }

    private fun autoStakeBlockMessage(): String {
        val currentBalance = _balance.value
            ?: return "Waiting for the broker balance before calculating the percentage stake."
        val amount = currentBalance * (_risk.value.stakePercent / 100.0)
        return if (amount < _risk.value.minimumStake)
            "Calculated ${_risk.value.stakePercent}% stake (${"%.2f".format(amount)}) is below the broker minimum ${"%.2f".format(_risk.value.minimumStake)}; no trade placed."
        else "Could not calculate a valid percentage stake; no trade placed."
    }

    private companion object { const val MAX_TICK_AGE_MS = 15_000L; const val MAX_TT_SIGNAL_AGE_MS = 15_000L }

    private fun riskLimitHit(reason: String) =
        reason.startsWith("Max trades") || reason.startsWith("Daily") || reason.startsWith("Max consecutive") || reason.startsWith("Next stake")

    // ---------------- orders ----------------

    fun placeManual(direction: Direction, amount: Double, expirySec: Int): String? =
        place(_asset.value, amount, direction, expirySec, TradeSource.MANUAL, null)

    private fun validateOrder(asset: String, amount: Double, direction: Direction, expirySec: Int, signalId: Long?): String? {
        if (connState.value != ConnState.CONNECTED) return "Not connected"
        val demo = _account.value == AccountType.DEMO
        if (client.isDemo != demo) return "Session/account mismatch. Reconnect to the selected account."
        val listed = _assets.value.firstOrNull { it.symbol == asset }
            ?: return "Asset $asset is not in the current broker asset list."
        if (!listed.isActive) return "Asset $asset is not currently active at the broker."
        if (!amount.isFinite() || amount < _risk.value.minimumStake)
            return "Amount is invalid or below the configured broker minimum."
        val currentBalance = _balance.value
        if (currentBalance == null || !currentBalance.isFinite() || amount > currentBalance)
            return "A valid broker balance is required and the stake cannot exceed it."
        if (expirySec !in 5..3600) return "Expiry must be between 5 and 3600 seconds."
        if (signalId != null) {
            val signal = _signals.value.firstOrNull { it.id == signalId }
                ?: return "Signal is no longer eligible for execution."
            if (signal.asset != asset || signal.direction != direction || signal.expirySec != expirySec)
                return "Order asset, direction or expiry does not match the originating signal."
            val age = System.currentTimeMillis() - signal.emittedAtMs
            if (age !in 0..MAX_TT_SIGNAL_AGE_MS) return "Signal expired before execution. Wait for a fresh signal."
            if (signal.confluenceScore < _minConfluence.value) return "Signal is below the current confluence threshold."
        }
        if (listed.payout <= 0 || listed.payout < _strategy.value.minPayout)
            return "Payout is unavailable or below the configured ${_strategy.value.minPayout}% minimum."
        val now = System.currentTimeMillis()
        val tickAt = synchronized(lock) { marketTickAtMs[asset] ?: if (asset == _asset.value) _lastTickAtMs.value else 0L }
        if (tickAt <= 0L || now - tickAt > MAX_TICK_AGE_MS)
            return "Live quote for $asset is stale. Wait for a fresh Pocket Option price before ordering."
        sessionTimeBlockReason()?.let { return it }
        riskManager.config = _risk.value.copy(stake = amount)
        riskManager.blockReason(dayKey())?.let { return "Risk limit blocked this order: $it" }
        return null
    }

    private fun place(asset: String, amount: Double, direction: Direction, expirySec: Int, source: TradeSource, signalId: Long?): String? {
        // Validate, deduplicate and reserve the risk slot as one critical section. Without
        // this reservation, two simultaneous signals can both pass maxOpenTrades=1.
        val reservationError = synchronized(lock) {
            val invalid = validateOrder(asset, amount, direction, expirySec, signalId)
            if (invalid != null) invalid
            else if (signalId != null && claimedSignalIds.contains(signalId))
                "This signal has already been submitted. Duplicate order blocked."
            else {
                val blocked = riskManager.reserveOpen(dayKey())
                if (blocked != null) blocked
                else {
                    if (signalId != null) {
                        claimedSignalIds.add(signalId)
                        while (claimedSignalIds.size > 2000) claimedSignalIds.remove(claimedSignalIds.first())
                    }
                    null
                }
            }
        }
        if (reservationError != null) return reservationError
        _riskState.value = riskManager.state
        val demo = client.isDemo
        val sentAt = System.currentTimeMillis()
        val id = client.openOrder(asset, amount, direction, expirySec) ?: run {
            riskManager.onRejected()
            _riskState.value = riskManager.state
            return "Order submission failed. Risk reservation released; check the connection before retrying."
        }
        val rec = TradeRecord(id, null, asset, direction, amount, expirySec, demo, source, signalId, sentAt)
        _trades.update { (listOf(rec) + it).take(500) }
        if (signalId != null) updateSignalAudit(signalId) {
            it.copy(expiryAtMs = sentAt + expirySec * 1000L, brokerResult = TradeStatus.PENDING, tradeRequestId = id)
        }
        log("Order sent: ${direction.label} $asset ${"%.2f".format(amount)} ${expirySec}s ${if (demo) "DEMO" else "REAL"} [$source]")
        return null
    }

    private fun updateTrade(match: (TradeRecord) -> Boolean, change: (TradeRecord) -> TradeRecord): TradeRecord? {
        var changed: TradeRecord? = null
        _trades.update { list ->
            val i = list.indexOfFirst(match)
            if (i < 0) list else list.toMutableList().also { changed = change(it[i]); it[i] = changed!! }
        }
        return changed
    }

    private fun onOpened(d: Deal) {
        val r = updateTrade({ it.status == TradeStatus.PENDING && (it.requestId == d.requestId || (d.requestId == null && it.asset == d.asset)) }) {
            it.copy(dealId = d.id, status = TradeStatus.OPEN, entryPrice = d.openPrice)
        } ?: updateTrade({ it.status == TradeStatus.PENDING && it.asset == d.asset && Math.abs(it.amount - d.amount) < 1e-6 }) {
            it.copy(dealId = d.id, status = TradeStatus.OPEN, entryPrice = d.openPrice)
        }
        if (r != null) {
            if (r.signalId != null) updateSignalAudit(r.signalId) {
                it.copy(entryPrice = d.openPrice ?: it.emittedPrice, brokerResult = TradeStatus.OPEN)
            }
            log("Trade opened ${r.asset} (deal ${d.id.take(8)})")
        }
    }

    private fun onOpenFailed(error: String, asset: String) {
        val r = updateTrade({ it.status == TradeStatus.PENDING && (asset.isEmpty() || it.asset == asset) }) {
            it.copy(status = TradeStatus.FAILED, note = error)
        }
        log("Order rejected: $error")
        if (r != null) {
            val manager = riskManagers.getValue(if (r.isDemo) AccountType.DEMO else AccountType.REAL)
            manager.onRejected()
            if (r.isDemo == (_account.value == AccountType.DEMO)) _riskState.value = manager.state
        }
    }

    private fun onClosed(d: Deal) {
        val (outcome, net) = settle(d)
        val status = when (outcome) { TradeOutcome.WIN -> TradeStatus.WIN; TradeOutcome.LOSS -> TradeStatus.LOSS; TradeOutcome.DRAW -> TradeStatus.DRAW }
        val r = updateTrade({ it.dealId == d.id && (it.status == TradeStatus.OPEN || it.status == TradeStatus.PENDING) }) {
            it.copy(status = status, netProfit = net)
        } ?: return
        if (r.signalId != null) updateSignalAudit(r.signalId) {
            it.copy(entryPrice = d.openPrice ?: it.entryPrice, brokerResult = status, netReturn = net)
        }
        log("Result ${r.asset}: $status ${"%+.2f".format(net)}")
        Notifier.result(app, r)
        client.refreshBalance()
        val isActiveAccount = r.isDemo == (_account.value == AccountType.DEMO)
            val manager = riskManagers.getValue(if (r.isDemo) AccountType.DEMO else AccountType.REAL)
            manager.onClosed(net, dayKey())
            if (isActiveAccount) {
                _riskState.value = manager.state
                if (_auto.value) manager.blockReason(dayKey())?.takeIf { riskLimitHit(it) }?.let { setAuto(false, it) }
        }
    }

    /** No result after expiry + 90s: mark UNKNOWN and count it as a full loss for the risk limits. */
    private fun expireStaleTrades() {
        val now = System.currentTimeMillis()
        val stale = _trades.value.filter { (it.status == TradeStatus.OPEN || it.status == TradeStatus.PENDING) && now > it.placedAtMs + (it.expirySec + 90) * 1000L }
        for (t in stale) {
            updateTrade({ it.requestId == t.requestId }) { it.copy(status = TradeStatus.UNKNOWN, note = "No result received; check Pocket Option history") }
            log("No result for ${t.asset} trade; marked UNKNOWN")
            if (t.signalId != null) updateSignalAudit(t.signalId) { it.copy(brokerResult = TradeStatus.UNKNOWN, netReturn = -t.amount) }
            val manager = riskManagers.getValue(if (t.isDemo) AccountType.DEMO else AccountType.REAL)
                manager.onClosed(-t.amount, dayKey())
            if (t.isDemo == (_account.value == AccountType.DEMO)) _riskState.value = manager.state
        }
    }
}
