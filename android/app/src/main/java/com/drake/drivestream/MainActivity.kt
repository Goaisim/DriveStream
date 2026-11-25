package com.drake.drivestream

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        AuthManager.getInstance(this).apply {
            startCompanionServer()
            refreshIfNeededOnWakeAsync()
        }
        LocalStreamProxy.getInstance(this).start()
        AndroidAdBlockEngine.getInstance(this).prepare()
        SyncManager.getInstance(this).syncOnLaunch()

        handleOAuthIntent(intent)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF3B82F6),
                    background = Color(0xFF0A0A0F),
                    surface = Color(0xFF141621),
                    onPrimary = Color.White,
                    onBackground = Color.White,
                    onSurface = Color.White
                )
            ) {
                TvMainScreen()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        AuthManager.getInstance(this).apply {
            startCompanionServer()
            refreshLocalIp()
            refreshIfNeededOnWakeAsync()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOAuthIntent(intent)
    }

    private fun handleOAuthIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == AuthManager.REDIRECT_SCHEME) {
            lifecycleScope.launch {
                val res = AuthManager.getInstance(this@MainActivity).handleRedirectUri(data)
                if (res.isSuccess) {
                    SyncManager.getInstance(this@MainActivity).triggerManualSync()
                }
            }
        }
    }
}
