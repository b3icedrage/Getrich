package com.getrich.deriv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val credentials = SecureCredentials(this)
        val api = DerivApiClient()
        setContent { MaterialTheme { DerivHome(credentials, api) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DerivHome(credentials: SecureCredentials, api: DerivApiClient) {
    var appId by remember { mutableStateOf(credentials.appId()) }
    var token by remember { mutableStateOf(credentials.token()) }
    var model by remember { mutableStateOf(TradingModel.CLASSIC) }
    var status by remember { mutableStateOf("Not connected · Demo-first setup") }
    var accountSummary by remember { mutableStateOf("Connect to load your Deriv options accounts.") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Scaffold(topBar = {
        TopAppBar(title = { Text("DERIV", style = MaterialTheme.typography.headlineSmall) },
            actions = { Text("DEMO FIRST", modifier = Modifier.padding(end = 16.dp), color = MaterialTheme.colorScheme.primary) })
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Your Deriv trading workspace", style = MaterialTheme.typography.headlineMedium)
            Text("Independent app · Separate credentials · Official Deriv API")
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("API connection", style = MaterialTheme.typography.titleLarge)
                    OutlinedTextField(appId, { appId = it }, label = { Text("Deriv App ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(token, { token = it }, label = { Text("Personal Access Token") },
                        visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = !busy, onClick = {
                            credentials.save(appId, token)
                            busy = true
                            status = "Connecting securely…"
                            scope.launch {
                                val result = api.fetchOptionsAccounts(appId, token)
                                result.onSuccess { json ->
                                    status = "API connection successful"
                                    accountSummary = summarizeAccounts(json)
                                }.onFailure { error ->
                                    status = "Connection failed: ${error.message ?: "Unknown error"}"
                                    accountSummary = "Check your App ID, token permissions and internet connection."
                                }
                                busy = false
                            }
                        }) { Text(if (busy) "Connecting…" else "Connect") }
                        OutlinedButton(onClick = {
                            credentials.clear(); token = ""; appId = ""
                            status = "Credentials cleared from this device"
                            accountSummary = "Connect to load your Deriv options accounts."
                        }) { Text("Clear") }
                    }
                    Text(status, color = MaterialTheme.colorScheme.primary)
                    Text(accountSummary, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Trading model", style = MaterialTheme.typography.titleLarge)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        SegmentedButton(selected = model == TradingModel.CLASSIC, onClick = { model = TradingModel.CLASSIC }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("Classic") }
                        SegmentedButton(selected = model == TradingModel.FOUR_AGENT, onClick = { model = TradingModel.FOUR_AGENT }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("Four-Agent") }
                    }
                    if (model == TradingModel.CLASSIC) {
                        Text("Classic uses one shared signal and risk engine. Execution remains disabled until the Deriv trading flow is implemented and tested.")
                    } else {
                        Text("Agent 1 · Scan markets")
                        Text("Agent 2 · Rank opportunities (80+ target)")
                        Text("Agent 3 · Risk approval (hard maximum 3% stake; minimum payout 70%)")
                        Text("Agent 4 · Execute only after approval and final broker validation")
                    }
                }
            }
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Safety status", style = MaterialTheme.typography.titleLarge)
                    Text("Real trading: LOCKED")
                    Text("Automatic execution: OFF")
                    Text("No trades are placed by this initial connection build.")
                    Text("The API token is stored using Android encrypted preferences and is never written to source code.")
                }
            }
            Text("Next: verify your Deriv account list, add live market subscriptions, then implement and test Demo contract proposals and execution.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun summarizeAccounts(raw: String): String = runCatching {
    val root = JSONObject(raw)
    val data = root.optJSONArray("data")
    if (data == null || data.length() == 0) "Connected, but no options accounts were returned: ${raw.take(350)}"
    else buildString {
        append("Found ${data.length()} options account(s):\n")
        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            append("• ")
            append(item.optString("account_type", item.optString("type", "Account")))
            append(" · ")
            append(item.optString("account_id", item.optString("id", "ID not provided")))
            append("\n")
        }
    }
}.getOrElse { "Connected. Account response received: ${raw.take(400)}" }
