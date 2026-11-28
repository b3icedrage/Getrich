# GROK for Android

GROK is a personal desktop-style browser with optional NVIDIA Nemotron vision chart analysis. It defaults to Pocket Option Demo.

## Code map
- app/src/main/java/com/getrich/grok/MainActivity.kt — Compose UI, desktop WebView, monitoring loop and approval dialog.
- NvidiaVisionService.kt — NVIDIA NIM vision request and JSON parsing.
- WebActionInjector.kt — constrained page click/type helpers and order-control checks.
- GrokSecureStore.kt — encrypted on-device NVIDIA key storage.
- .github/workflows/grok-android.yml — independent APK build.

## Requirements
- Android 7.0+ (API 24); Android 10+ recommended.
- JDK 17 and Android SDK 34 for local builds.
- NVIDIA API key with access to nvidia/nemotron-3.5-super-vl-120b-a12b.

## Build locally
From this directory run:
    gradle assembleDebug
APK: app/build/outputs/apk/debug/app-debug.apk

## Build from your phone
1. Open the repository Actions tab.
2. Select GROK Android Build and tap Run workflow.
3. Download the GROK-debug-apk artifact after success.

## First run
1. Install GROK and keep Manual Mode selected.
2. Open Pocket Option Demo and sign in yourself. Complete 2FA/CAPTCHA yourself.
3. Enter your NVIDIA API key in the masked field and tap Save key securely.
4. Set your chart strategy prompt and choose 5 or 15 minutes.
5. Enable AI Agent Mode. Keep the app in the foreground and device awake during testing.

## Safety and limitations
- Demo-first research prototype, not a broker-certified trading client.
- Does not call a broker trading API; it operates within the loaded website.
- AI typing into password, OTP, 2FA and payment/security fields is blocked.
- Order-related controls (Buy, Sell, Order, Confirm, Submit, Place Order, Call, Put, Higher, Lower) trigger a screenshot approval dialog and pause the agent. Approval authorizes one staged page action only.
- Browser text can be misleading or hidden; no client-side keyword guard guarantees detection of every order control. Review every screenshot and use Demo only.
- NVIDIA free-tier access and limits depend on your account; free access is not guaranteed unlimited.
- Android may pause background WebView rendering. Keep GROK in the foreground.
- Trading sites may restrict desktop spoofing or embedded browsers; verify login/session behavior on-device.
