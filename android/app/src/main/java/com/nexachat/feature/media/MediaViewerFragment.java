package com.nexachat.feature.media;

import android.net.Uri;
import android.os.Bundle;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.navigation.Navigation;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.nexachat.R;
import com.nexachat.core.network.dto.AttachmentDto;
import com.nexachat.core.ui.components.ErrorStateView;
import com.nexachat.core.ui.components.LoadingStateView;
import com.nexachat.data.AttachmentRepository;
import com.nexachat.databinding.FragmentMediaViewerBinding;
import dagger.hilt.android.AndroidEntryPoint;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.io.File;
import javax.inject.Inject;

/**
 * Full-screen attachment preview backed by the authenticated cache. Images support
 * bounded zoom/pan; video and audio play locally via Media3.
 */
@AndroidEntryPoint
public final class MediaViewerFragment extends Fragment {

    private static final int MENU_OPEN = 1;
    private static final int MENU_SHARE = 2;
    private static final int MENU_SAVE = 3;
    private static final int MENU_ZOOM = 4;
    private static final String SAVED_EXPORT = "media.pendingExport";
    private final MediaViewerSubscriptions subscriptions = new MediaViewerSubscriptions();
    private final ImageLoadState imageLoadState = new ImageLoadState();
    @Nullable private File pendingExport;
    private final ActivityResultLauncher<String> saveDocument = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("application/octet-stream"), uri -> {
                File file = pendingExport;
                pendingExport = null;
                if (uri == null || file == null || !isAdded()) return;
                android.content.Context context = requireContext().getApplicationContext();
                subscriptions.addExport(io.reactivex.rxjava3.core.Completable.fromAction(() -> {
                    try (var input = new java.io.FileInputStream(file);
                         var output = context.getContentResolver().openOutputStream(uri)) {
                        if (output == null) throw new java.io.IOException("Unavailable document output");
                        byte[] buffer = new byte[8192];
                        int count;
                        while ((count = input.read(buffer)) != -1) {
                            output.write(buffer, 0, count);
                        }
                    }
                }).subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread())
                        .subscribe(() -> {
                            if (isAdded()) android.widget.Toast.makeText(context,
                                    R.string.media_saved, android.widget.Toast.LENGTH_SHORT).show();
                        }, error -> {
                            if (isAdded()) android.widget.Toast.makeText(context,
                                    R.string.media_save_failed, android.widget.Toast.LENGTH_SHORT).show();
                        }));
            });

    @Inject
    AttachmentRepository attachmentRepository;

    @Nullable
    private String loadedImageAttachmentId;

    @Nullable
    private File loadedImageFile;

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
        ui.viewerToolbar.getMenu().add(Menu.NONE, MENU_SHARE, 1, R.string.archive_action_share);
        ui.viewerToolbar.getMenu().add(Menu.NONE, MENU_SAVE, 2, R.string.action_save);
        ui.viewerToolbar.getMenu().add(Menu.NONE, MENU_ZOOM, 3, R.string.media_toggle_zoom);
        ui.viewerToolbar.setOnMenuItemClickListener(this::onToolbarMenuItem);
        ui.mediaImage.setOnDismiss(() -> {
            if (isAdded()) androidx.navigation.fragment.NavHostFragment.findNavController(this).navigateUp();
        });
        if (savedInstanceState != null) {
            String path = savedInstanceState.getString(SAVED_EXPORT);
            if (path != null) pendingExport = new File(path);
        }

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
        if (state == null) return false;
        File file = state.getFile() != null
                ? state.getFile()
                : imageLoadState.canUseFileActions() ? loadedImageFile : null;
        if (file == null) {
            return false;
        }
        String mime = state.getMimeType() == null ? "application/octet-stream" : state.getMimeType();
        if (item.getItemId() == MENU_OPEN) {
            AttachmentViewBinder.openExternally(requireContext(), file, mime);
            return true;
        }
        if (item.getItemId() == MENU_SHARE) {
            startActivity(android.content.Intent.createChooser(
                    AttachmentViewBinder.createShareIntent(requireContext(), file, mime),
                    getString(R.string.archive_action_share)));
            return true;
        }
        if (item.getItemId() == MENU_SAVE) {
            pendingExport = file;
            saveDocument.launch(state.getDisplayName() == null ? file.getName() : state.getDisplayName());
            return true;
        }
        if (item.getItemId() == MENU_ZOOM && binding != null) {
            binding.mediaImage.performClick();
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
        String sentAtLabel = state.getSentAtEpochMs() > 0
                ? com.nexachat.core.ui.components.ChatUiRules.resolveRelativeTimeLabel(
                        state.getSentAtEpochMs(),
                        System.currentTimeMillis(),
                        getString(R.string.time_just_now),
                        getString(R.string.time_yesterday))
                : null;
        MediaViewerChrome chrome = MediaViewerChrome.resolve(
                state.isVisualMedia(),
                state.getSenderName(),
                sentAtLabel,
                state.getDisplayName(),
                getString(R.string.media_attachment));
        ui.viewerToolbar.setTitle(chrome.getTitle());
        ui.viewerToolbar.setSubtitle(chrome.getSubtitle());
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
        if (id.equals(loadedImageAttachmentId)
                && imageLoadState.canUseFileActions()
                && ui.mediaImage.getDrawable() != null) {
            ui.mediaImage.setVisibility(View.VISIBLE);
            return;
        }
        loadedImageAttachmentId = id;
        loadedImageFile = null;
        long requestGeneration = imageLoadState.begin();
        updateToolbarActions(state);
        showLoading(ui);
        ui.mediaImage.setImageDrawable(null);
        subscriptions.clearImageLoads();
        subscriptions.addImageLoad(
                attachmentRepository.downloadToCache(id, state.getDisplayName())
                        .subscribeOn(Schedulers.io())
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe(
                                result -> {
                                    FragmentMediaViewerBinding current = binding;
                                    if (current == null || !isAdded()) {
                                        return;
                                    }
                                    if (result instanceof com.nexachat.core.common.Result.Success<?> success
                                            && success.getValue() instanceof File file) {
                                        current.mediaImage.setVisibility(View.VISIBLE);
                                        Glide.with(this)
                                                .load(file)
                                                .fitCenter()
                                                .listener(new RequestListener<Drawable>() {
                                                    @Override
                                                    public boolean onLoadFailed(
                                                            @Nullable GlideException error,
                                                            Object model,
                                                            Target<Drawable> target,
                                                            boolean isFirstResource) {
                                                        FragmentMediaViewerBinding latest = binding;
                                                        if (isCurrentImageRequest(
                                                                latest, id, requestGeneration)) {
                                                            latest.mediaLoadingHost.setVisibility(View.GONE);
                                                            latest.mediaImage.setVisibility(View.GONE);
                                                            latest.mediaImage.setImageDrawable(null);
                                                            loadedImageAttachmentId = null;
                                                            loadedImageFile = null;
                                                            imageLoadState.fail();
                                                            updateToolbarActions(state);
                                                            showImageError(latest, state, R.string.error_generic);
                                                        }
                                                        return true;
                                                    }

                                                    @Override
                                                    public boolean onResourceReady(
                                                            Drawable resource,
                                                            Object model,
                                                            Target<Drawable> target,
                                                            DataSource dataSource,
                                                            boolean isFirstResource) {
                                                        FragmentMediaViewerBinding latest = binding;
                                                        if (isCurrentImageRequest(
                                                                latest, id, requestGeneration)) {
                                                            imageLoadState.decodeSucceeded();
                                                            loadedImageFile = file;
                                                            latest.mediaLoadingHost.setVisibility(View.GONE);
                                                            latest.mediaErrorHost.setVisibility(View.GONE);
                                                            latest.mediaImage.setVisibility(View.VISIBLE);
                                                            updateToolbarActions(state);
                                                        }
                                                        return false;
                                                    }
                                                })
                                                .into(current.mediaImage);
                                    } else {
                                        loadedImageAttachmentId = null;
                                        loadedImageFile = null;
                                        imageLoadState.fail();
                                        updateToolbarActions(state);
                                        current.mediaLoadingHost.setVisibility(View.GONE);
                                        showImageError(current, state, R.string.error_network);
                                    }
                                },
                                error -> {
                                    FragmentMediaViewerBinding current = binding;
                                    if (current != null && isAdded()) {
                                        current.mediaLoadingHost.setVisibility(View.GONE);
                                        loadedImageAttachmentId = null;
                                        loadedImageFile = null;
                                        imageLoadState.fail();
                                        updateToolbarActions(state);
                                        showImageError(current, state, R.string.error_network);
                                    }
                                }));
    }

    private boolean isCurrentImageRequest(
            @Nullable FragmentMediaViewerBinding current,
            @NonNull String attachmentId,
            long requestGeneration) {
        return current != null
                && current == binding
                && isAdded()
                && imageLoadState.isCurrent(requestGeneration)
                && attachmentId.equals(loadedImageAttachmentId);
    }

    private void showImageError(
            @NonNull FragmentMediaViewerBinding ui,
            @NonNull MediaViewerViewModel.UiState state,
            int messageResource) {
        ui.mediaErrorHost.setVisibility(View.VISIBLE);
        if (errorView == null) {
            errorView = new ErrorStateView(requireContext());
            ui.mediaErrorHost.addView(errorView, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        }
        errorView.setMessage(getString(messageResource));
        errorView.setOnRetry(imageLoadState.shouldShowRetry() ? () -> {
            FragmentMediaViewerBinding current = binding;
            if (current != null && isAdded()) {
                current.mediaErrorHost.setVisibility(View.GONE);
                loadImageAttachment(current, state);
            }
        } : null);
    }

    private void updateToolbarActions(@NonNull MediaViewerViewModel.UiState state) {
        FragmentMediaViewerBinding ui = binding;
        if (ui == null) {
            return;
        }
        Menu menu = ui.viewerToolbar.getMenu();
        boolean hasFile = state.getFile() != null
                || (imageLoadState.canUseFileActions() && loadedImageFile != null);
        MenuItem open = menu.findItem(MENU_OPEN);
        if (open != null) {
            open.setVisible(hasFile);
        }
        menu.findItem(MENU_SHARE).setVisible(hasFile);
        menu.findItem(MENU_SAVE).setVisible(hasFile);
        menu.findItem(MENU_ZOOM).setVisible(hasFile && state.isImage());
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

    static final class ImageLoadState {
        private enum Phase { IDLE, LOADING, READY, ERROR }

        private Phase phase = Phase.IDLE;
        private long generation;

        long begin() {
            generation++;
            phase = Phase.LOADING;
            return generation;
        }

        void decodeSucceeded() {
            phase = Phase.READY;
        }

        void fail() {
            phase = Phase.ERROR;
        }

        boolean canUseFileActions() {
            return phase == Phase.READY;
        }

        boolean shouldShowRetry() {
            return phase == Phase.ERROR;
        }

        boolean isCurrent(long requestGeneration) {
            return generation == requestGeneration;
        }

        void reset() {
            generation++;
            phase = Phase.IDLE;
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle state) {
        super.onSaveInstanceState(state);
        if (pendingExport != null) state.putString(SAVED_EXPORT, pendingExport.getAbsolutePath());
    }

    @Override
    public void onStop() {
        if (player != null) {
            player.pause();
        }
        super.onStop();
    }

    @Override
    public void onDestroy() {
        subscriptions.clearOnDestroy();
        super.onDestroy();
    }

    @Override
    public void onDestroyView() {
        subscriptions.clearOnDestroyView();
        imageLoadState.reset();
        FragmentMediaViewerBinding ui = binding;
        if (ui != null) {
            Glide.with(this).clear(ui.mediaImage);
        }
        loadedImageAttachmentId = null;
        loadedImageFile = null;
        releasePlayer();
        loadingView = null;
        errorView = null;
        binding = null;
        super.onDestroyView();
    }
}
