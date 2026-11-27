package com.pockettrader.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.pockettrader.app.trading.AccountType

/** Keeps the authenticated market connection alive while the screen is off. */
class TradingService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PocketTrader:MarketConnection").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val engine = (application as App).engine
        if (intent?.action == ACTION_STOP_AUTO) {
            engine.emergencyStop()
            updateNotification("Background market monitoring continues · Auto Trade OFF")
            return START_STICKY
        }
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val account = intent?.getStringExtra(EXTRA_ACCOUNT)
            ?: prefs.getString(EXTRA_ACCOUNT, AccountType.DEMO.name)
            ?: AccountType.DEMO.name
        prefs.edit().putString(EXTRA_ACCOUNT, account).apply()

        startForegroundNow("PocketTrader active · $account", "Market connection and signal scanning running")
        engine.resumeBackgroundConnection(account)
        return START_STICKY
    }

    private fun startForegroundNow(title: String, message: String) {
        val n = buildNotification(title, message)
        if (Build.VERSION.SDK_INT >= 29) startForeground(42, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(42, n)
    }

    private fun updateNotification(message: String) {
        val account = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(EXTRA_ACCOUNT, AccountType.DEMO.name) ?: AccountType.DEMO.name
        val nm = getSystemService(android.app.NotificationManager::class.java)
        nm.notify(42, buildNotification("PocketTrader active · $account", message))
    }

    private fun buildNotification(title: String, message: String) =
        NotificationCompat.Builder(this, Notifier.CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$message. Disconnect in PocketTrader to stop background monitoring."))
            .setOngoing(true)
            .setContentIntent(Notifier.openAppIntent(this))
            .addAction(0, "STOP AUTO", android.app.PendingIntent.getService(
                this, 1, Intent(this, TradingService::class.java).setAction(ACTION_STOP_AUTO),
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT))
            .build()

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        private const val ACTION_STOP_AUTO = "stop_auto"
        private const val EXTRA_ACCOUNT = "account"
        private const val PREFS = "pockettrader_background"
        fun start(ctx: Context, account: AccountType) {
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, TradingService::class.java).putExtra(EXTRA_ACCOUNT, account.name))
            } catch (_: Exception) { }
        }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, TradingService::class.java)) }
    }
}
