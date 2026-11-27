package com.pockettrader.app.ui

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.pockettrader.app.protocol.SessionInfo
import com.pockettrader.app.protocol.SsidParser

/**
 * Wraps window.WebSocket before the page's own scripts run, and reports the
 * 42["auth",{"session":..,"isDemo":..}] frame the platform sends, plus the socket URL.
 * The password never passes through the app: it is typed into pocketoption.com itself.
 */
private const val HOOK_JS = """
(function(){
  if (window.__ptHooked) return; window.__ptHooked = true;
  var Orig = window.WebSocket; if (!Orig) return;
  function Wrapped(url, protocols) {
    var ws = (protocols !== undefined) ? new Orig(url, protocols) : new Orig(url);
    var origSend = ws.send;
    ws.send = function(data) {
      try {
        if (typeof data === 'string' && data.indexOf('42["auth"') === 0 && window.PTBridge) {
          window.PTBridge.onAuth(data, String(url));
        }
      } catch (e) {}
      return origSend.apply(ws, arguments);
    };
    return ws;
  }
  Wrapped.prototype = Orig.prototype;
  Wrapped.CONNECTING = 0; Wrapped.OPEN = 1; Wrapped.CLOSING = 2; Wrapped.CLOSED = 3;
  window.WebSocket = Wrapped;
})();
"""

private const val LOGIN_URL = "https://pocketoption.com/en/login/"
private const val DEMO_URL = "https://pocketoption.com/en/cabinet/demo-quick-high-low/"
private const val REAL_URL = "https://pocketoption.com/en/cabinet/quick-high-low/"

@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun LoginWebViewScreen(onCaptured: (SessionInfo) -> Unit, onClose: () -> Unit) {
    var demoOk by remember { mutableStateOf(false) }
    var realOk by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Sign in below. Then open Demo and/or Real so the app can capture each session.") }
    var webView by remember { mutableStateOf<WebView?>(null) }
    val main = remember { Handler(Looper.getMainLooper()) }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(8.dp)) {
            Text(status)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = {}, label = { Text(if (demoOk) "Demo captured ✓" else "Demo: not yet") })
                AssistChip(onClick = {}, label = { Text(if (realOk) "Real captured ✓" else "Real: not yet") })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { webView?.loadUrl(DEMO_URL) }) { Text("Open Demo") }
                OutlinedButton(onClick = { webView?.loadUrl(REAL_URL) }) { Text("Open Real") }
                Button(onClick = onClose) { Text("Done") }
            }
        }
        AndroidView(modifier = Modifier.fillMaxSize(), factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.databaseEnabled = true
                // Look like normal mobile Chrome (some sign-in providers refuse "; wv").
                settings.userAgentString = settings.userAgentString.replace("; wv", "")
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                val ua = settings.userAgentString
                addJavascriptInterface(object {
                    @JavascriptInterface
                    fun onAuth(frame: String, url: String) {
                        if (!SsidParser.looksLikeUsableAuth(frame)) return
                        val info = try { SsidParser.parse(frame, url, ua) } catch (e: Exception) { return }
                        main.post {
                            onCaptured(info)
                            if (info.isDemo) demoOk = true else realOk = true
                            status = "Captured ${info.describe()}. " + if (!demoOk || !realOk) "Open the other account too if you want both." else "Both captured, tap Done."
                        }
                    }
                }, "PTBridge")
                val docStart = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
                if (docStart) {
                    WebViewCompat.addDocumentStartJavaScript(this, HOOK_JS, setOf("*"))
                }
                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                        // Fallback for old WebView versions: inject as early as possible.
                        if (!docStart) view.evaluateJavascript(HOOK_JS, null)
                    }
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = false
                }
                if (!docStart) status = "Old Android System WebView: capture may miss the session. Update WebView or use Paste."
                loadUrl(LOGIN_URL)
                webView = this
            }
        })
    }
}
