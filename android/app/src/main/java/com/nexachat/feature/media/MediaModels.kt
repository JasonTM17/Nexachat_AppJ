package com.nexachat.feature.media

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import coil.ImageLoader
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.Locale

/**
 * A picked item staged above the input bar while its upload runs
 * (phase-09 send pipeline). Rows are keyed by [localId] — the server
 * attachment id only exists after [PendingState.UPLOADED], at which point
 * the row leaves the strip and the optimistic outbox bubble takes over.
 */
data class PendingAttachment(
    val localId: String,
    val uri: Uri,
    val displayName: String?,
    val mimeType: String?,
    /** Derived wire type (IMAGE/VIDEO/AUDIO/FILE). */
    val type: String,
    val progress: Float,
    val state: PendingState,
) {
    enum class PendingState { UPLOADING, FAILED }
}

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
internal fun formatBytes(bytes: Long?): String {
    if (bytes == null || bytes < 0) return ""
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.getDefault(), "%.1f KB", kb)
    return String.format(Locale.getDefault(), "%.1f MB", kb / 1024.0)
}
