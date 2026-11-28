package com.pockettrader.app.signals

import com.pockettrader.app.protocol.Candle
import com.pockettrader.app.protocol.Direction
import kotlin.math.abs
import kotlin.math.roundToInt

data class MultiTimeframeScore(
    val callScore: Int,
    val putScore: Int,
    val recommendation: Direction?,
    val explanation: String,
    val callBreakdown: Map<String, Int>,
    val putBreakdown: Map<String, Int>
)

/** Directional technical agreement score, never a win probability. */
object MultiTimeframeConfluence {
    fun analyze(m1: List<Candle>, m5: List<Candle>, m30: List<Candle>, h1: List<Candle>, isCrypto: Boolean, minScore: Int): MultiTimeframeScore {
        val empty = MultiTimeframeScore(0, 0, null, "Waiting for sufficient closed M1/M5/M30/H1 history", emptyMap(), emptyMap())
        if (m1.size < 55 || m5.size < 55 || m30.size < 55 || h1.size < 205) return empty
        fun score(direction: Direction): Pair<Int, Map<String, Int>> {
            val sign = if (direction == Direction.CALL) 1 else -1
            fun agrees(v: Double) = v.isFinite() && if (sign > 0) v > 0.0 else v < 0.0
            fun scale(raw: Int, base: Int, target: Int) = (raw.toDouble() * target / base).roundToInt()
            val parts = linkedMapOf<String, Int>()

            run {
                val c=h1.map { it.close }; val hi=h1.map { it.high }; val lo=h1.map { it.low }; val n=c.lastIndex
                val e50=Indicators.ema(c,50); val e200=Indicators.ema(c,200); val adx=Indicators.adx(hi,lo,c,14)
                val recent=h1.takeLast(10); val first=recent.take(5); val last=recent.takeLast(5)
                val structure=if(sign>0) last.maxOf { it.high }>first.maxOf { it.high } && last.minOf { it.low }>first.minOf { it.low }
                    else last.minOf { it.low }<first.minOf { it.low } && last.maxOf { it.high }<first.maxOf { it.high }
                val raw=(if(agrees(e50[n]-e200[n])) 8 else 0)+(if(agrees(c[n]-e50[n])) 5 else 0)+
                    (if(adx.adx[n].isFinite() && adx.adx[n]>=20.0 && agrees(adx.plusDi[n]-adx.minusDi[n])) 4 else 0)+(if(structure) 3 else 0)
                parts["H1"]=if(isCrypto) scale(raw,20,17) else raw
            }
            run {
                val c=m30.map { it.close }; val hi=m30.map { it.high }; val lo=m30.map { it.low }; val n=c.lastIndex
                val e20=Indicators.ema(c,20); val e50=Indicators.ema(c,50); val macd=Indicators.macd(c).histogram; val adx=Indicators.adx(hi,lo,c,14)
                val raw=(if(agrees(e20[n]-e50[n])) 5 else 0)+(if(agrees(c[n]-e50[n])) 4 else 0)+
                    (if(agrees(macd[n])) 3 else 0)+(if(adx.adx[n].isFinite() && adx.adx[n]>=16.0 && agrees(adx.plusDi[n]-adx.minusDi[n])) 3 else 0)
                parts["M30"]=if(isCrypto) scale(raw,15,13) else raw
            }
            run {
                val c=m5.map { it.close }; val e20=Indicators.ema(c,20); val hi=m5.map { it.high }; val lo=m5.map { it.low }; val n=c.lastIndex
                val touched=(maxOf(0,n-10)..(n-1)).any { i -> e20[i].isFinite() && if(sign>0) m5[i].low<=e20[i] else m5[i].high>=e20[i] }
                val candle=m5.last(); val range=candle.high-candle.low
                val rejection=range>0.0 && if(sign>0) candle.close>candle.open && (candle.close-candle.low)/range>=0.55
                    else candle.close<candle.open && (candle.high-candle.close)/range>=0.55
                val rsi=Indicators.rsi(c,14)[n]; val rsiOk=if(sign>0) rsi in 50.0..68.0 else rsi in 32.0..50.0
                val macd=Indicators.macd(c).histogram[n]; val adx=Indicators.adx(hi,lo,c,14).adx[n]
                val raw=(if(touched) 7 else 0)+(if(rejection) 6 else 0)+(if(rsiOk) 4 else 0)+
                    (if(agrees(macd)) 4 else 0)+(if(adx.isFinite() && adx>=16.0) 4 else 0)
                parts["M5"]=if(isCrypto) scale(raw,25,22) else raw
            }
            run {
                val c=m1.map { it.close }; val hi=m1.map { it.high }; val lo=m1.map { it.low }; val macd=Indicators.macd(c).histogram; val n=c.lastIndex
                val cross=(maxOf(1,n-2)..n).any { i -> macd[i-1].isFinite() && macd[i].isFinite() &&
                    if(sign>0) macd[i-1]<=0.0 && macd[i]>0.0 else macd[i-1]>=0.0 && macd[i]<0.0 }
                val candle=m1.last(); val range=candle.high-candle.low; val body=abs(candle.close-candle.open)
                val candleOk=range>0.0 && if(sign>0) candle.close>candle.open else candle.close<candle.open
                val rsi=Indicators.rsi(c,14)[n]; val rsiOk=if(sign>0) rsi in 50.0..70.0 else rsi in 30.0..50.0
                val wick=if(sign>0) minOf(candle.open,candle.close)-candle.low else candle.high-maxOf(candle.open,candle.close)
                val wickOk=range>0.0 && body/range>=0.25 && wick/range<=0.55
                val atr=Indicators.atr(hi,lo,c,14)[n]; val atrOk=atr.isFinite() && atr>0.0 && range<=atr*2.2
                val raw=(if(cross) 12 else 0)+(if(candleOk) 10 else 0)+(if(rsiOk) 6 else 0)+(if(wickOk) 5 else 0)+(if(atrOk) 7 else 0)
                parts["M1"]=if(isCrypto) scale(raw,40,33) else raw
            }
            if(isCrypto) parts["CRYPTO_VWAP_ATR"]=if(CryptoVwapAtrFilter.rejection(m1,direction)==null) 15 else 0
            return parts.values.sum().coerceIn(0,100) to parts.toMap()
        }
        val (call,callParts)=score(Direction.CALL); val (put,putParts)=score(Direction.PUT)
        val edge=abs(call-put); val best=maxOf(call,put)
        val recommendation=when { best<minScore || edge<10 -> null; call>put -> Direction.CALL; else -> Direction.PUT }
        val explanation="H1 \${callParts["H1"] ?: 0}/\${if(isCrypto) 17 else 20}, M30 \${callParts["M30"] ?: 0}/\${if(isCrypto) 13 else 15}, M5 \${callParts["M5"] ?: 0}/\${if(isCrypto) 22 else 25}, M1 \${callParts["M1"] ?: 0}/\${if(isCrypto) 33 else 40}" +
            if(isCrypto) ", Crypto VWAP/ATR \${callParts["CRYPTO_VWAP_ATR"] ?: 0}/15" else ""
        return MultiTimeframeScore(call,put,recommendation,explanation,callParts,putParts)
    }
}
