package com.nexachat.feature.media;

import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.navigation.Navigation;
import com.bumptech.glide.Glide;
import com.nexachat.R;
import com.nexachat.core.network.dto.AttachmentDto;
import com.nexachat.core.ui.components.ErrorStateView;
import com.nexachat.core.ui.components.LoadingStateView;
import com.nexachat.data.AttachmentRepository;
import com.nexachat.databinding.FragmentMediaViewerBinding;
import dagger.hilt.android.AndroidEntryPoint;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.io.File;
import javax.inject.Inject;

/**
 * Full-screen attachment preview. Images stream from the content endpoint; video and audio
 * download to cache first, then play locally via Media3.
 */
@AndroidEntryPoint
public final class MediaViewerFragment extends Fragment {

    private static final int MENU_OPEN = 1;

    @Inject
    AttachmentRepository attachmentRepository;

    private final CompositeDisposable disposables = new CompositeDisposable();

    @Nullable
    private String loadedImageAttachmentId;

    @Nullable
    private FragmentMediaViewerBinding binding;

    @Nullable
    private MediaViewerViewModel viewModel;

    @Nullable
    private ExoPlayer player;

    @Nullable
    private LoadingStateView loadingView;

    @Nullable
    private ErrorStateView errorView;

    @Nullable
    private File playingFile;

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        binding = FragmentMediaViewerBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        FragmentMediaViewerBinding ui = binding;
        if (ui == null) {
            return;
        }
        ui.viewerToolbar.setNavigationOnClickListener(v ->
                Navigation.findNavController(v).navigateUp());
        ui.viewerToolbar.getMenu().add(Menu.NONE, MENU_OPEN, 0, R.string.action_view);
        ui.viewerToolbar.setOnMenuItemClickListener(this::onToolbarMenuItem);

        viewModel = new ViewModelProvider(this).get(MediaViewerViewModel.class);
        viewModel.getUiState()
                .observe(getViewLifecycleOwner(), this::render);
    }

    private boolean onToolbarMenuItem(@NonNull MenuItem item) {
        MediaViewerViewModel viewModel = this.viewModel;
        if (viewModel == null) {
            return false;
        }
        MediaViewerViewModel.UiState state = viewModel.getUiState().getValue();
        File file = state.getFile();
        if (file == null) {
            return false;
        }
        String mime = state.getMimeType() == null ? "application/octet-stream" : state.getMimeType();
        if (item.getItemId() == MENU_OPEN) {
            AttachmentViewBinder.openExternally(requireContext(), file, mime);
            return true;
        }
        return false;
    }

    private void render(@NonNull MediaViewerViewModel.UiState state) {
        FragmentMediaViewerBinding ui = binding;
        MediaViewerViewModel viewModel = this.viewModel;
        if (ui == null || viewModel == null) {
            return;
        }
        ui.viewerToolbar.setTitle(state.getDisplayName() == null
                ? getString(R.string.media_attachment)
                : state.getDisplayName());
        updateToolbarActions(state);

        hideContent(ui);
        if (state.isImage()) {
            loadImageAttachment(ui, state);
            return;
        }
        if (state.getLoading()) {
            showLoading(ui);
            return;
        }
        File file = state.getFile();
        if (file != null) {
            showPlayer(ui, file);
            return;
        }
        showError(ui, viewModel);
    }

    private void loadImageAttachment(
            @NonNull FragmentMediaViewerBinding ui,
            @NonNull MediaViewerViewModel.UiState state) {
        String id = state.getAttachmentId();
        if (id.isEmpty()) {
            ui.mediaImage.setVisibility(View.VISIBLE);
            return;
        }
        if (id.equals(loadedImageAttachmentId) && ui.mediaImage.getDrawable() != null) {
            ui.mediaImage.setVisibility(View.VISIBLE);
            return;
        }
        loadedImageAttachmentId = id;
        showLoading(ui);
        disposables.clear();
        disposables.add(
                attachmentRepository.downloadToCache(id, state.getDisplayName())
                        .subscribeOn(Schedulers.io())
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe(
                                result -> {
                                    FragmentMediaViewerBinding current = binding;
                                    if (current == null || !isAdded()) {
                                        return;
                                    }
                                    current.mediaLoadingHost.setVisibility(View.GONE);
                                    if (result instanceof com.nexachat.core.common.Result.Success<?> success
                                            && success.getValue() instanceof File file) {
                                        current.mediaImage.setVisibility(View.VISIBLE);
                                        Glide.with(this)
                                                .load(file)
                                                .fitCenter()
                                                .into(current.mediaImage);
                                    } else {
                                        current.mediaImage.setVisibility(View.VISIBLE);
                                        Glide.with(this)
                                                .load(AttachmentDto.contentPath(id))
                                                .fitCenter()
                                                .into(current.mediaImage);
                                    }
                                },
                                error -> {
                                    FragmentMediaViewerBinding current = binding;
                                    if (current != null && isAdded()) {
                                        current.mediaLoadingHost.setVisibility(View.GONE);
                                        current.mediaImage.setVisibility(View.VISIBLE);
                                        Glide.with(this)
                                                .load(AttachmentDto.contentPath(id))
                                                .fitCenter()
                                                .into(current.mediaImage);
                                    }
                                }));
    }

    private void updateToolbarActions(@NonNull MediaViewerViewModel.UiState state) {
        FragmentMediaViewerBinding ui = binding;
        if (ui == null) {
            return;
        }
        Menu menu = ui.viewerToolbar.getMenu();
        boolean hasFile = state.getFile() != null;
        MenuItem open = menu.findItem(MENU_OPEN);
        if (open != null) {
            open.setVisible(hasFile);
        }
    }

    private void hideContent(@NonNull FragmentMediaViewerBinding ui) {
        ui.mediaImage.setVisibility(View.GONE);
        ui.mediaPlayer.setVisibility(View.GONE);
        ui.mediaLoadingHost.setVisibility(View.GONE);
        ui.mediaErrorHost.setVisibility(View.GONE);
        Glide.with(this).clear(ui.mediaImage);
        releasePlayer();
    }

    private void showLoading(@NonNull FragmentMediaViewerBinding ui) {
        ui.mediaLoadingHost.setVisibility(View.VISIBLE);
        if (loadingView == null) {
            loadingView = new LoadingStateView(requireContext());
            ui.mediaLoadingHost.addView(loadingView, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        }
    }

    private void showError(
            @NonNull FragmentMediaViewerBinding ui,
            @NonNull MediaViewerViewModel viewModel) {
        ui.mediaErrorHost.setVisibility(View.VISIBLE);
        if (errorView == null) {
            errorView = new ErrorStateView(requireContext());
            ui.mediaErrorHost.addView(errorView, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        }
        errorView.setMessage(getString(R.string.error_network));
        errorView.setOnRetry(viewModel::reload);
    }

    private void showPlayer(@NonNull FragmentMediaViewerBinding ui, @NonNull File file) {
        ui.mediaPlayer.setVisibility(View.VISIBLE);
        if (player != null && file.equals(playingFile)) {
            return;
        }
        releasePlayer();
        playingFile = file;
        player = new ExoPlayer.Builder(requireContext()).build();
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)));
        player.setPlayWhenReady(true);
        player.prepare();
        ui.mediaPlayer.setPlayer(player);
    }

    private void releasePlayer() {
        FragmentMediaViewerBinding ui = binding;
        if (ui != null) {
            ui.mediaPlayer.setPlayer(null);
        }
        if (player != null) {
            player.release();
            player = null;
        }
        playingFile = null;
    }

    @Override
    public void onStop() {
        if (player != null) {
            player.pause();
        }
        super.onStop();
    }

    @Override
    public void onDestroyView() {
        disposables.clear();
        loadedImageAttachmentId = null;
        releasePlayer();
        loadingView = null;
        errorView = null;
        binding = null;
        super.onDestroyView();
    }
}
