package com.nexachat.feature.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import coil.ImageLoader
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/** Lets plain Compose code reach the authed [ImageLoader] singleton. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface MediaEntryPoint {
    fun imageLoader(): ImageLoader
}

/**
 * The Authorization-carrying Coil loader provided in NetworkModule. Local
 * `content://` models pass through it untouched — same loader serves both.
 */
@Composable
fun rememberAuthedImageLoader(): ImageLoader {
    val context = LocalContext.current
    return remember {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            MediaEntryPoint::class.java,
        ).imageLoader()
    }
}

/**
 * `1.2 MB` / `640 KB` style size text for bubble rows. The device locale
 * decides the decimal separator (vi_VN uses a comma); the B/KB/MB unit tokens
 * themselves are SI-neutral.
 */
internal fun formatBytes(bytes: Long?): String = FormatBytes.format(bytes)
