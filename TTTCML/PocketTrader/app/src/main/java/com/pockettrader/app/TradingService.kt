package com.pockettrader.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.pockettrader.app.trading.AccountType

/** Keeps the process (and the socket) alive while the autotrader runs. */
class TradingService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            (application as App).engine.emergencyStop()
            stopSelf(); return START_NOT_STICKY
        }
        val account = intent?.getStringExtra(EXTRA_ACCOUNT) ?: "DEMO"
        val stopIntent = android.app.PendingIntent.getService(this, 1,
            Intent(this, TradingService::class.java).setAction(ACTION_STOP),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(this, Notifier.CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Autotrader running · $account")
            .setContentText("Tap to open. Use STOP to halt new trades.")
            .setOngoing(true)
            .setContentIntent(Notifier.openAppIntent(this))
            .addAction(0, "STOP", stopIntent)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(42, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(42, n)
        return START_NOT_STICKY
    }

    companion object {
        private const val ACTION_STOP = "stop"
        private const val EXTRA_ACCOUNT = "account"
        fun start(ctx: Context, account: AccountType) {
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, TradingService::class.java).putExtra(EXTRA_ACCOUNT, account.name))
            } catch (_: Exception) { }
        }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, TradingService::class.java)) }
    }
}
