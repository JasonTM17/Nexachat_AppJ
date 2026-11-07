package com.nexachat.feature.media;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.lifecycle.SavedStateHandle;
import com.nexachat.core.common.ApiError;
import com.nexachat.core.common.Result;
import com.nexachat.data.AttachmentRepository;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public final class MediaViewerViewModelTest {

    @Rule public final InstantTaskExecutorRule taskExecutorRule = new InstantTaskExecutorRule();

    private static final String ATTACHMENT_ID = "att-1";
    private AttachmentRepository attachmentRepository;

    @Before
    public void setUp() {
        RxJavaPlugins.setIoSchedulerHandler(scheduler -> Schedulers.trampoline());
        attachmentRepository = mock(AttachmentRepository.class);
    }

    @After
    public void tearDown() {
        RxJavaPlugins.reset();
    }

    @Test
    public void imageMimeSkipsDownloadHop() {
        MediaViewerViewModel viewModel = createViewModel("photo.jpg", "image/jpeg");

        MediaViewerViewModel.UiState state = viewModel.getUiState().getValue();
        assertTrue(state.isImage());
        assertFalse(state.isLoading());
        assertEquals("photo.jpg", state.getDisplayName());
        verify(attachmentRepository, never()).downloadToCache(anyString(), nullable(String.class));
    }

    @Test
    public void nonImageDownloadsToCacheOnInitAndExposesFile() {
        File cached = new File("cached-clip.mp4");
        when(attachmentRepository.downloadToCache(ATTACHMENT_ID, "clip.mp4"))
                .thenReturn(Single.just(new Result.Success<>(cached)));

        MediaViewerViewModel viewModel = createViewModel("clip.mp4", "video/mp4");

        MediaViewerViewModel.UiState state = viewModel.getUiState().getValue();
        assertFalse(state.isImage());
        assertFalse(state.isLoading());
        assertEquals(cached, state.getFile());
        assertNull(state.getError());
        verify(attachmentRepository, times(1)).downloadToCache(ATTACHMENT_ID, "clip.mp4");
    }

    @Test
    public void downloadFailureSurfacesErrorAndReloadRetries() {
        when(attachmentRepository.downloadToCache(anyString(), nullable(String.class)))
                .thenReturn(Single.just(Result.failure(new ApiError.Network("down"))));

        MediaViewerViewModel viewModel = createViewModel("clip.mp4", "video/mp4");
        assertEquals(new ApiError.Network("down"), viewModel.getUiState().getValue().getError());
        assertFalse(viewModel.getUiState().getValue().isLoading());

        File cached = new File("cached-clip.mp4");
        when(attachmentRepository.downloadToCache(anyString(), nullable(String.class)))
                .thenReturn(Single.just(new Result.Success<>(cached)));
        viewModel.reload();

        MediaViewerViewModel.UiState state = viewModel.getUiState().getValue();
        assertEquals(cached, state.getFile());
        assertNull(state.getError());
        verify(attachmentRepository, times(2)).downloadToCache(ATTACHMENT_ID, "clip.mp4");
    }

    @Test
    public void reloadWhileDownloadIsInFlightIsNoOp() {
        when(attachmentRepository.downloadToCache(anyString(), nullable(String.class)))
                .thenReturn(Single.never());

        MediaViewerViewModel viewModel = createViewModel("clip.mp4", "video/mp4");
        assertTrue(viewModel.getUiState().getValue().isLoading());

        viewModel.reload();

        verify(attachmentRepository, times(1))
                .downloadToCache(anyString(), nullable(String.class));
    }

    @Test
    public void reloadOnImageIsNoOp() {
        MediaViewerViewModel viewModel = createViewModel("photo.png", "image/png");

        viewModel.reload();

        verify(attachmentRepository, never()).downloadToCache(anyString(), nullable(String.class));
    }

    @Test
    public void blankNavArgsDegradeToNulls() {
        when(attachmentRepository.downloadToCache(anyString(), nullable(String.class)))
                .thenReturn(Single.just(Result.failure(new ApiError.Network("offline"))));

        MediaViewerViewModel viewModel = createViewModel("  ", "  ");

        MediaViewerViewModel.UiState state = viewModel.getUiState().getValue();
        assertNull(state.getDisplayName());
        assertNull(state.getMimeType());
        assertFalse(state.isImage());
    }

    private MediaViewerViewModel createViewModel(String name, String mime) {
        Map<String, Object> args = new HashMap<>();
        args.put("attachmentId", ATTACHMENT_ID);
        if (name != null) {
            args.put("name", name);
        }
        if (mime != null) {
            args.put("mime", mime);
        }
        return new MediaViewerViewModel(new SavedStateHandle(args), attachmentRepository);
    }
}
