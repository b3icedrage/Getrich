package com.pockettrader.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import com.pockettrader.app.ui.AppRoot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        window.statusBarColor = android.graphics.Color.rgb(10, 18, 32)
        window.navigationBarColor = android.graphics.Color.rgb(10, 18, 32)
        val engine = (application as App).engine
        val pocketColors = darkColorScheme(
            primary = Color(0xFF35D0BA),
            onPrimary = Color(0xFF062A2A),
            secondary = Color(0xFF72A7FF),
            background = Color(0xFF0A1220),
            surface = Color(0xFF111D30),
            surfaceVariant = Color(0xFF1B2A40),
            onBackground = Color(0xFFEAF2FF),
            onSurface = Color(0xFFEAF2FF),
            onSurfaceVariant = Color(0xFFAAB8CC),
            error = Color(0xFFFF647C)
        )
        setContent {
            MaterialTheme(colorScheme = pocketColors) { AppRoot(engine) }
        }
    }
}
