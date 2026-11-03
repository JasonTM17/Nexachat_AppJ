package com.nexachat

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.nexachat.core.common.AppLocaleManager
import com.nexachat.core.ui.AppNavHost
import com.nexachat.core.ui.theme.NexaChatTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Single-activity host. All screens are Compose destinations on [AppNavHost]
 * (plan §12.1).
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /**
     * Per-app locale for API <33 — [AppLocaleManager.wrapContext] merges the
     * stored tag into the activity configuration. No-op on 33+, where the
     * platform LocaleManager configures activities itself.
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocaleManager.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NexaChatTheme {
                // Root Surface paints colorScheme.background behind every
                // destination — without it the window's splash-colored
                // windowBackground bleeds into Scaffold-less screens (login).
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AppNavHost()
                }
            }
        }
    }
}
