package com.pockettrader.app

import android.app.Application
import com.pockettrader.app.trading.TradingEngine

class App : Application() {
    val engine: TradingEngine by lazy { TradingEngine(this) }

    override fun onCreate() {
        super.onCreate()
        Notifier.createChannels(this)
    }
}
