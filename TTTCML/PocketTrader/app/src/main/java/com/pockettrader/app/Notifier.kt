package com.pockettrader.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.pockettrader.app.signals.Signal
import com.pockettrader.app.trading.TradeRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Notifier {
    const val CH_SIGNALS = "signals"
    const val CH_RESULTS = "results"
    const val CH_SERVICE = "service"

    fun createChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_SIGNALS, "Signals", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_RESULTS, "Trade results", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(CH_SERVICE, "Autotrader running", NotificationManager.IMPORTANCE_LOW))
    }

    fun openAppIntent(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun canPost(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private val hm = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun signal(ctx: Context, s: Signal) {
        if (!canPost(ctx)) return
        val n = NotificationCompat.Builder(ctx, CH_SIGNALS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("${s.direction.label.uppercase()} ${s.asset} · ${s.expirySec / 60}m")
            .setContentText("Enter at ${hm.format(Date(s.entryAtSec * 1000))} · ${s.strategy.label} (strategy-based, not guaranteed)")
            .setStyle(NotificationCompat.BigTextStyle().bigText("${s.reason}\nEntry ${hm.format(Date(s.entryAtSec * 1000))}, expiry ${s.expirySec}s. Strategy-based signal, not guaranteed."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setTimeoutAfter(s.expirySec * 1000L)
            .setContentIntent(openAppIntent(ctx))
            .setAutoCancel(true)
            .build()
        try { NotificationManagerCompat.from(ctx).notify(1000 + (s.id % 1000).toInt(), n) } catch (_: SecurityException) {}
    }

    fun result(ctx: Context, t: TradeRecord) {
        if (!canPost(ctx)) return
        val n = NotificationCompat.Builder(ctx, CH_RESULTS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("${t.status} ${t.asset} ${"%+.2f".format(t.netProfit ?: 0.0)} (${if (t.isDemo) "demo" else "REAL"})")
            .setContentText("${t.direction.label} ${"%.2f".format(t.amount)} · ${t.source}")
            .setContentIntent(openAppIntent(ctx))
            .setAutoCancel(true)
            .build()
        try { NotificationManagerCompat.from(ctx).notify(5000 + (t.placedAtMs % 1000).toInt(), n) } catch (_: SecurityException) {}
    }
}
