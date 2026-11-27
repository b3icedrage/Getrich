package com.pockettrader.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pockettrader.app.protocol.ConnState
import com.pockettrader.app.protocol.Direction
import com.pockettrader.app.protocol.SsidParser
import com.pockettrader.app.signals.Signal
import com.pockettrader.app.signals.StrategyType
import com.pockettrader.app.signals.TrendTrigger
import com.pockettrader.app.trading.AccountType
import com.pockettrader.app.trading.TradeStatus
import com.pockettrader.app.trading.TradingEngine
import com.pockettrader.app.analytics.analyseTechnicalMarket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent

private val Green = Color(0xFF2E7D32)
private val Red = Color(0xFFC62828)
private val hms = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

enum class Tab(val label: String) { TRADE("Trade"), SIGNALS("Signals"), ANALYTICS("Stats"), CHAT("AI Chat"), AUTO("Auto"), LOG("Log"), ACCOUNT("Account") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(engine: TradingEngine) {
    var tab by rememberSaveable { mutableStateOf(Tab.ACCOUNT.takeIf { !engine.sessions.value.first && !engine.sessions.value.second } ?: Tab.TRADE) }
    var showWebLogin by remember { mutableStateOf(false) }
    var confirmReal by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val conn by engine.connState.collectAsStateWithLifecycle()
    val account by engine.account.collectAsStateWithLifecycle()
    val balance by engine.balance.collectAsStateWithLifecycle()
    val auto by engine.autoEnabled.collectAsStateWithLifecycle()

    if (showWebLogin) {
        LoginWebViewScreen(onCaptured = { engine.saveSession(it) }, onClose = { showWebLogin = false })
        return
    }

    if (confirmReal) {
        AlertDialog(
            onDismissRequest = { confirmReal = false },
            title = { Text("Switch to REAL money?") },
            text = { Text("Trades on the Real account use your real balance and can lose it. Manual and autotrader orders will be real. Continue?") },
            confirmButton = { TextButton(onClick = { confirmReal = false; message = engine.switchAccount(AccountType.REAL) }) { Text("Yes, use Real", color = Red) } },
            dismissButton = { TextButton(onClick = { confirmReal = false }) { Text("Stay on Demo") } },
        )
    }
    message?.let { m -> AlertDialog(onDismissRequest = { message = null }, confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } }, text = { Text(m) }) }

    Scaffold(
        topBar = {
            Column(Modifier.background(Brush.horizontalGradient(if (account == AccountType.REAL) listOf(Color(0xFF5B171D), Color(0xFF241116)) else listOf(Color(0xFF123B55), Color(0xFF101D35)))).padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("PocketTrader", fontWeight = FontWeight.Bold, fontSize = 21.sp, letterSpacing = 0.3.sp, color = Color.White)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val online = conn == ConnState.CONNECTED
                            Box(Modifier.size(7.dp).background(if (online) Color(0xFF4ADE80) else Color(0xFFFBBF24), androidx.compose.foundation.shape.CircleShape))
                            Spacer(Modifier.width(6.dp))
                            Text("${account.name} · ${connLabel(conn)}", fontSize = 12.sp, color = Color.White.copy(alpha = 0.82f))
                        }
                        AnimatedContent(targetState = balance, transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.96f)) togetherWith (fadeOut() + scaleOut(targetScale = 0.96f)) }, label = "balance-value") { value ->
                            Text("Balance  ${value?.let { "%.2f".format(it) } ?: "—"}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                        }
                    }
                    SingleChoiceSegmentedButtonRow {
                        SegmentedButton(selected = account == AccountType.DEMO, onClick = { message = engine.switchAccount(AccountType.DEMO) },
                            shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("Demo") }
                        SegmentedButton(selected = account == AccountType.REAL, onClick = { if (account != AccountType.REAL) confirmReal = true },
                            shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("Real") }
                    }
                }
                if (auto) {
                    Button(onClick = { engine.emergencyStop() }, colors = ButtonDefaults.buttonColors(containerColor = Red),
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(52.dp)) {
                        Text("STOP AUTOTRADER", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(selected = tab == t, onClick = { tab = t }, label = { Text(t.label) }, icon = {
                        Icon(when (t) {
                            Tab.TRADE -> Icons.Default.ShowChart; Tab.SIGNALS -> Icons.Default.Notifications
                            Tab.ANALYTICS -> Icons.Default.Analytics; Tab.CHAT -> Icons.Default.Chat; Tab.AUTO -> Icons.Default.SmartToy; Tab.LOG -> Icons.Default.List; Tab.ACCOUNT -> Icons.Default.AccountCircle
                        }, null)
                    })
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            AnimatedContent(
                targetState = tab,
                transitionSpec = { (slideInHorizontally { it / 10 } + fadeIn()) togetherWith (slideOutHorizontally { -it / 10 } + fadeOut()) },
                label = "screen-transition"
            ) { activeTab ->
            when (activeTab) {
                Tab.TRADE -> TradeScreen(engine) { message = it }
                Tab.SIGNALS -> SignalsScreen(engine)
                Tab.ANALYTICS -> AnalyticsScreen(engine)
                Tab.CHAT -> ChatScreen(engine) { message = it }
                Tab.AUTO -> AutoScreen(engine) { message = it }
                Tab.LOG -> LogScreen(engine)
                Tab.ACCOUNT -> AccountScreen(engine, onWebLogin = { showWebLogin = true }) { message = it }
            }
            }
        }
    }
}

private fun connLabel(c: ConnState) = when (c) {
    ConnState.DISCONNECTED -> "offline"; ConnState.CONNECTING -> "connecting…"; ConnState.AUTHENTICATING -> "signing in…"
    ConnState.CONNECTED -> "live"; ConnState.AUTH_FAILED -> "session rejected"
}

// ---------------------------------------------------------------- Trade
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TradeScreen(engine: TradingEngine, onMessage: (String) -> Unit) {
    val assets by engine.assets.collectAsStateWithLifecycle()
    val asset by engine.selectedAsset.collectAsStateWithLifecycle()
    val price by engine.price.collectAsStateWithLifecycle()
    val lastTick by engine.lastTickAtMs.collectAsStateWithLifecycle()
    val conn by engine.connState.collectAsStateWithLifecycle()
    val account by engine.account.collectAsStateWithLifecycle()
    var amount by rememberSaveable { mutableStateOf("1") }
    var expiry by rememberSaveable { mutableStateOf(60) }
    var filter by rememberSaveable { mutableStateOf("") }
    var pending by remember { mutableStateOf<Direction?>(null) }
    val current = assets.firstOrNull { it.symbol == asset }

    pending?.let { dir ->
        AlertDialog(onDismissRequest = { pending = null },
            title = { Text("Confirm REAL trade") },
            text = { Text("${dir.label} $asset, amount $amount, expiry ${expiry}s on your REAL account.") },
            confirmButton = { TextButton(onClick = { pending = null; engine.placeManual(dir, amount.toDoubleOrNull() ?: 0.0, expiry)?.let(onMessage) }) { Text("Place real trade", color = Red) } },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancel") } })
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        if (conn != ConnState.CONNECTED) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Not connected", Modifier.weight(1f))
                Button(onClick = { engine.connect()?.let(onMessage) }) { Text("Connect") }
            }
        }
        Card(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF14243A))
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(current?.name ?: asset, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text(if (current?.isOtc == true) "OTC MARKET" else "MARKET WATCH", fontSize = 10.sp,
                            letterSpacing = 1.4.sp, color = Color(0xFF91A8C5))
                    }
                    Surface(shape = RoundedCornerShape(50), color = Color(0xFF183F3B)) {
                        Text("${current?.payout?.let { "$it% PAYOUT" } ?: "PAYOUT —"}",
                            Modifier.padding(horizontal = 11.dp, vertical = 7.dp), color = Color(0xFF58E0C4),
                            fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    }
                }
                Spacer(Modifier.height(12.dp))
                AnimatedContent(targetState = price, label = "market-price") { p ->
                    Text(p?.let { "%.5f".format(it) } ?: "Waiting for price…",
                        fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
                val age = if (lastTick > 0L) ((System.currentTimeMillis() - lastTick) / 1000L).coerceAtLeast(0L) else null
                val fresh = age != null && age <= 15L
                Text(
                    when { conn != ConnState.CONNECTED -> "○  Disconnected"; fresh -> "●  Live quote · ${age}s ago"; else -> "⚠  Quote stale · orders blocked" },
                    fontSize = 11.sp, color = if (fresh) Color(0xFF58E0C4) else Color(0xFFFFC76A))

            }
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(amount, { amount = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("Amount") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.width(120.dp), singleLine = true)
            ExpiryPicker(expiry) { expiry = it }
        }
        Spacer(Modifier.height(8.dp))
        val pulse = rememberInfiniteTransition(label = "trade-button-pulse")
            .animateFloat(initialValue = 0.985f, targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(1100), repeatMode = RepeatMode.Reverse), label = "pulse").value
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(Direction.CALL to Color(0xFF00A889), Direction.PUT to Color(0xFFE34D68)).forEach { (d, col) ->
                Button(onClick = {
                    if (account == AccountType.REAL) pending = d
                    else engine.placeManual(d, amount.toDoubleOrNull() ?: 0.0, expiry)?.let(onMessage)
                }, enabled = conn == ConnState.CONNECTED,
                    colors = ButtonDefaults.buttonColors(containerColor = col, disabledContainerColor = Color(0xFF39475A)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.weight(1f).height(62.dp).graphicsLayer {
                        val s = if (conn == ConnState.CONNECTED) pulse else 1f
                        scaleX = s; scaleY = s
                    }) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (d == Direction.CALL) "▲  HIGHER" else "▼  LOWER", fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
                        Text(if (d == Direction.CALL) "UP" else "DOWN", fontSize = 9.sp, letterSpacing = 1.5.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(filter, { filter = it }, label = { Text("Search assets") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        LazyColumn(Modifier.fillMaxSize()) {
            items(assets.filter { filter.isBlank() || it.name.contains(filter, true) || it.symbol.contains(filter, true) }, key = { it.symbol }) { a ->
                val selected = a.symbol == asset
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                        .clickable { engine.selectAsset(a.symbol) }
                        .then(if (selected) Modifier.border(1.dp, Color(0xFF35D0BA), RoundedCornerShape(13.dp)) else Modifier),
                    shape = RoundedCornerShape(13.dp),
                    colors = CardDefaults.cardColors(containerColor = if (selected) Color(0xFF183B45) else Color(0xFF111D30))
                ) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp))
                            .background(if (selected) Color(0xFF23665D) else Color(0xFF1C2C43)),
                            contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.ShowChart, null, tint = if (selected) Color(0xFF63E6C5) else Color(0xFF91A8C5),
                                modifier = Modifier.size(19.dp))
                        }
                        Spacer(Modifier.width(11.dp))
                        Column(Modifier.weight(1f)) {
                            Text(a.name, fontWeight = FontWeight.SemiBold, color = if (a.isActive) Color.White else Color.Gray)
                            Text(if (a.isOtc) "OTC instrument" else a.symbol, fontSize = 10.sp, color = Color(0xFF91A8C5))
                        }
                        Text(if (a.isActive) "${a.payout}%" else "CLOSED", fontWeight = FontWeight.Bold,
                            fontSize = 12.sp, color = if (a.isActive) Color(0xFF58E0C4) else Color.Gray)
                        if (selected) {
                            Spacer(Modifier.width(7.dp))
                            Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF58E0C4), modifier = Modifier.size(17.dp))
                        }
                    }
                }
            }
        }
    }
}

val EXPIRIES = listOf(5, 15, 30, 60, 120, 180, 300, 600, 900, 1800, 3600)
fun expiryLabel(s: Int) = if (s < 60) "${s}s" else if (s < 3600) "${s / 60}m" else "${s / 3600}h"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpiryPicker(value: Int, options: List<Int> = EXPIRIES, label: String = "Expiry", onChange: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(open, { open = it }) {
        OutlinedTextField(expiryLabel(value), {}, readOnly = true, label = { Text(label) },
            modifier = Modifier.menuAnchor().width(130.dp), trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) })
        ExposedDropdownMenu(open, { open = false }) {
            options.forEach { e -> DropdownMenuItem(text = { Text(expiryLabel(e)) }, onClick = { onChange(e); open = false }) }
        }
    }
}

// ---------------------------------------------------------------- Signals
@Composable
fun SignalsScreen(engine: TradingEngine) {
    val cfg by engine.strategy.collectAsStateWithLifecycle()
    val asset by engine.selectedAsset.collectAsStateWithLifecycle()
    val counts by engine.candleCount.collectAsStateWithLifecycle()
    val conn by engine.connState.collectAsStateWithLifecycle()
    val account by engine.account.collectAsStateWithLifecycle()
    val recent by engine.signals.collectAsStateWithLifecycle()
    val candles by engine.candleHistory.collectAsStateWithLifecycle()
    val technical = remember(candles) { analyseTechnicalMarket(candles) }
    var generated by remember { mutableStateOf<Signal?>(null) }
    var amount by rememberSaveable { mutableStateOf("1.00") }
    var feedback by remember { mutableStateOf<String?>(null) }
    var confirmReal by remember { mutableStateOf(false) }

    if (confirmReal && generated != null) {
        val signal = generated!!
        AlertDialog(
            onDismissRequest = { confirmReal = false },
            title = { Text("Confirm REAL ${signal.direction.label.uppercase()} trade") },
            text = { Text("Place ${signal.direction.label} on ${signal.asset} for $amount with ${expiryLabel(signal.expirySec)} expiry using your REAL balance? This order can lose money.") },
            confirmButton = { TextButton(onClick = {
                confirmReal = false
                feedback = engine.placeSignalTrade(signal, amount.toDoubleOrNull() ?: 0.0)
            }) { Text("Confirm real trade", color = Red) } },
            dismissButton = { TextButton(onClick = { confirmReal = false }) { Text("Cancel") } }
        )
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF14243A))) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("SIGNAL GENERATOR", color = Color(0xFF91A8C5), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text(asset, color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.ExtraBold)
                    }
                    Surface(shape = RoundedCornerShape(50), color = if (account == AccountType.REAL) Color(0xFF5B252B) else Color(0xFF17483F)) {
                        Text(account.name, Modifier.padding(horizontal = 12.dp, vertical = 7.dp), color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
                Text("${cfg.type.label} · ${expiryLabel(cfg.timeframeSec)} candles · ${expiryLabel(cfg.expirySec)} expiry", color = Color(0xFFB8C8DA), fontSize = 13.sp)
                Text("Candles ready: ${counts.first} signal / ${counts.second} trend", color = Color(0xFF91A8C5), fontSize = 12.sp)
                Text("LIVE DATA: Pocket Option online price ticks + broker candle history. Signals are blocked if the latest tick is older than 15 seconds.", color = Color(0xFF58E0C4), fontSize = 11.sp)
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF0D1929))) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("TECHNICAL MARKET ANALYSIS", modifier = Modifier.weight(1f), color = Color(0xFF91A8C5), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Text(technical.label, color = when {
                                technical.direction == Direction.CALL -> Color(0xFF58E0C4)
                                technical.direction == Direction.PUT -> Color(0xFFFF8292)
                                else -> Color(0xFFFFC76A)
                            }, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (technical.readings.isEmpty()) "—" else "${technical.score}/100", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black)
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text("Indicator confluence", color = Color.White, fontWeight = FontWeight.SemiBold)
                                Text(technical.summary, color = Color(0xFFB8C8DA), fontSize = 11.sp)
                            }
                        }
                        technical.readings.forEach { reading ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(reading.name, modifier = Modifier.weight(1f), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                Text(when (reading.bias) { 1 -> "BULLISH"; -1 -> "BEARISH"; else -> "NEUTRAL" },
                                    color = when (reading.bias) { 1 -> Color(0xFF58E0C4); -1 -> Color(0xFFFF8292); else -> Color(0xFFFFC76A) },
                                    fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                            Text("${reading.value} · ${reading.note}", color = Color(0xFF91A8C5), fontSize = 10.sp)
                        }
                        Text("Uses closed broker candles: EMA 20/50, RSI 14, MACD, ADX/+DI/-DI, Bollinger Bands and momentum. Score is agreement strength, NOT a win probability. WAIT means no clear edge.",
                            color = Color(0xFF91A8C5), fontSize = 10.sp)
                    }
                }
                Button(onClick = {
                    generated = null
                    feedback = null
                    val result = engine.generateSignalForSelectedAsset()
                    if (result != null) generated = result
                    else feedback = if (conn != ConnState.CONNECTED) "Connect to the market first."
                        else "No signal: the live feed may be stale, candle history may be incomplete, or strategies do not agree. Wait for fresh ticks and more candles."
                }, enabled = conn == ConnState.CONNECTED,
                    modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(15.dp)) {
                    Icon(Icons.Default.ShowChart, null)
                    Spacer(Modifier.width(8.dp))
                    Text("GENERATE SIGNAL", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
                }
                Text("Independent of Auto Trader. Analyses the pair currently selected on the Trade tab.",
                    color = Color(0xFF91A8C5), fontSize = 11.sp)
            }
        }

        generated?.let { signal ->
            val isBuy = signal.direction == Direction.CALL
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = if (isBuy) Color(0xFF103F38) else Color(0xFF4A202B))) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("FRESH SIGNAL · ${signal.asset}", color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text(if (isBuy) "BUY" else "SELL", color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.Black)
                    Text("${signal.strategy.label} · ${signal.reason}", color = Color.White, fontSize = 13.sp)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(amount, { amount = it.filter { c -> c.isDigit() || c == '.' }.take(10) },
                            label = { Text("Trade amount") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f), singleLine = true)
                        Surface(color = Color.Black.copy(alpha = 0.18f), shape = RoundedCornerShape(12.dp)) {
                            Text(expiryLabel(signal.expirySec), Modifier.padding(horizontal = 14.dp, vertical = 17.dp), color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                    Button(onClick = {
                        if (account == AccountType.REAL) confirmReal = true
                        else feedback = engine.placeSignalTrade(signal, amount.toDoubleOrNull() ?: 0.0)
                    }, enabled = conn == ConnState.CONNECTED && (amount.toDoubleOrNull() ?: 0.0) > 0.0,
                        modifier = Modifier.fillMaxWidth().height(76.dp), shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = if (isBuy) Color(0xFF00A889) else Color(0xFFE34D68))) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(if (isBuy) "▲  BUY NOW" else "▼  SELL NOW", fontSize = 23.sp, fontWeight = FontWeight.Black)
                            Text(if (account == AccountType.REAL) "REAL ORDER · CONFIRMATION REQUIRED" else "PLACE DEMO TRADE", fontSize = 10.sp)
                        }
                    }
                    Text("Signals are not guarantees. Check pair, amount and expiry before ordering.", color = Color.White.copy(alpha = 0.72f), fontSize = 11.sp)
                }
            }
        }

        feedback?.let { msg ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Text(if (msg.isBlank()) "Order request sent to the trading connection." else msg, Modifier.padding(12.dp), fontSize = 13.sp)
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("STRATEGY SETTINGS", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                StrategyEditor(engine)
            }
        }
        Text("RECENT SIGNALS", fontWeight = FontWeight.Bold, fontSize = 13.sp)
        if (recent.isEmpty()) Text("No signals yet. Generate one for the selected pair.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        else LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(recent.take(10), key = { it.id }) { s ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = RoundedCornerShape(10.dp), color = if (s.direction == Direction.CALL) Color(0xFF17483F) else Color(0xFF5B252B)) {
                            Text(if (s.direction == Direction.CALL) "BUY" else "SELL", Modifier.padding(horizontal = 10.dp, vertical = 7.dp), color = Color.White, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.asset, fontWeight = FontWeight.Bold)
                            Text("${s.strategy.label} · ${expiryLabel(s.expirySec)}", fontSize = 11.sp, color = Color.Gray)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StrategyEditor(engine: TradingEngine) {
    val cfg by engine.strategy.collectAsStateWithLifecycle()
    var expanded by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf(false) }
    Column {
        ExposedDropdownMenuBox(open, { open = it }) {
            OutlinedTextField(cfg.type.label, {}, readOnly = true, label = { Text("Strategy") },
                modifier = Modifier.menuAnchor().fillMaxWidth(), trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) })
            ExposedDropdownMenu(open, { open = false }) {
                StrategyType.entries.forEach { t -> DropdownMenuItem(text = { Text(t.label) }, onClick = { engine.updateStrategy(cfg.copy(type = t)); open = false }) }
            }
        }
        Text(cfg.type.description, fontSize = 12.sp, color = Color.Gray)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ExpiryPicker(cfg.timeframeSec, listOf(15, 30, 60, 300, 900), "Candles") { engine.updateStrategy(cfg.copy(timeframeSec = it)) }
            ExpiryPicker(cfg.higherTimeframeSec, listOf(300, 900, 1800, 3600, 14400), "Trend TF") { engine.updateStrategy(cfg.copy(higherTimeframeSec = it)) }
        }
        ExpiryPicker(cfg.expirySec, label = "Trade expiry") { engine.updateStrategy(cfg.copy(expirySec = it)) }
        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide parameters" else "More parameters") }
        if (expanded) {
            NumField("Trend EMA period (higher TF)", cfg.htfEmaPeriod.toDouble()) { engine.updateStrategy(cfg.copy(htfEmaPeriod = it.toInt().coerceAtLeast(2))) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Trend trigger: ")
                TrendTrigger.entries.forEach { t -> FilterChip(cfg.trendTrigger == t, { engine.updateStrategy(cfg.copy(trendTrigger = t)) }, { Text(t.name) }, Modifier.padding(end = 6.dp)) }
            }
            NumField("RSI oversold", cfg.rsiOversold) { engine.updateStrategy(cfg.copy(rsiOversold = it)) }
            NumField("RSI overbought", cfg.rsiOverbought) { engine.updateStrategy(cfg.copy(rsiOverbought = it)) }
            NumField("ADX trend level", cfg.adxTrend) { engine.updateStrategy(cfg.copy(adxTrend = it)) }
            NumField("ADX range level", cfg.adxRange) { engine.updateStrategy(cfg.copy(adxRange = it)) }
            NumField("Breakout tick-volume multiple", cfg.volumeMult) { engine.updateStrategy(cfg.copy(volumeMult = it)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(cfg.requireVolume, { engine.updateStrategy(cfg.copy(requireVolume = it)) })
                Text("  Require tick-volume surge for breakouts (tick count is a volume proxy)", fontSize = 12.sp)
            }
            NumField("Minimum payout %", cfg.minPayout.toDouble()) { engine.updateStrategy(cfg.copy(minPayout = it.toInt())) }
        }
    }
}

@Composable
fun NumField(label: String, value: Double, onChange: (Double) -> Unit) {
    var text by remember(value) { mutableStateOf(if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()) }
    OutlinedTextField(text, { t -> text = t; t.toDoubleOrNull()?.let(onChange) }, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp))
}

// ---------------------------------------------------------------- Auto
@Composable
fun AutoScreen(engine: TradingEngine, onMessage: (String) -> Unit) {
    val auto by engine.autoEnabled.collectAsStateWithLifecycle()
    val status by engine.autoStatus.collectAsStateWithLifecycle()
    val risk by engine.risk.collectAsStateWithLifecycle()
    val rs by engine.riskState.collectAsStateWithLifecycle()
    val account by engine.account.collectAsStateWithLifecycle()
    val conn by engine.connState.collectAsStateWithLifecycle()
    val cfg by engine.strategy.collectAsStateWithLifecycle()
    var confirmRealAuto by remember { mutableStateOf(false) }
    if (confirmRealAuto) {
        AlertDialog(onDismissRequest = { confirmRealAuto = false },
            title = { Text("Autotrade with REAL money?") },
            text = { Text("The autotrader will place REAL-money trades of ${"%.2f".format(risk.stake)} each automatically, without asking for each order. It stops only when a configured risk limit is hit or you press STOP. Losses are possible; signals are not guaranteed.") },
            confirmButton = { TextButton(onClick = { confirmRealAuto = false; engine.setAuto(true) }) { Text("Start on Real", color = Red) } },
            dismissButton = { TextButton(onClick = { confirmRealAuto = false }) { Text("Cancel") } })
    }
    Column(Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState())) {
        Text("SIGNAL-DRIVEN AUTOTRADER", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Text("Nemotron is available in the AI Chat tab only. It never approves, rejects, or changes signals.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Same live quote feed, signal strategies, risk limits and Nemotron chat are available in Demo and Real. Nemotron remains chat-only.", fontSize = 12.sp, color = Green)
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("Autotrader executes signals from: ${cfg.type.label}", fontWeight = FontWeight.Bold)
        Text("Status: $status")
        Text("Session trades ${rs.sessionTrades}/${risk.maxTradesPerSession} · Today P/L ${"%+.2f".format(rs.dailyPnl)} · Loss streak ${rs.consecutiveLosses} · Open ${rs.openTrades}", fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        if (!auto) {
            Button(onClick = {
                when {
                    conn != ConnState.CONNECTED -> onMessage("Connect to the selected account first (Trade tab).")
                    account == AccountType.REAL -> confirmRealAuto = true
                    else -> engine.setAuto(true)
                }
            }, modifier = Modifier.fillMaxWidth().height(52.dp), colors = ButtonDefaults.buttonColors(containerColor = if (account == AccountType.REAL) Red else Green)) {
                Text("Start autotrader on ${account.name}")
            }
        } else {
            Button(onClick = { engine.emergencyStop() }, modifier = Modifier.fillMaxWidth().height(72.dp), colors = ButtonDefaults.buttonColors(containerColor = Red)) {
                Text("STOP", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            }
        }
        OutlinedButton(onClick = { engine.resetSession() }, modifier = Modifier.padding(top = 6.dp)) { Text("Reset session counters") }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("Risk limits", fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { engine.updateRisk(risk.copy(stake = 0.5, maxTradesPerSession = 5, dailyStopLoss = 2.0, dailyTakeProfit = 2.0, maxConsecutiveLosses = 2, maxOpenTrades = 1, maxSignalDelaySec = 2)) }) { Text("Conservative") }
            OutlinedButton(onClick = { engine.updateRisk(risk.copy(stake = 1.0, maxTradesPerSession = 10, dailyStopLoss = 10.0, dailyTakeProfit = 10.0, maxConsecutiveLosses = 3, maxOpenTrades = 1, maxSignalDelaySec = 3)) }) { Text("Balanced") }
        }
        NumField("Fixed stake per trade", risk.stake) { engine.updateRisk(risk.copy(stake = it)) }
        NumField("Max trades per session", risk.maxTradesPerSession.toDouble()) { engine.updateRisk(risk.copy(maxTradesPerSession = it.toInt())) }
        NumField("Daily stop-loss (0 = off)", risk.dailyStopLoss) { engine.updateRisk(risk.copy(dailyStopLoss = it)) }
        NumField("Daily take-profit (0 = off)", risk.dailyTakeProfit) { engine.updateRisk(risk.copy(dailyTakeProfit = it)) }
        NumField("Max consecutive losses (0 = off)", risk.maxConsecutiveLosses.toDouble()) { engine.updateRisk(risk.copy(maxConsecutiveLosses = it.toInt().coerceAtLeast(0))) }
        NumField("Max simultaneous open trades", risk.maxOpenTrades.toDouble()) { engine.updateRisk(risk.copy(maxOpenTrades = it.toInt().coerceIn(1, 10))) }
        NumField("Skip signals later than (seconds)", risk.maxSignalDelaySec.toDouble()) { engine.updateRisk(risk.copy(maxSignalDelaySec = it.toInt())) }
        Text("The autotrader trades one position at a time on the selected asset, skips assets under the minimum payout, and stops itself when a limit is hit. Open binary options cannot be closed early; STOP only prevents new trades.", fontSize = 12.sp, color = Color.Gray)
    }
}

// ---------------------------------------------------------------- Analytics, chart and research backtests
@Composable
fun AnalyticsScreen(engine: TradingEngine) {
    val context = LocalContext.current
    val priceHistory by engine.priceHistory.collectAsStateWithLifecycle()
    val candles by engine.candleHistory.collectAsStateWithLifecycle()
    val trades by engine.trades.collectAsStateWithLifecycle()
    val asset by engine.selectedAsset.collectAsStateWithLifecycle()
    val account by engine.account.collectAsStateWithLifecycle()
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); nowMs = System.currentTimeMillis() } }
    val openTrades = trades.filter { it.isDemo == (account == AccountType.DEMO) && it.status in setOf(TradeStatus.PENDING, TradeStatus.OPEN) }
    var payout by rememberSaveable { mutableStateOf("80") }
    var expiryBars by rememberSaveable { mutableStateOf("1") }
    val closed = trades.filter { it.isDemo == (account == AccountType.DEMO) && it.status in setOf(TradeStatus.WIN, TradeStatus.LOSS, TradeStatus.DRAW) }
    val wins = closed.count { it.status == TradeStatus.WIN }
    val losses = closed.count { it.status == TradeStatus.LOSS }
    val net = closed.sumOf { it.netProfit ?: 0.0 }
    val reports = remember(candles, payout, expiryBars) {
        com.pockettrader.app.analytics.BacktestEngine.compare(candles, payout.toDoubleOrNull() ?: 80.0, expiryBars.toIntOrNull() ?: 1)
    }
    Column(Modifier.fillMaxSize().padding(14.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("PERFORMANCE · ${account.name}", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricCard("Closed", closed.size.toString(), Modifier.weight(1f))
            MetricCard("Win rate", if (closed.isEmpty()) "—" else "${"%.1f".format(wins * 100.0 / closed.size)}%", Modifier.weight(1f))
            MetricCard("Net P/L", "%+.2f".format(net), Modifier.weight(1f))
        }
        Text("Live price · $asset", fontWeight = FontWeight.Bold)
        if (priceHistory.size > 1) {
            Canvas(Modifier.fillMaxWidth().height(150.dp).background(Color(0xFF101D30), RoundedCornerShape(14.dp)).padding(10.dp)) {
                val vals = priceHistory.map { it.second }
                val lo = vals.minOrNull() ?: 0.0; val hi = vals.maxOrNull() ?: 1.0
                val span = (hi - lo).takeIf { it > 0.0 } ?: 1.0
                val path = Path()
                vals.forEachIndexed { index, value ->
                    val x = size.width * index / (vals.size - 1).toFloat()
                    val y = size.height - ((value - lo) / span * size.height).toFloat()
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, Color(0xFF4DE0C1), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f))
            }
            Text("Last ${priceHistory.size} received ticks · chart is a local price trace, not candlesticks.", fontSize = 11.sp, color = Color.Gray)
        } else Text("Waiting for enough live ticks to draw the price trace.", fontSize = 12.sp, color = Color.Gray)
        Text("Candlestick history", fontWeight = FontWeight.Bold)
        if (candles.size >= 2) {
            val view = candles.takeLast(60)
            Canvas(Modifier.fillMaxWidth().height(170.dp).background(Color(0xFF101D30), RoundedCornerShape(14.dp)).padding(8.dp)) {
                val low = view.minOf { it.low }; val high = view.maxOf { it.high }
                val span = (high - low).takeIf { it > 0.0 } ?: 1.0
                val step = size.width / view.size
                val bodyWidth = (step * 0.55f).coerceAtLeast(2f)
                view.forEachIndexed { index, candle ->
                    val x = step * (index + 0.5f)
                    fun y(v: Double) = size.height - ((v - low) / span * size.height).toFloat()
                    val up = candle.close >= candle.open
                    val tint = if (up) Color(0xFF35D0A2) else Color(0xFFE45C72)
                    drawLine(tint, androidx.compose.ui.geometry.Offset(x, y(candle.high)), androidx.compose.ui.geometry.Offset(x, y(candle.low)), strokeWidth = 1.5f)
                    val top = minOf(y(candle.open), y(candle.close))
                    val bodyHeight = maxOf(2f, kotlin.math.abs(y(candle.open) - y(candle.close)))
                    drawRect(tint, androidx.compose.ui.geometry.Offset(x - bodyWidth / 2f, top), androidx.compose.ui.geometry.Size(bodyWidth, bodyHeight))
                }
            }
            Text("Last ${view.size} closed candles · OHLC from Pocket Option history.", fontSize = 11.sp, color = Color.Gray)
        } else Text("Candlestick chart appears when historical OHLC candles load.", fontSize = 12.sp, color = Color.Gray)
        Text("Open trades · local expiry estimate", fontWeight = FontWeight.Bold)
        if (openTrades.isEmpty()) Text("No open trades on this account.", fontSize = 12.sp, color = Color.Gray)
        openTrades.forEach { trade ->
            val left = (trade.placedAtMs + trade.expirySec * 1000L - nowMs).coerceAtLeast(0L) / 1000L
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF14243A))) {
                Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${trade.direction.label} ${trade.asset} · ${trade.status}", color = Color.White, fontWeight = FontWeight.Bold)
                        Text("${if (trade.isDemo) "DEMO" else "REAL"} · ${trade.source} · ${trade.amount}", color = Color(0xFFB7C7DA), fontSize = 11.sp)
                    }
                    Text(if (left > 0) "${left}s left*" else "Awaiting result", color = Color(0xFFFFC76A), fontWeight = FontWeight.Bold)
                }
            }
        }
        if (openTrades.isNotEmpty()) Text("*Countdown is estimated from local order time; the broker's settlement status is authoritative.", fontSize = 10.sp, color = Color.Gray)
        HorizontalDivider()
        Text("Market condition", fontWeight = FontWeight.Bold)
        val regime = com.pockettrader.app.analytics.classifyMarket(candles)
        Text("${regime.label} · ${regime.detail}", fontSize = 12.sp, color = Color.Gray)
        Text("Historical strategy comparison", fontWeight = FontWeight.Bold, fontSize = 17.sp)
        Text("Uses ${candles.size} loaded closed candles for $asset. Approximate OHLC close-to-close research only; not broker fills or a prediction.", fontSize = 12.sp, color = Color.Gray)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(payout, { payout = it.filter(Char::isDigit).take(3) }, label = { Text("Assumed payout %") },
                modifier = Modifier.weight(1f), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(expiryBars, { expiryBars = it.filter(Char::isDigit).take(2) }, label = { Text("Expiry bars") },
                modifier = Modifier.weight(1f), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }
        reports.forEach { report ->
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF14243A))) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(report.strategy.label, fontWeight = FontWeight.Bold, color = Color.White)
                    Text("Trades ${report.trades} · Wins ${report.wins} · Losses ${report.losses} · Draws ${report.draws}", fontSize = 12.sp, color = Color.White)
                    Text("Win rate ${"%.1f".format(report.winRatePct)}% · Net ${"%+.2f".format(report.netUnits)} units · Max DD ${"%.2f".format(report.maxDrawdownUnits)}", fontSize = 12.sp, color = Color(0xFFB7C7DA))
                    Text("Profit factor ${if (report.profitFactor.isInfinite()) "∞" else "%.2f".format(report.profitFactor)}", fontSize = 11.sp, color = Color(0xFF58E0C4))
                }
            }
        }
        if (reports.all { it.trades == 0 }) Text("Not enough history or no qualifying setups yet. Load more Pocket Option history before interpreting these results.", fontSize = 12.sp, color = Color.Gray)
        HorizontalDivider()
        Text("Export account trade history", fontWeight = FontWeight.Bold)
        Button(onClick = {
            val send = Intent(Intent.ACTION_SEND).setType("text/csv")
                .putExtra(Intent.EXTRA_SUBJECT, "PocketTrader trade history")
                .putExtra(Intent.EXTRA_TEXT, engine.exportTradesCsv())
            runCatching { context.startActivity(Intent.createChooser(send, "Export trade history")) }
        }, modifier = Modifier.fillMaxWidth()) { Text("Share trade history CSV") }
        Text("Only completed broker-confirmed results count toward the performance metrics. UNKNOWN and pending trades are excluded.", fontSize = 11.sp, color = Color.Gray)
    }
}

@Composable
private fun MetricCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = Color(0xFF14243A))) {
        Column(Modifier.padding(10.dp)) {
            Text(label, fontSize = 10.sp, color = Color(0xFF91A8C5))
            Text(value, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
    }
}

// ---------------------------------------------------------------- AI Chat
@Composable
fun ChatScreen(engine: TradingEngine, onMessage: (String) -> Unit) {
    val configured by engine.aiConfigured.collectAsStateWithLifecycle()
    val status by engine.aiStatus.collectAsStateWithLifecycle()
    val messages by engine.chatMessages.collectAsStateWithLifecycle()
    val busy by engine.chatBusy.collectAsStateWithLifecycle()
    var apiKey by rememberSaveable { mutableStateOf("") }
    var prompt by rememberSaveable { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("NEMOTRON AI CHAT", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text("nvidia/nemotron-3.5-lightning-30b-a3b · $status", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { engine.clearChat() }) { Text("Clear") }
        }
        if (!configured) {
            Text("Enter your NVIDIA API key. It is stored encrypted on this phone and used only for chat.", fontSize = 12.sp)
            OutlinedTextField(value = apiKey, onValueChange = { apiKey = it }, label = { Text("NVIDIA API key") },
                placeholder = { Text("nvapi-…") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
            Button(onClick = {
                val error = engine.saveNvidiaApiKey(apiKey)
                if (error == null) apiKey = "" else onMessage(error)
            }, enabled = apiKey.isNotBlank()) { Text("Save API key") }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("API key saved securely", Modifier.weight(1f), fontSize = 12.sp, color = Green)
                TextButton(onClick = { engine.clearNvidiaApiKey() }) { Text("Remove key") }
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(messages) { item ->
                val user = item.role == "user"
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
                    Surface(color = if (user) Color(0xFF17483F) else MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(16.dp), modifier = Modifier.widthIn(max = 320.dp)) {
                        Text(item.content, Modifier.padding(12.dp), fontSize = 14.sp)
                    }
                }
            }
            if (busy) item { Text("Nemotron is thinking…", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = prompt, onValueChange = { prompt = it }, modifier = Modifier.weight(1f),
                placeholder = { Text("Ask Nemotron…") }, enabled = configured && !busy, maxLines = 4)
            Button(onClick = { engine.sendChatMessage(prompt); prompt = "" }, enabled = configured && !busy && prompt.isNotBlank()) { Text("Send") }
        }
        Text("Chat only — no access to the live feed and no control over trading or signal decisions.", fontSize = 10.sp, color = Color.Gray)
    }
}

// ---------------------------------------------------------------- Log
@Composable
fun LogScreen(engine: TradingEngine) {
    val context = LocalContext.current
    val trades by engine.trades.collectAsStateWithLifecycle()
    val logs by engine.logs.collectAsStateWithLifecycle()
    var showEvents by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        val closed = trades.filter { it.status in setOf(TradeStatus.WIN, TradeStatus.LOSS, TradeStatus.DRAW) }
        val wins = closed.count { it.status == TradeStatus.WIN }
        Text("Trades ${trades.size} · Closed ${closed.size} · Wins $wins · Win rate ${if (closed.isEmpty()) "-" else "${wins * 100 / closed.size}%"} · Net ${"%+.2f".format(closed.sumOf { it.netProfit ?: 0.0 })}")
        OutlinedButton(onClick = {
            val send = Intent(Intent.ACTION_SEND).setType("text/csv").putExtra(Intent.EXTRA_SUBJECT, "PocketTrader trade history").putExtra(Intent.EXTRA_TEXT, engine.exportTradesCsv())
            runCatching { context.startActivity(Intent.createChooser(send, "Export trade history")) }
        }) { Text("Export CSV") }
        Row { FilterChip(!showEvents, { showEvents = false }, { Text("Trades") }); Spacer(Modifier.width(8.dp)); FilterChip(showEvents, { showEvents = true }, { Text("Events") }) }
        LazyColumn {
            if (!showEvents) items(trades, key = { it.requestId }) { t ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("${t.direction.label} ${t.asset} ${"%.2f".format(t.amount)} · ${expiryLabel(t.expirySec)} · ${if (t.isDemo) "demo" else "REAL"} · ${t.source}", fontSize = 13.sp)
                        Text(hms.format(Date(t.placedAtMs)) + (t.note?.let { " · $it" } ?: ""), fontSize = 11.sp, color = Color.Gray)
                    }
                    Text("${t.status}${t.netProfit?.let { " %+.2f".format(it) } ?: ""}", fontWeight = FontWeight.Bold,
                        color = when (t.status) { TradeStatus.WIN -> Green; TradeStatus.LOSS -> Red; else -> Color.Unspecified })
                }
            } else items(logs) { l -> Text("${hms.format(Date(l.timeMs))}  ${l.text}", fontSize = 12.sp) }
        }
    }
}

// ---------------------------------------------------------------- Account
@Composable
fun AccountScreen(engine: TradingEngine, onWebLogin: () -> Unit, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val sessions by engine.sessions.collectAsStateWithLifecycle()
    val conn by engine.connState.collectAsStateWithLifecycle()
    var paste by remember { mutableStateOf("") }
    var backup by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState())) {
        Text("Pocket Option sessions", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text("Demo session: ${if (sessions.first) "saved ✓" else "none"}    Real session: ${if (sessions.second) "saved ✓" else "none"}")
        Text("Stored encrypted on this phone only. Your password is never stored by this app.", fontSize = 12.sp, color = Color.Gray)
        Spacer(Modifier.height(8.dp))
        Button(onClick = onWebLogin, modifier = Modifier.fillMaxWidth()) { Text("Sign in with Pocket Option (recommended)") }
        Text("Signs in on pocketoption.com inside the app and captures the session the website itself uses. After signing in, tap 'Open Demo' and 'Open Real' so both sessions are captured.", fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { engine.connect()?.let(onMessage) }, enabled = conn == ConnState.DISCONNECTED || conn == ConnState.AUTH_FAILED) { Text("Connect") }
            OutlinedButton(onClick = { engine.disconnect() }) { Text("Disconnect") }
            OutlinedButton(onClick = { engine.clearSessions() }) { Text("Forget sessions") }
        }
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Text("Settings backup", fontWeight = FontWeight.Bold, fontSize = 17.sp)
        Text("Export or restore strategy and risk settings. Pocket Option sessions and NVIDIA API keys are deliberately excluded.", fontSize = 12.sp, color = Color.Gray)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { backup = engine.settingsBackupJson() }) { Text("Generate backup") }
            OutlinedButton(onClick = {
                val clip = ClipData.newPlainText("PocketTrader settings backup", engine.settingsBackupJson())
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
                onMessage("Settings backup copied. It does not contain login sessions or API keys.")
            }) { Text("Copy backup") }
        }
        OutlinedTextField(backup, { backup = it }, label = { Text("Paste backup JSON here to restore") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp), minLines = 4)
        Button(onClick = { engine.restoreSettingsBackup(backup)?.let(onMessage) ?: onMessage("Settings restored successfully.") },
            enabled = backup.isNotBlank()) { Text("Restore settings") }
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Text("Or paste a session (fallback)", fontWeight = FontWeight.Bold)
        Text(PASTE_HELP, fontSize = 12.sp)
        OutlinedTextField(paste, { paste = it }, label = { Text("42[\"auth\",{\"session\":...}]") }, modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp))
        Button(onClick = {
            try {
                val s = SsidParser.parse(paste)
                engine.saveSession(s); paste = ""
                onMessage("Saved ${s.describe()}." + if (!s.isDemo) " Real sessions are tied to the browser's IP and user agent; it may be rejected if your phone's IP differs." else "")
            } catch (e: Exception) { onMessage(e.message ?: "Could not read that session") }
        }) { Text("Save pasted session") }
    }
}

private const val PASTE_HELP = """1. On a computer, open pocketoption.com and sign in. Open the Demo (or Real) trading page.
2. Press F12 > Network tab > filter "WS" and reload the page (F5).
3. Click the socket.io/?EIO=4… connection > Messages.
4. Find the sent message starting with 42["auth",{"session":… that has "isDemo" and "platform" (not the one with "sessionToken").
5. Right-click > Copy message, and paste it here. Repeat on the other account to save both.
Real sessions are bound to that browser's IP and user agent, so the in-app sign-in is more reliable."""
