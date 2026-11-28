package com.getrich.grok

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.coroutines.resume

private const val DEFAULT_URL = "https://pocketoption.com/en/cabinet/demo/"
private const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CookieManager.getInstance().setAcceptCookie(true)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { GrokScreen() } }
    }

    override fun onPause() {
        CookieManager.getInstance().flush()
        super.onPause()
    }
}

private data class PendingAction(val action: VisionAction, val screenshot: Bitmap, val reason: String)

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun GrokScreen() {
    val context = LocalContext.current
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val viewportScale = ((screenWidthDp - 20).coerceAtLeast(320)).toFloat() / 1280f
    val webViewState = remember { mutableStateOf<WebView?>(null) }
    val service = remember { NvidiaVisionService() }
    var url by remember { mutableStateOf("https://pocketoption.com/en/cabinet/demo/") }
    var apiKey by remember { mutableStateOf("") }
    var savedKey by remember { mutableStateOf("") }
    var strategy by remember { mutableStateOf("Analyze the visible market chart. Identify trend, structure, momentum, nearby support/resistance and uncertainty. Prefer WAIT when the setup is unclear. Never submit an order.") }
    var interval by remember { mutableIntStateOf(5) }
    var aiMode by remember { mutableStateOf(false) }
    var logs by remember { mutableStateOf(listOf("GROK ready. Open Pocket Option Demo and sign in manually.")) }
    var pending by remember { mutableStateOf<PendingAction?>(null) }
    var busy by remember { mutableStateOf(false) }
    var lastActionAt by remember { mutableLongStateOf(0L) }

    fun log(message: String) {
        val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        logs = (logs + "[$stamp] $message").takeLast(80)
    }

    LaunchedEffect(Unit) { savedKey = GrokSecureStore.readApiKey(context) }

    fun openTarget() {
        val parsed = Uri.parse(url.trim())
        if (parsed.scheme !in listOf("https", "http") || parsed.host.isNullOrBlank()) {
            log("Blocked URL. Use a valid http:// or https:// address.")
            return
        }
        webViewState.value?.loadUrl(parsed.toString())
        log("Opening " + parsed.host)
    }

    suspend fun evaluate(web: WebView, script: String): String =
        suspendCancellableCoroutine { continuation ->
            web.evaluateJavascript(script) { result ->
                if (continuation.isActive) continuation.resume(result ?: "null")
            }
        }

    fun decodeJs(raw: String): JSONObject = try {
        val value = JSONTokener(raw).nextValue()
        JSONObject(value as? String ?: "{}")
    } catch (_: Exception) {
        JSONObject().put("ok", false).put("reason", "Could not read page response.")
    }

    fun screenshot(web: WebView): Bitmap? {
        if (web.width <= 0 || web.height <= 0) return null
        return try {
            val actual = Bitmap.createBitmap(web.width, web.height, Bitmap.Config.ARGB_8888)
            web.draw(Canvas(actual))
            Bitmap.createScaledBitmap(actual, 1280, 800, true).also { if (it !== actual) actual.recycle() }
        } catch (_: Exception) { null }
    }

    fun vibrate() {
        try {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (Build.VERSION.SDK_INT >= 26) vibrator.vibrate(VibrationEffect.createOneShot(450, VibrationEffect.DEFAULT_AMPLITUDE))
            else vibrator.vibrate(450L)
        } catch (_: Exception) { }
    }

    LaunchedEffect(aiMode, interval, savedKey, strategy) {
        if (aiMode) {
            if (!savedKey.startsWith("nvapi-")) {
                log("AI mode stopped: save a valid NVIDIA nvapi- key first.")
                aiMode = false
            } else {
                log("AI Agent Mode active. Checks every " + interval + " minutes.")
                while (aiMode) {
                    delay(interval * 60_000L)
                    val web = webViewState.value
                    if (web == null || busy || pending != null) {
                        log("Scan skipped: browser unavailable or previous action needs review.")
                        continue
                    }
                    val shot = screenshot(web)
                    if (shot == null) {
                        log("Could not capture visible browser. Keep GROK on screen.")
                        continue
                    }
                    busy = true
                    try {
                        log("Sending chart screenshot to NVIDIA Nemotron Vision…")
                        val decision = service.analyze(savedKey, shot, strategy)
                        log("AI: " + decision.action.uppercase() + " — " + decision.reason)
                        when (decision.action) {
                            "wait" -> log("No browser action taken.")
                            "type" -> {
                                if (decision.isOrderSubmission) {
                                    pending = PendingAction(decision, shot, decision.reason)
                                    aiMode = false
                                    vibrate()
                                    log("Model flagged this as an order-related action. Paused before typing.")
                                } else {
                                    val preflight = decodeJs(evaluate(web, WebActionInjector.inspectType(decision)))
                                    if (preflight.optBoolean("orderRelated")) {
                                        pending = PendingAction(decision, shot, preflight.optString("reason", decision.reason))
                                        aiMode = false
                                        vibrate()
                                        log("Order-related field detected. Paused before typing.")
                                    } else if (preflight.optBoolean("blocked")) {
                                        log("Typing blocked: " + preflight.optString("reason", "Unsafe input field."))
                                    } else {
                                        val elapsed = SystemClock.elapsedRealtime() - lastActionAt
                                        if (elapsed < 2_000L) delay(2_000L - elapsed)
                                        val result = decodeJs(evaluate(web, WebActionInjector.type(decision)))
                                        lastActionAt = SystemClock.elapsedRealtime()
                                        log(result.optString("reason", "Type action processed."))
                                    }
                                }
                            }
                            "click" -> {
                                val elapsed = SystemClock.elapsedRealtime() - lastActionAt
                                if (elapsed < 2_000L) delay(2_000L - elapsed)
                                val preflight = decodeJs(evaluate(web, WebActionInjector.inspectClick(decision)))
                                lastActionAt = SystemClock.elapsedRealtime()
                                if (decision.isOrderSubmission || preflight.optBoolean("blocked")) {
                                    pending = PendingAction(decision, shot, preflight.optString("reason", decision.reason))
                                    aiMode = false
                                    vibrate()
                                    log("Potential order control detected. AI paused; approval required.")
                                } else {
                                    val result = decodeJs(evaluate(web, WebActionInjector.click(decision)))
                                    log(result.optString("reason", "Click processed."))
                                }
                            }
                        }
                    } catch (e: Exception) {
                        log("AI scan error: " + (e.message ?: "Unknown error"))
                    } finally {
                        busy = false
                    }
                }
            }
        } else {
            log("Manual Mode. AI monitoring is paused.")
        }
    }

    fun approvePending() {
        val item = pending ?: return
        val web = webViewState.value
        if (web == null) {
            log("Approval expired: browser is unavailable.")
            pending = null
            return
        }
        val script = when (item.action.action) {
            "click" -> WebActionInjector.approvedClick(item.action)
            "type" -> WebActionInjector.type(item.action)
            else -> null
        }
        if (script == null) log("No executable action to approve.")
        else web.evaluateJavascript(script) { raw ->
            val result = decodeJs(raw ?: "null")
            log("Human approval: " + result.optString("reason", "Action processed."))
        }
        pending = null
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Column {
                Text("GROK", style = MaterialTheme.typography.headlineMedium)
                Text("Desktop chart browser • Human-approved actions", style = MaterialTheme.typography.bodySmall)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (aiMode) "AI Agent" else "Manual", style = MaterialTheme.typography.labelMedium)
                Switch(checked = aiMode, onCheckedChange = { enabled ->
                    if (enabled && !savedKey.startsWith("nvapi-")) log("Save your NVIDIA API key before enabling AI mode.")
                    else aiMode = enabled
                })
            }
        }
        OutlinedTextField(value = url, onValueChange = { url = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Target website") }, singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { openTarget() }) { Text("Open website") }
            Text("Interval:", style = MaterialTheme.typography.labelMedium)
            FilterChip(selected = interval == 5, onClick = { interval = 5 }, label = { Text("5 min") })
            FilterChip(selected = interval == 15, onClick = { interval = 15 }, label = { Text("15 min") })
        }
        OutlinedTextField(value = apiKey, onValueChange = { apiKey = it }, modifier = Modifier.fillMaxWidth(),
            label = { Text("NVIDIA API key (nvapi-…)") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                if (apiKey.trim().startsWith("nvapi-")) {
                    GrokSecureStore.saveApiKey(context, apiKey.trim())
                    savedKey = apiKey.trim()
                    apiKey = ""
                    log("NVIDIA key saved in encrypted on-device storage.")
                } else log("Key not saved. It must begin with nvapi-.")
            }) { Text("Save key securely") }
            OutlinedButton(onClick = {
                GrokSecureStore.saveApiKey(context, "")
                savedKey = ""
                apiKey = ""
                aiMode = false
                log("Stored NVIDIA key cleared.")
            }) { Text("Clear key") }
        }
        Text(if (savedKey.isNotBlank()) "NVIDIA key: saved on this device" else "NVIDIA key: not configured", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(value = strategy, onValueChange = { strategy = it }, modifier = Modifier.fillMaxWidth(),
            label = { Text("Your chart strategy prompt") }, minLines = 2, maxLines = 4)
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
            Column(modifier = Modifier.padding(8.dp)) {
                Text("Desktop browser • 1280 × 800 logical chart view", style = MaterialTheme.typography.labelLarge)
                Box(
                    modifier = Modifier.fillMaxWidth().height((viewportScale * 800f).dp).clipToBounds()
                ) {
                AndroidView(
                    modifier = Modifier.width(1280.dp).height(800.dp).graphicsLayer {
                        scaleX = viewportScale
                        scaleY = viewportScale
                        transformOrigin = TransformOrigin(0f, 0f)
                    },
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.databaseEnabled = true
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = true
                            settings.setSupportZoom(true)
                            settings.builtInZoomControls = true
                            settings.displayZoomControls = false
                            settings.userAgentString = DESKTOP_UA
                            setInitialScale(100)
                            webViewClient = WebViewClient()
                            webChromeClient = WebChromeClient()
                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            webViewState.value = this
                            loadUrl(DEFAULT_URL)
                        }
                    },
                    update = { webViewState.value = it }
                )
                }
            }
        }
        Text("Manual login, 2FA and CAPTCHA are completed by you. AI will not fill password or OTP fields.", style = MaterialTheme.typography.bodySmall)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("Live activity log", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { logs = emptyList() }) { Text("Clear") }
                }
                LazyColumn(modifier = Modifier.heightIn(max = 170.dp)) {
                    items(logs.reversed()) { entry -> Text(entry, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp)) }
                }
            }
        }
        Text("Safety: GROK pauses on order-related controls for your approval. AI can misread charts; use Demo only.", style = MaterialTheme.typography.bodySmall)
    }

    pending?.let { item ->
        AlertDialog(
            onDismissRequest = { pending = null; log("Staged action rejected by dismissing the dialog.") },
            title = { Text("Human approval required") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("GROK paused because this action may interact with an order control.")
                    Text("Reason: " + item.reason, style = MaterialTheme.typography.bodySmall)
                    Image(bitmap = item.screenshot.asImageBitmap(), contentDescription = "Captured browser screenshot", modifier = Modifier.fillMaxWidth().height(190.dp))
                    Text("Review the screenshot and current page carefully. Approval authorizes one staged page action.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Button(onClick = { approvePending() }) {
                    Text(if (item.action.action == "click") "Approve & Click" else "Approve & Type")
                }
            },
            dismissButton = { TextButton(onClick = { pending = null; log("Staged action rejected.") }) { Text("Reject") } }
        )
    }
}
