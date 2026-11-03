package com.nexachat.feature.media

import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.nexachat.R
import com.nexachat.core.common.ApiError
import com.nexachat.core.common.Result
import com.nexachat.core.network.dto.AttachmentDto
import com.nexachat.data.AttachmentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

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
    val state by viewModel.uiState.collectAsStateWithLifecycle()
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
            )
        },
    ) { innerPadding ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            color = MaterialTheme.colorScheme.surface,
        ) {
            val file = state.file
            when {
                state.isImage -> ZoomableImage(
                    url = AttachmentDto.contentPath(state.attachmentId),
                    imageLoader = imageLoader,
                    contentDescription = state.displayName,
                )
                state.loading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
                file != null -> PlayerSurface(file = file)
                else -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.error_network),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
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
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 5f)
                    offset = if (scale > 1f) offset + pan else Offset.Zero
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
                    translationX = offset.x,
                    translationY = offset.y,
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

/**
 * Viewer state is derived from nav args + the download: images skip the hop
 * (streamed by Coil), everything else resolves to a cache file.
 */
@HiltViewModel
class MediaViewerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val attachmentRepository: AttachmentRepository,
) : ViewModel() {

    data class UiState(
        val attachmentId: String = "",
        val displayName: String? = null,
        val mimeType: String? = null,
        val isImage: Boolean = false,
        val loading: Boolean = true,
        val file: File? = null,
        val error: ApiError? = null,
    )

    private val _uiState = MutableStateFlow(
        UiState(
            attachmentId = savedStateHandle.get<String>("attachmentId").orEmpty(),
            displayName = savedStateHandle.get<String>("name")?.takeIf { it.isNotBlank() },
            mimeType = savedStateHandle.get<String>("mime")?.takeIf { it.isNotBlank() },
        ),
    )
    val uiState: StateFlow<UiState> = _uiState

    init {
        val current = _uiState.value
        val isImage = current.mimeType
            ?.startsWith(AttachmentDto.MIME_PREFIX_IMAGE) == true
        _uiState.value = current.copy(isImage = isImage, loading = !isImage)
        if (!isImage) {
            viewModelScope.launch {
                when (
                    val result = attachmentRepository.downloadToCache(
                        attachmentId = current.attachmentId,
                        displayName = current.displayName,
                    )
                ) {
                    is Result.Success ->
                        _uiState.value = _uiState.value.copy(loading = false, file = result.value)
                    is Result.Failure ->
                        _uiState.value = _uiState.value.copy(loading = false, error = result.error)
                }
            }
        }
    }
}
