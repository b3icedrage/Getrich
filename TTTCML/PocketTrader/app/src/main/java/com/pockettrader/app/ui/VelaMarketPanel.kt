package com.pockettrader.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pockettrader.app.protocol.Candle
import com.pockettrader.app.signals.Indicators
import kotlin.math.max
import kotlin.math.min

private enum class MarketView { CHART, DOM }

@Composable
fun VelaMarketPanel(candles: List<Candle>, asset: String, timeframeSec: Int) {
    var view by remember { mutableStateOf(MarketView.CHART) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableFloatStateOf(0f) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF101D30))
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("MARKET WORKSPACE", color = Color.White, fontSize = 12.sp)
                    Text("$asset · ${timeframeSec}s · ${candles.size} candles", color = Color(0xFF91A8C5), fontSize = 10.sp)
                }
                SingleChoiceSegmentedButtonRow {
                    SegmentedButton(view == MarketView.CHART, { view = MarketView.CHART }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Chart") }
                    SegmentedButton(view == MarketView.DOM, { view = MarketView.DOM }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("DOM") }
                }
            }
            if (view == MarketView.CHART) {
                if (candles.size < 3) {
                    Text("Waiting for genuine broker OHLC candles. No synthetic candles are drawn.", color = Color(0xFFFFC76A), fontSize = 12.sp)
                    Box(Modifier.fillMaxWidth().height(210.dp).background(Color(0xFF0B1524), RoundedCornerShape(10.dp)))
                } else {
                    val transform = rememberTransformableState { zoomChange, panChange, _ ->
                        zoom = (zoom * zoomChange).coerceIn(0.65f, 5f)
                        pan = (pan + panChange.x).coerceIn(-1200f, 1200f)
                    }
                    Canvas(
                        Modifier.fillMaxWidth().height(230.dp)
                            .background(Color(0xFF0B1524), RoundedCornerShape(10.dp))
                            .transformable(transform)
                    ) {
                        val count = (candles.size / zoom).toInt().coerceIn(12, candles.size)
                        val shift = (pan / 12f).toInt()
                        val end = (candles.size - shift).coerceIn(count, candles.size)
                        val start = (end - count).coerceAtLeast(0)
                        val visible = candles.subList(start, end)
                        val top = visible.maxOf { it.high }
                        val bottom = visible.minOf { it.low }
                        val span = (top - bottom).takeIf { it > 0.0 } ?: max(kotlin.math.abs(top) * 0.00001, 0.00001)
                        val chartBottom = size.height * 0.73f
                        val chartTop = 8f
                        val chartHeight = chartBottom - chartTop
                        fun y(v: Double) = chartBottom - (((v - bottom) / span).toFloat() * chartHeight)
                        for (i in 0..4) {
                            val gy = chartTop + chartHeight * i / 4f
                            drawLine(Color(0xFF23364D), Offset(0f, gy), Offset(size.width, gy), 1f)
                        }
                        val step = size.width / visible.size.coerceAtLeast(1)
                        val bodyW = (step * 0.62f).coerceAtLeast(1.5f)
                        visible.forEachIndexed { index, candle ->
                            val x = (index + 0.5f) * step
                            val up = candle.close >= candle.open
                            val color = if (up) Color(0xFF31C7A1) else Color(0xFFEF6478)
                            drawLine(color, Offset(x, y(candle.high)), Offset(x, y(candle.low)), 1.4f)
                            val bodyTop = min(y(candle.open), y(candle.close))
                            val bodyHeight = max(2f, kotlin.math.abs(y(candle.open) - y(candle.close)))
                            drawRect(color, Offset(x - bodyW / 2f, bodyTop), androidx.compose.ui.geometry.Size(bodyW, bodyHeight))
                            val volumeMax = visible.maxOfOrNull { it.volume }?.takeIf { it > 0.0 } ?: 1.0
                            val volH = (candle.volume / volumeMax).toFloat().coerceIn(0f, 1f) * (size.height * 0.13f)
                            drawRect(color.copy(alpha = 0.35f), Offset(x - bodyW / 2f, size.height - volH), androidx.compose.ui.geometry.Size(bodyW, volH))
                        }
                        val closes = candles.take(end).map { it.close }
                        listOf(9 to Color(0xFFFFD166), 21 to Color(0xFF6EB6FF), 50 to Color(0xFFB69CFF)).forEach { (period, color) ->
                            if (closes.size >= period) {
                                val values = Indicators.ema(closes, period)
                                val path = Path()
                                visible.forEachIndexed { index, candle ->
                                    val absoluteIndex = start + index
                                    val value = values.getOrNull(absoluteIndex) ?: Double.NaN
                                    if (value.isFinite()) {
                                        val point = Offset((index + 0.5f) * step, y(value))
                                        if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
                                    }
                                }
                                drawPath(path, color, style = Stroke(width = 1.7f))
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("● EMA 9", color = Color(0xFFFFD166), fontSize = 10.sp)
                        Text("● EMA 21", color = Color(0xFF6EB6FF), fontSize = 10.sp)
                        Text("● EMA 50", color = Color(0xFFB69CFF), fontSize = 10.sp)
                    }
                    Text("Pinch to zoom · drag to pan · volume bars are broker-reported volume or tick-count proxies, not exchange-traded volume.", color = Color(0xFF91A8C5), fontSize = 10.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { zoom = (zoom / 1.25f).coerceAtLeast(0.65f); pan = 0f }) { Text("Zoom out") }
                        TextButton(onClick = { zoom = (zoom * 1.25f).coerceAtMost(5f); pan = 0f }) { Text("Zoom in") }
                        TextButton(onClick = { zoom = 1f; pan = 0f }) { Text("Reset") }
                    }
                }
            } else {
                Text("DEPTH OF MARKET · $asset", color = Color.White, fontSize = 12.sp)
                Row(Modifier.fillMaxWidth().background(Color(0xFF1B2C42), RoundedCornerShape(8.dp)).padding(10.dp)) {
                    Text("BID SIZE", Modifier.weight(1f), color = Color(0xFF58E0C4), fontSize = 10.sp)
                    Text("PRICE", Modifier.weight(1f), color = Color.White, fontSize = 10.sp)
                    Text("ASK SIZE", Modifier.weight(1f), color = Color(0xFFFF8292), fontSize = 10.sp)
                }
                Box(Modifier.fillMaxWidth().height(105.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                        Text("Depth unavailable", color = Color(0xFFFFC76A), style = MaterialTheme.typography.titleMedium)
                        Text("The connected Pocket Option stream currently provides price ticks and OHLC history, not verified Level-2 bid/ask quantities.", color = Color(0xFFB8C8DA), fontSize = 11.sp)
                        Text("No liquidity ladder or imbalance is fabricated.", color = Color(0xFF91A8C5), fontSize = 10.sp)
                    }
                }
            }
        }
    }
}
