package com.nexachat.feature.media

import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.asFlow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.nexachat.R
import com.nexachat.core.network.dto.AttachmentDto
import com.nexachat.core.ui.components.ErrorState
import com.nexachat.core.ui.components.LoadingState
import java.io.File

/**
 * `media/{attachmentId}?name=&mime=` — fullscreen attachment viewer.
 *
 * IMAGE streams straight through the authed Coil loader (no download hop);
 * VIDEO/AUDIO download to cache first, then play the local file — one
 * authorized fetch path shared with FILE bubbles, and the cached copy keeps
 * working offline. The player itself is a composable-local remember so
 * release() lines up with the composition, not process teardown.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaViewerScreen(
    onBack: () -> Unit,
    viewModel: MediaViewerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.asFlow().collectAsStateWithLifecycle(
        viewModel.uiState.value ?: MediaViewerViewModel.UiState(),
    )
    val imageLoader = rememberAuthedImageLoader()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(state.displayName ?: stringResource(R.string.media_attachment))
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                ),
            )
        },
    ) { innerPadding ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            color = Color.Black,
        ) {
            val file = state.file
            when {
                state.isImage -> ZoomableImage(
                    url = AttachmentDto.contentPath(state.attachmentId),
                    imageLoader = imageLoader,
                    contentDescription = state.displayName,
                )
                state.loading -> LoadingState()
                file != null -> PlayerSurface(file = file)
                else -> ErrorState(
                    message = stringResource(R.string.error_network),
                    onRetry = viewModel::reload,
                )
            }
        }
    }
}

@Composable
private fun ZoomableImage(
    url: String,
    imageLoader: coil.ImageLoader,
    contentDescription: String?,
) {
    // Zoom/pan survive rotation — a landscape re-look shouldn't reset them.
    var scale by rememberSaveable { mutableFloatStateOf(1f) }
    var offsetX by rememberSaveable { mutableFloatStateOf(0f) }
    var offsetY by rememberSaveable { mutableFloatStateOf(0f) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 5f)
                    if (scale > 1f) {
                        offsetX += pan.x
                        offsetY += pan.y
                    } else {
                        offsetX = 0f
                        offsetY = 0f
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = url,
            imageLoader = imageLoader,
            contentDescription = contentDescription
                ?: stringResource(R.string.cd_open_attachment),
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offsetX,
                    translationY = offsetY,
                ),
        )
    }
}

@Composable
private fun PlayerSurface(file: File) {
    val context = LocalContext.current
    val player = remember(file) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(file.toUri()))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) {
        onDispose { player.release() }
    }
    AndroidView(
        factory = { ctx -> PlayerView(ctx).apply { this.player = player } },
        modifier = Modifier.fillMaxSize(),
    )
}
