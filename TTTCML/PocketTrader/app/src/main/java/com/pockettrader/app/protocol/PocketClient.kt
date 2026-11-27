package com.pockettrader.app.protocol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

enum class ConnState { DISCONNECTED, CONNECTING, AUTHENTICATING, CONNECTED, AUTH_FAILED }

sealed class PocketEvent {
    data class Balance(val amount: Double, val isDemo: Boolean?) : PocketEvent()
    data class Assets(val assets: List<Asset>) : PocketEvent()
    data class Ticks(val ticks: List<Tick>) : PocketEvent()
    data class History(val snapshot: HistorySnapshot, val fromLoadHistory: Boolean) : PocketEvent()
    data class Opened(val deal: Deal) : PocketEvent()
    data class OpenFailed(val failure: FailedOrder) : PocketEvent()
    data class Closed(val deals: List<Deal>) : PocketEvent()
    data class Log(val message: String) : PocketEvent()
}

/**
 * WebSocket client for the Pocket Option web-platform socket. One instance = one session
 * (demo OR real). Never logs the session string.
 */
class PocketClient(private val scope: CoroutineScope, private val http: OkHttpClient = defaultHttp()) {

    private val _state = MutableStateFlow(ConnState.DISCONNECTED)
    val state: StateFlow<ConnState> = _state
    private val _events = MutableSharedFlow<PocketEvent>(extraBufferCapacity = 512, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<PocketEvent> = _events

    @Volatile var session: SessionInfo? = null
        private set
    @Volatile private var ws: WebSocket? = null
    @Volatile private var pendingBinaryEvent: String? = null
    @Volatile private var userClosed = false
    private var keepAliveJob: Job? = null
    private var urls: List<String> = emptyList()
    private var urlIndex = 0
    private var reconnectAttempts = 0
    private val historyIndex = AtomicLong(System.currentTimeMillis() / 1000)
    /** Server clock offset (server - local), seconds, learned from ticks. */
    @Volatile var serverOffsetSec: Double = 0.0
        private set
    private var currentSymbol: Pair<String, Int>? = null

    val isDemo: Boolean get() = session?.isDemo ?: true

    fun serverNowSec(): Double = System.currentTimeMillis() / 1000.0 + serverOffsetSec

    /** [overrideUrls] is for tests (local mock server); production uses [Servers.candidates]. */
    fun connect(s: SessionInfo, overrideUrls: List<String>? = null) {
        disconnect()
        userClosed = false
        session = s
        urls = overrideUrls ?: Servers.candidates(s)
        urlIndex = 0
        reconnectAttempts = 0
        openSocket()
    }

    fun disconnect() {
        userClosed = true
        keepAliveJob?.cancel()
        ws?.close(1000, "bye")
        ws = null
        _state.value = ConnState.DISCONNECTED
    }

    private fun log(m: String) { _events.tryEmit(PocketEvent.Log(m)) }

    private fun openSocket() {
        val s = session ?: return
        val url = urls.getOrNull(urlIndex) ?: urls.first()
        _state.value = ConnState.CONNECTING
        log("Connecting to ${url.substringAfter("://").substringBefore("/")} (${if (s.isDemo) "demo" else "REAL"})")
        val req = Request.Builder()
            .url(url)
            .header("Origin", Servers.ORIGIN)
            .header("User-Agent", s.userAgent ?: Servers.DEFAULT_UA)
            .build()
        ws = http.newWebSocket(req, Listener())
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) { reconnectAttempts = 0 }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (webSocket !== ws) return
            handleText(webSocket, text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (webSocket !== ws) return
            val name = pendingBinaryEvent ?: return
            pendingBinaryEvent = null
            dispatch(name, SocketIo.decodeBinaryPayload(bytes.toByteArray()))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (webSocket !== ws) return
            log("Connection error: ${t.javaClass.simpleName} ${t.message ?: ""} ${response?.code ?: ""}".trim())
            scheduleReconnect(rotate = true)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (webSocket !== ws) return
            log("Socket closed ($code)")
            scheduleReconnect(rotate = false)
        }
    }

    private fun scheduleReconnect(rotate: Boolean) {
        keepAliveJob?.cancel()
        if (userClosed || _state.value == ConnState.AUTH_FAILED) {
            if (_state.value != ConnState.AUTH_FAILED) _state.value = ConnState.DISCONNECTED
            return
        }
        _state.value = ConnState.CONNECTING
        if (rotate) urlIndex = (urlIndex + 1) % urls.size.coerceAtLeast(1)
        reconnectAttempts++
        val wait = (1000L shl reconnectAttempts.coerceAtMost(5)).coerceAtMost(30_000L)
        scope.launch { delay(wait); if (!userClosed) openSocket() }
    }

    private fun handleText(webSocket: WebSocket, text: String) {
        when (val f = SocketIo.parse(text)) {
            is SocketIo.Frame.Open -> webSocket.send(PocketFrames.CONNECT)
            is SocketIo.Frame.Connected -> {
                _state.value = ConnState.AUTHENTICATING
                session?.let { webSocket.send(it.authFrame()) }
                // Some sessions are never explicitly confirmed with successauth
                // (BinaryOptionsTools-v2 uses a short grace period for the same reason).
                scope.launch {
                    delay(4000)
                    if (ws === webSocket && _state.value == ConnState.AUTHENTICATING) {
                        log("No successauth received; continuing (no rejection seen)")
                        onAuthenticated()
                    }
                }
            }
            SocketIo.Frame.Ping -> webSocket.send(PocketFrames.PONG)
            SocketIo.Frame.Disconnect -> {
                log("Server closed the session (41): session invalid/expired, or bound to another IP/User-Agent")
                _state.value = ConnState.AUTH_FAILED
                webSocket.close(1000, null)
            }
            is SocketIo.Frame.ConnectError -> log("Socket.IO connect error")
            is SocketIo.Frame.BinaryEventHeader -> pendingBinaryEvent = f.name
            is SocketIo.Frame.Event -> dispatch(f.name, f.payload)
            else -> {}
        }
    }

    private fun dispatch(name: String, payload: Any?) {
        when (name) {
            "successauth" -> onAuthenticated()
            "NotAuthorized", "notAuthorized", "auth_error" -> {
                log("Server rejected the session ($name). Capture a fresh session.")
                _state.value = ConnState.AUTH_FAILED
                ws?.close(1000, null)
            }
            "successupdateBalance", "updateBalance" -> PocketParsers.balance(payload)?.let { _events.tryEmit(PocketEvent.Balance(it.first, it.second)) }
            "updateAssets" -> PocketParsers.assets(payload).takeIf { it.isNotEmpty() }?.let { _events.tryEmit(PocketEvent.Assets(it)) }
            "updateStream" -> {
                val ticks = PocketParsers.ticks(payload)
                if (ticks.isNotEmpty()) {
                    val newest = ticks.maxOf { it.timeSec }
                    val off = newest - System.currentTimeMillis() / 1000.0
                    serverOffsetSec = if (serverOffsetSec == 0.0) off else serverOffsetSec * 0.9 + off * 0.1
                    _events.tryEmit(PocketEvent.Ticks(ticks))
                }
            }
            "updateHistoryNewFast", "updateHistoryNew", "updateHistory", "history" ->
                PocketParsers.history(payload)?.let { _events.tryEmit(PocketEvent.History(it, false)) }
            "loadHistoryPeriod", "loadHistoryPeriodFast" ->
                PocketParsers.loadHistory(payload)?.let { _events.tryEmit(PocketEvent.History(it, true)) }
            "successopenOrder" -> (payload as? org.json.JSONObject)?.let { PocketParsers.deal(it) }?.let { _events.tryEmit(PocketEvent.Opened(it)) }
            "failopenOrder" -> PocketParsers.failedOrder(payload)?.let { _events.tryEmit(PocketEvent.OpenFailed(it)) }
            "updateClosedDeals", "successcloseOrder" -> PocketParsers.deals(payload).takeIf { it.isNotEmpty() }?.let { _events.tryEmit(PocketEvent.Closed(it)) }
            else -> {}
        }
    }

    private fun onAuthenticated() {
        if (_state.value == ConnState.CONNECTED) return
        _state.value = ConnState.CONNECTED
        log("Authenticated (${if (isDemo) "demo" else "REAL"})")
        val w = ws ?: return
        w.send(PocketFrames.LOAD_ASSETS)
        w.send(PocketFrames.GET_BALANCE)
        currentSymbol?.let { (a, p) -> sendSymbol(a, p) }
        keepAliveJob?.cancel()
        keepAliveJob = scope.launch {
            while (isActive) { delay(20_000); ws?.send(PocketFrames.KEEP_ALIVE) }
        }
    }

    /** Select the chart symbol and subscribe to its price stream. */
    fun watch(asset: String, periodSec: Int) {
        currentSymbol = asset to periodSec
        if (_state.value == ConnState.CONNECTED) sendSymbol(asset, periodSec)
    }

    /** Subscribe to an additional market without changing the selected chart. */
    fun subscribeMarket(asset: String) {
        if (_state.value == ConnState.CONNECTED) ws?.send(PocketFrames.subscribe(asset))
    }

    private fun sendSymbol(asset: String, periodSec: Int) {
        val w = ws ?: return
        w.send(PocketFrames.changeSymbol(asset, periodSec))
        w.send(PocketFrames.subscribe(asset))
    }

    fun requestHistory(asset: String, periodSec: Int, candles: Int) {
        val w = ws ?: return
        if (_state.value != ConnState.CONNECTED) return
        val end = serverNowSec().toLong()
        w.send(PocketFrames.loadHistoryPeriod(asset, periodSec, end, (periodSec.toLong() * candles).coerceAtMost(86_400L * 3), historyIndex.incrementAndGet()))
    }

    fun refreshBalance() { ws?.send(PocketFrames.GET_BALANCE) }

    /**
     * Send an order on the CURRENT session. isDemo always comes from the session so a demo
     * session can never send a real order. Returns the requestId, or null if not connected.
     */
    fun openOrder(asset: String, amount: Double, direction: Direction, timeSec: Int): String? {
        val s = session ?: return null
        val w = ws ?: return null
        if (_state.value != ConnState.CONNECTED) return null
        val id = UUID.randomUUID().toString()
        val ok = w.send(PocketFrames.openOrder(asset, amount, direction, timeSec, s.isDemo, id))
        return if (ok) id else null
    }

    companion object {
        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(0, TimeUnit.SECONDS)
            .build()
    }
}
