package com.pockettrader.app.trading

import android.content.Context
import com.pockettrader.app.Notifier
import com.pockettrader.app.TradingService
import com.pockettrader.app.data.SecureStore
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
import java.util.Locale

enum class AccountType { DEMO, REAL }
enum class TradeSource { MANUAL, AUTO }
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
)

data class LogLine(val timeMs: Long, val text: String)

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

    val connState: StateFlow<ConnState> = client.state
    private val _account = MutableStateFlow(AccountType.DEMO)   // always start on demo
    val account: StateFlow<AccountType> = _account
    private val _balance = MutableStateFlow<Double?>(null); val balance: StateFlow<Double?> = _balance
    private val _assets = MutableStateFlow<List<Asset>>(emptyList()); val assets: StateFlow<List<Asset>> = _assets
    private val _asset = MutableStateFlow(store.lastAsset); val selectedAsset: StateFlow<String> = _asset
    private val _price = MutableStateFlow<Double?>(null); val price: StateFlow<Double?> = _price
    private val _signals = MutableStateFlow<List<Signal>>(emptyList()); val signals: StateFlow<List<Signal>> = _signals
    private val _trades = MutableStateFlow<List<TradeRecord>>(emptyList()); val trades: StateFlow<List<TradeRecord>> = _trades
    private val _logs = MutableStateFlow<List<LogLine>>(emptyList()); val logs: StateFlow<List<LogLine>> = _logs
    private val _strategy = MutableStateFlow(store.loadStrategy()); val strategy: StateFlow<StrategyConfig> = _strategy
    private val _risk = MutableStateFlow(store.loadRisk()); val risk: StateFlow<RiskConfig> = _risk
    private val riskManager = RiskManager(_risk.value)
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
    private val signalEngine = SignalEngine(_strategy.value)
    private val _candleCount = MutableStateFlow(0 to 0); val candleCount: StateFlow<Pair<Int, Int>> = _candleCount
    private val _lastTickAtMs = MutableStateFlow(0L)

    init {
        scope.launch { client.events.collect { onEvent(it) } }
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
        signalEngine.reset(); _price.value = null; _balance.value = null; _lastTickAtMs.value = 0L
        client.watch(_asset.value, _strategy.value.timeframeSec)
        client.connect(s)
        return null
    }

    fun disconnect() {
        setAuto(false, "Disconnected")
        _lastTickAtMs.value = 0L
        client.disconnect()
    }

    /** Caller (UI) must already have confirmed a switch to REAL. */
    fun switchAccount(type: AccountType): String? {
        if (type == _account.value) return null
        setAuto(false, "Account switched")
        client.disconnect()
        _account.value = type
        log("Account switched to $type")
        return connect()
    }

    // ---------------- market data ----------------

    fun selectAsset(symbol: String) {
        if (symbol == _asset.value) return
        _asset.value = symbol; store.lastAsset = symbol
        _price.value = null
        _lastTickAtMs.value = 0L
        signalEngine.reset()
        client.watch(symbol, _strategy.value.timeframeSec)
        requestHistory()
    }

    private fun requestHistory() {
        val c = _strategy.value
        client.requestHistory(_asset.value, c.timeframeSec, 300)
        scope.launch { delay(400); client.requestHistory(_asset.value, c.higherTimeframeSec, maxOf(c.htfEmaPeriod + 20, 250)) }
    }

    fun updateStrategy(c: StrategyConfig) {
        val old = _strategy.value
        _strategy.value = c; store.saveStrategy(c)
        synchronized(lock) { signalEngine.reconfigure(c) }
        if (old.timeframeSec != c.timeframeSec || old.higherTimeframeSec != c.higherTimeframeSec) {
            client.watch(_asset.value, c.timeframeSec); requestHistory()
        }
    }

    fun updateRisk(r: RiskConfig) {
        _risk.value = r; store.saveRisk(r)
        riskManager.config = r; _riskState.value = riskManager.state
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

    private fun onEvent(e: PocketEvent) {
        when (e) {
            is PocketEvent.Log -> log(e.message)
            is PocketEvent.Balance -> {
                val demoNow = _account.value == AccountType.DEMO
                if (e.isDemo == null || e.isDemo == demoNow) _balance.value = e.amount
            }
            is PocketEvent.Assets -> {
                _assets.value = e.assets.sortedWith(compareByDescending<Asset> { it.isActive }.thenByDescending { it.payout }.thenBy { it.name })
                if (connState.value == ConnState.CONNECTED && signalEngine.ltf.candles.isEmpty()) requestHistory()
            }
            is PocketEvent.History -> onHistory(e)
            is PocketEvent.Ticks -> {
                val sym = _asset.value
                val now = System.currentTimeMillis()
                for (t in e.ticks) {
                    if (t.symbol != sym) continue
                    _price.value = t.price
                    _lastTickAtMs.value = now
                    val sig = synchronized(lock) { signalEngine.onTick(sym, t.timeSec, t.price, now) }
                    sig?.let { onSignal(it) }
                }
                _candleCount.value = signalEngine.ltf.candles.size to signalEngine.htf.candles.size
            }
            is PocketEvent.Opened -> onOpened(e.deal)
            is PocketEvent.OpenFailed -> onOpenFailed(e.failure.error, e.failure.asset)
            is PocketEvent.Closed -> e.deals.forEach { onClosed(it) }
        }
    }

    private fun onHistory(e: PocketEvent.History) {
        val snap = e.snapshot
        if (snap.asset.isNotEmpty() && snap.asset != _asset.value) return
        synchronized(lock) {
            val c = _strategy.value
            listOf(signalEngine.ltf, signalEngine.htf).forEach { agg ->
                val candles = snap.candles.takeIf { snap.period == agg.periodSec } ?: emptyList()
                // Only seed when it adds data; live candles are never thrown away for less history.
                if (candles.size > agg.candles.size) agg.seed(candles, snap.ticks.filter { it.first > (candles.lastOrNull()?.startSec ?: 0L).toDouble() })
                else if (candles.isEmpty() && agg.candles.isEmpty() && snap.ticks.isNotEmpty()) agg.seed(emptyList(), snap.ticks)
            }
            _candleCount.value = signalEngine.ltf.candles.size to signalEngine.htf.candles.size
            if (c.timeframeSec == snap.period || c.higherTimeframeSec == snap.period)
                log("History ${snap.asset} ${snap.period}s: ${snap.candles.size} candles, ${snap.ticks.size} ticks")
        }
        snap.ticks.lastOrNull()?.let { if (_price.value == null) _price.value = it.second }
    }

    private fun tickClock() {
        if (connState.value != ConnState.CONNECTED) return
        val sym = _asset.value
        val feedFresh = _lastTickAtMs.value > 0L && System.currentTimeMillis() - _lastTickAtMs.value <= MAX_TICK_AGE_MS
        if (feedFresh) {
            val sig = synchronized(lock) { signalEngine.onClock(sym, client.serverNowSec(), System.currentTimeMillis()) }
            sig?.let { onSignal(it) }
        }
        expireStaleTrades()
    }

    /** Manual signal generation independent of Auto Trader's candle-close listener. */
    fun generateSignalForSelectedAsset(): Signal? {
        if (connState.value != ConnState.CONNECTED) return null
        if (_lastTickAtMs.value == 0L || System.currentTimeMillis() - _lastTickAtMs.value > MAX_TICK_AGE_MS) {
            log("Signal skipped: live market feed is stale or unavailable")
            return null
        }
        val cfg = _strategy.value
        val sym = _asset.value
        val result = synchronized(lock) {
            val ltfCandles = signalEngine.ltf.candles
            val htfCandles = signalEngine.htf.candles + listOfNotNull(signalEngine.htf.forming)
            val decision = Strategies.consensus(cfg, ltfCandles, htfCandles) ?: return@synchronized null
            Signal(
                id = System.currentTimeMillis(),
                asset = sym,
                direction = decision.direction,
                strategy = decision.strategy,
                reason = decision.reason,
                entryAtSec = client.serverNowSec().toLong(),
                expirySec = cfg.expirySec,
                emittedAtMs = System.currentTimeMillis()
            )
        } ?: return null
        _signals.update { (listOf(result) + it).take(100) }
        log("Manual signal generated: ${result.direction.label} ${result.asset} (${result.strategy.label})")
        return result
    }

    /** Uses the existing authenticated order route for the generated signal. */
    fun placeSignalTrade(signal: Signal, amount: Double): String? {
        if (signal.asset != _asset.value) return "Selected pair changed. Generate a new signal for the current pair."
        return place(signal.asset, amount, signal.direction, signal.expirySec, TradeSource.MANUAL, signal.id)
    }

    // ---------------- signals & autotrader ----------------

    private fun onSignal(sig: Signal) {
        _signals.update { (listOf(sig) + it).take(100) }
        Notifier.signal(app, sig)
        log("Signal ${sig.direction.label} ${sig.asset} (${sig.strategy.label}): ${sig.reason}")
        if (_auto.value) autoExecute(sig)
    }

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
        if (_auto.value == on) return
        _auto.value = on
        _autoStatus.value = if (on) "Running on ${_account.value}" else "Stopped" + (reason?.let { ": $it" } ?: "")
        log("Autotrader ${if (on) "STARTED on ${_account.value}" else "STOPPED"}${reason?.let { " ($it)" } ?: ""}")
        if (on) TradingService.start(app, _account.value) else TradingService.stop(app)
    }

    fun emergencyStop() = setAuto(false, "STOP pressed")

    private fun autoBlockReason(sig: Signal): String? {
        val cfg = _strategy.value
        val payout = _assets.value.firstOrNull { it.symbol == sig.asset }?.payout
        return when {
            connState.value != ConnState.CONNECTED -> "not connected"
            _lastTickAtMs.value == 0L || System.currentTimeMillis() - _lastTickAtMs.value > MAX_TICK_AGE_MS -> "live market feed is stale"
            riskManager.isLate(sig.entryAtSec, client.serverNowSec()) -> "signal late (> ${_risk.value.maxSignalDelaySec}s)"
            payout != null && payout < cfg.minPayout -> "payout $payout% < ${cfg.minPayout}%"
            else -> riskManager.blockReason(dayKey())
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
        val err = place(sig.asset, _risk.value.stake, sig.direction, sig.expirySec, TradeSource.AUTO, sig.id)
        if (err != null) {
            _autoStatus.value = "Order failed: $err"
            log("Autotrader order error: $err")
        } else {
            _autoStatus.value = "Placed ${sig.direction.label} ${sig.asset} from live-feed signal"
        }
    }

    private companion object { const val MAX_TICK_AGE_MS = 15_000L }

    private fun riskLimitHit(reason: String) =
        reason.startsWith("Max trades") || reason.startsWith("Daily") || reason.startsWith("Max consecutive") || reason.startsWith("Next stake")

    // ---------------- orders ----------------

    fun placeManual(direction: Direction, amount: Double, expirySec: Int): String? =
        place(_asset.value, amount, direction, expirySec, TradeSource.MANUAL, null)

    private fun place(asset: String, amount: Double, direction: Direction, expirySec: Int, source: TradeSource, signalId: Long?): String? {
        if (amount <= 0) return "Amount must be above 0"
        if (connState.value != ConnState.CONNECTED) return "Not connected"
        val demo = client.isDemo
        if (demo != (_account.value == AccountType.DEMO)) return "Session/account mismatch, reconnect"
        val id = client.openOrder(asset, amount, direction, expirySec) ?: return "Could not send order"
        val rec = TradeRecord(id, null, asset, direction, amount, expirySec, demo, source, signalId, System.currentTimeMillis())
        _trades.update { (listOf(rec) + it).take(500) }
        if (source == TradeSource.AUTO) { riskManager.onOpened(); _riskState.value = riskManager.state }
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
            it.copy(dealId = d.id, status = TradeStatus.OPEN)
        } ?: updateTrade({ it.status == TradeStatus.PENDING && it.asset == d.asset && Math.abs(it.amount - d.amount) < 1e-6 }) {
            it.copy(dealId = d.id, status = TradeStatus.OPEN)
        }
        if (r != null) log("Trade opened ${r.asset} (deal ${d.id.take(8)})")
    }

    private fun onOpenFailed(error: String, asset: String) {
        val r = updateTrade({ it.status == TradeStatus.PENDING && (asset.isEmpty() || it.asset == asset) }) {
            it.copy(status = TradeStatus.FAILED, note = error)
        }
        log("Order rejected: $error")
        if (r?.source == TradeSource.AUTO) { riskManager.onRejected(); _riskState.value = riskManager.state }
    }

    private fun onClosed(d: Deal) {
        val (outcome, net) = settle(d)
        val status = when (outcome) { TradeOutcome.WIN -> TradeStatus.WIN; TradeOutcome.LOSS -> TradeStatus.LOSS; TradeOutcome.DRAW -> TradeStatus.DRAW }
        val r = updateTrade({ it.dealId == d.id && (it.status == TradeStatus.OPEN || it.status == TradeStatus.PENDING) }) {
            it.copy(status = status, netProfit = net)
        } ?: return
        log("Result ${r.asset}: $status ${"%+.2f".format(net)}")
        Notifier.result(app, r)
        client.refreshBalance()
        if (r.source == TradeSource.AUTO) {
            riskManager.onClosed(net, dayKey()); _riskState.value = riskManager.state
            if (_auto.value) riskManager.blockReason(dayKey())?.takeIf { riskLimitHit(it) }?.let { setAuto(false, it) }
        }
    }

    /** No result after expiry + 90s: mark UNKNOWN and count it as a full loss for the risk limits. */
    private fun expireStaleTrades() {
        val now = System.currentTimeMillis()
        val stale = _trades.value.filter { (it.status == TradeStatus.OPEN || it.status == TradeStatus.PENDING) && now > it.placedAtMs + (it.expirySec + 90) * 1000L }
        for (t in stale) {
            updateTrade({ it.requestId == t.requestId }) { it.copy(status = TradeStatus.UNKNOWN, note = "No result received; check Pocket Option history") }
            log("No result for ${t.asset} trade; marked UNKNOWN")
            if (t.source == TradeSource.AUTO) {
                riskManager.onClosed(-t.amount, dayKey()); _riskState.value = riskManager.state
            }
        }
    }
}
