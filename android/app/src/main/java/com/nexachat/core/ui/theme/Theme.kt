package com.nexachat.core.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val LightColorScheme = lightColorScheme(
    primary = Indigo30,
    secondary = Slate30,
    tertiary = Rose30,
    primaryContainer = Indigo90,
    secondaryContainer = Slate90,
    tertiaryContainer = Rose90,
)

private val DarkColorScheme = darkColorScheme(
    primary = Indigo80,
    secondary = Slate80,
    tertiary = Rose80,
    primaryContainer = Indigo10,
)

/**
 * App-wide Material3 theme. Prefers dynamic color on Android 12+ (S, API 31);
 * falls back to the brand palette on API 26–30.
 */
@Composable
fun NexaChatTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
