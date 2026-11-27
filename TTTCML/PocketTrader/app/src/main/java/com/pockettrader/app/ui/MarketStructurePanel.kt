package com.pockettrader.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pockettrader.app.protocol.Candle
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private enum class MarketPanelTab { CHART, DOM }

@Composable
fun MarketStructurePanel(candles: List<Candle>, asset: String, timeframeSec: Int) {
    var selected by remember { mutableStateOf(MarketPanelTab.CHART) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableFloatStateOf(0f) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF101D30)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected == MarketPanelTab.CHART, { selected = MarketPanelTab.CHART }, label = { Text("Vela chart") })
                FilterChip(selected == MarketPanelTab.DOM, { selected = MarketPanelTab.DOM }, label = { Text("DOM ladder") })
            }
            if (selected == MarketPanelTab.DOM) {
                Text("DEPTH OF MARKET · $asset", color = Color.White, fontSize = 13.sp)
                Text("Depth unavailable", color = Color(0xFFFFC76A), fontSize = 18.sp)
                Text(
                    "The connected Pocket Option stream currently provides ticks and OHLC candles, not verified bid/ask order-book levels or resting-order sizes. Liquidity imbalance cannot be calculated. No synthetic depth is displayed.",
                    color = Color(0xFFB7C7DA), fontSize = 12.sp
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("PRICE", color = Color(0xFF91A8C5), fontSize = 10.sp)
                    Text("BID SIZE", color = Color(0xFF91A8C5), fontSize = 10.sp)
                    Text("ASK SIZE", color = Color(0xFF91A8C5), fontSize = 10.sp)
                }
                repeat(5) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("—", color = Color.Gray)
                        Text("Unavailable", color = Color.Gray, fontSize = 11.sp)
                        Text("Unavailable", color = Color.Gray, fontSize = 11.sp)
                    }
                }
            } else {
                Text("$asset · ${timeframeLabel(timeframeSec)} · OHLC", color = Color.White, fontSize = 13.sp)
                Text("Pinch to zoom · drag to move through candles", color = Color(0xFF91A8C5), fontSize = 10.sp)
                if (candles.size < 20) {
                    Text("Waiting for at least 20 genuine OHLC candles.", color = Color(0xFFB7C7DA), fontSize = 12.sp)
                } else {
                    val count = (candles.size / zoom).toInt().coerceIn(20, min(180, candles.size))
                    val end = (candles.size - pan.toInt()).coerceIn(count, candles.size)
                    val visible = candles.subList(end - count, end)
                    val ema20 = ema(visible.map { it.close }, 20)
                    val ema50 = ema(visible.map { it.close }, 50)
                    Canvas(
                        Modifier.fillMaxWidth().height(210.dp)
                            .pointerInput(candles.size) {
                                detectTransformGestures { _, panChange, zoomChange, _ ->
                                    zoom = (zoom * zoomChange).coerceIn(0.35f, 4f)
                                    pan = (pan - panChange.x / 8f).coerceIn(0f, (candles.size - 20).toFloat())
                                }
                            }
                    ) {
                        val lo = visible.minOf { it.low }
                        val hi = visible.maxOf { it.high }
                        val range = (hi - lo).takeIf { it > 0.0 } ?: 1.0
                        val step = size.width / visible.size
                        val body = (step * 0.58f).coerceAtLeast(1.5f)
                        fun y(value: Double) = size.height - ((value - lo) / range * size.height).toFloat()
                        for (grid in 1..4) {
                            val gy = size.height * grid / 5f
                            drawLine(Color(0xFF26364D), Offset(0f, gy), Offset(size.width, gy), 1f)
                        }
                        visible.forEachIndexed { i, candle ->
                            val x = step * (i + 0.5f)
                            val rising = candle.close >= candle.open
                            val tint = if (rising) Color(0xFF35D0A2) else Color(0xFFE45C72)
                            drawLine(tint, Offset(x, y(candle.high)), Offset(x, y(candle.low)), 1.5f)
                            val top = min(y(candle.open), y(candle.close))
                            val height = max(2f, abs(y(candle.open) - y(candle.close)))
                            drawRect(tint, Offset(x - body / 2f, top), Size(body, height))
                        }
                        fun drawAverage(values: List<Double>, tint: Color) {
                            if (values.size < 2) return
                            val path = Path()
                            values.forEachIndexed { i, value ->
                                val x = step * (i + 0.5f)
                                if (i == 0) path.moveTo(x, y(value)) else path.lineTo(x, y(value))
                            }
                            drawPath(path, tint, style = Stroke(2f))
                        }
                        drawAverage(ema20, Color(0xFFFFC857))
                        drawAverage(ema50, Color(0xFF65A8FF))
                    }
                    val rsi = rsi(visible.map { it.close }, 14)
                    Text("EMA 20  •  EMA 50  •  RSI 14", color = Color(0xFFB7C7DA), fontSize = 10.sp)
                    if (rsi != null) {
                        Text("RSI 14 · ${"%.1f".format(rsi)}", color = Color(0xFFB7C7DA), fontSize = 11.sp)
                        Canvas(Modifier.fillMaxWidth().height(54.dp)) {
                            val y70 = size.height * 0.3f
                            val y30 = size.height * 0.7f
                            drawLine(Color(0xFF8A6D3B), Offset(0f, y70), Offset(size.width, y70), 1f)
                            drawLine(Color(0xFF8A6D3B), Offset(0f, y30), Offset(size.width, y30), 1f)
                            val y = size.height * (1f - (rsi / 100f))
                            drawLine(Color(0xFFB58CFF), Offset(0f, y), Offset(size.width, y), 2.5f)
                        }
                    }
                    Text("OHLC is broker history. RSI/EMA are calculated locally. This feed does not provide exchange-traded volume.", color = Color(0xFF91A8C5), fontSize = 10.sp)
                }
            }
        }
    }
}

private fun timeframeLabel(seconds: Int): String = when {
    seconds < 60 -> "${seconds}s"
    seconds < 3600 -> "${seconds / 60}m"
    else -> "${seconds / 3600}h"
}

private fun ema(values: List<Double>, period: Int): List<Double> {
    if (values.isEmpty()) return emptyList()
    val alpha = 2.0 / (period + 1.0)
    val result = ArrayList<Double>(values.size)
    var current = values.first()
    values.forEachIndexed { index, value ->
        current = if (index == 0) value else alpha * value + (1.0 - alpha) * current
        result += current
    }
    return result
}

private fun rsi(values: List<Double>, period: Int): Double? {
    if (values.size <= period) return null
    var gains = 0.0
    var losses = 0.0
    for (i in 1..period) {
        val change = values[i] - values[i - 1]
        if (change > 0) gains += change else losses -= change
    }
    var avgGain = gains / period
    var avgLoss = losses / period
    for (i in period + 1 until values.size) {
        val change = values[i] - values[i - 1]
        avgGain = (avgGain * (period - 1) + max(0.0, change)) / period
        avgLoss = (avgLoss * (period - 1) + max(0.0, -change)) / period
    }
    return if (avgLoss == 0.0) 100.0 else 100.0 - 100.0 / (1.0 + avgGain / avgLoss)
}
