# Deriv (Android)

A standalone Deriv account app inside the Getrich repository. It is independent of PocketTrader.

## Current foundation
- Kotlin + Jetpack Compose Android app (package: com.getrich.deriv).
- Uses Deriv's official REST API to list options accounts.
- Personal Access Token and App ID are entered by the user and stored locally using Android encrypted preferences.
- Classic and Four-Agent model selection UI.
- Four-Agent approval pipeline: scanner → analyst → risk manager → execution gate.
- Four-Agent risk ceiling: 3% of account balance per trade; initial opportunity target 80+; payout floor 70%.
- Real trading locked and automatic execution off. No orders are placed in this foundation build.

## Credentials
Create a Deriv API application and a Personal Access Token (PAT) with only the required permissions. The PAT flow requires the Deriv-App-ID header. Never commit tokens, put them in Gradle files, or share them in chat. Revoke a token immediately if it is exposed.

The app currently expects a PAT and its corresponding Deriv App ID. It calls:
https://api.derivws.com/trading/v1/options/accounts

## Build
Build with JDK 17, Android SDK 34 and Gradle 8.7:
gradle assembleDebug
APK output: app/build/outputs/apk/debug/app-debug.apk

## Planned implementation stages
1. Confirm PAT/App ID and retrieve the user's options account list.
2. Add authenticated OTP WebSocket sessions for Demo and Real accounts.
3. Add live symbols, ticks, candles, balance, portfolio and contract proposals.
4. Implement Classic signals and Demo-only execution with strict validation and duplicate protection.
5. Wire Four-Agent automatic execution only after Agent 3 approval; keep the 3% hard cap.
6. Add trade journal, per-model analytics, background service and robust reconnect handling.
7. Keep Real mode locked until Demo validation and explicit user approval.

## Important
The current foundation is a connection/UI scaffold, not a finished trading bot. The model labels and risk gate do not imply that live signals or order execution are implemented yet. Trading can lose money; no win rate or profit is guaranteed.
