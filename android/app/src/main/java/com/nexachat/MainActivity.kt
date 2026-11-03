package com.nexachat

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
                AppNavHost()
            }
        }
    }
}
