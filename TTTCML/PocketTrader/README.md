# PocketTrader (Android)

Unofficial Pocket Option client: manual trading, Demo/Real switch, strategy signals and an
autotrader with risk limits. Kotlin + Jetpack Compose + OkHttp WebSocket, minSdk 24.

**Risk:** Pocket Option has no official API. This app speaks the same socket.io protocol as the
website; it can break whenever they change it, and their terms may treat it as a bot. Signals are
strategy-based and NOT guaranteed. Start on Demo.

## Open and build
1. Install Android Studio (Koala or newer) with JDK 17.
2. File > Open > select this folder. Let Gradle sync (it downloads Gradle 8.7 and dependencies).
3. Run > Run 'app' on a phone/emulator, or Build > Build APK(s).
   Command line: `./gradlew assembleDebug` -> `app/build/outputs/apk/debug/app-debug.apk`
   Unit tests: `./gradlew testDebugUnitTest`
(Android Studio creates `local.properties` with your SDK path automatically.)

## Signing in
- Account tab > "Sign in with Pocket Option": log in on pocketoption.com inside the app, then tap
  "Open Demo" and "Open Real". The app captures the `42["auth",{"session":...}]` frame the site
  sends (by wrapping window.WebSocket before page scripts run) and stores it encrypted
  (EncryptedSharedPreferences). Your password goes only to pocketoption.com.
- Fallback: paste the auth message from browser dev tools (instructions in the app).
- Demo and Real use different sessions. Real sessions are bound to IP + User-Agent.

## Code map
- `protocol/` socket.io codec (SocketIo.kt), SSID parsing + servers (Ssid.kt), payload parsers (Models.kt), WebSocket client (PocketClient.kt)
- `signals/` indicators (EMA, SMA, RSI, MACD, Bollinger, ATR, ADX, Donchian, Supertrend), candle aggregator, strategies, signal engine
- `trading/` risk manager, trading engine (orders, results, autotrader)
- `ui/` Compose screens, WebView login
- Tests: `app/src/test/...` (protocol, indicators, strategies, risk, mock-server client flow)

## Strategies
Auto (ADX regime: >25 trend/breakout, <20 mean reversion, between = no trade), Multi-timeframe trend
(higher-TF EMA200 + MACD/Supertrend cross after pullback), Mean reversion (BB + RSI 30/70),
Volatility breakout (Donchian + tick-volume 1.5x avg; tick count is a volume proxy because
Pocket Option streams no volume), Liquidity sweep, simple EMA cross + RSI.
Not possible with binary options: grid/DCA, pairs trading, funding arbitrage.

## Protocol sources
- ChipaDevTeam/BinaryOptionsTools-v2 (Rust): ssid.rs, connect.rs, regions json, modules/keep_alive.rs, trades.rs, deals.rs, balance.rs, assets.rs, subscriptions.rs, get_candles.rs, types.rs
- ChipaDevTeam/PocketOptionAPI (python): client.py, issue #4 (openOrder frame)
- BinaryOptionsTools-v2 discussion #87 (correct SSID format, demo vs real)
