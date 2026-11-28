package com.nexachat.feature.feed;

import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;
import com.bumptech.glide.Glide;
import com.google.android.material.snackbar.Snackbar;
import com.nexachat.R;
import com.nexachat.databinding.FragmentPostComposeBinding;
import dagger.hilt.android.AndroidEntryPoint;

/**
 * Writes or edits one public post: text up to the server limit plus one optional photo
 * or MP4 video (when {@code videoUploadsEnabled} is true).
 */
@AndroidEntryPoint
public final class PostComposeFragment extends Fragment {

    public static final String ARG_EDIT_POST_ID = "editPostId";
    public static final String ARG_EDIT_BODY = "editBody";

    private FragmentPostComposeBinding binding;
    private PostComposeViewModel viewModel;
    private boolean syncingBody;
    private final androidx.activity.result.ActivityResultLauncher<PickVisualMediaRequest> imagePicker =
            registerForActivityResult(
                    new ActivityResultContracts.PickVisualMedia(),
                    uri -> {
                        if (uri != null && viewModel != null) {
                            viewModel.setImage(uri);
                        }
                    });
    private final androidx.activity.result.ActivityResultLauncher<PickVisualMediaRequest> videoPicker =
            registerForActivityResult(
                    new ActivityResultContracts.PickVisualMedia(),
                    uri -> {
                        if (uri != null && viewModel != null) {
                            viewModel.setVideo(uri);
                        }
                    });

    public PostComposeFragment() {
        super(R.layout.fragment_post_compose);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        binding = FragmentPostComposeBinding.bind(view);
        viewModel = new ViewModelProvider(this).get(PostComposeViewModel.class);

        Bundle args = getArguments();
        String editPostId = args == null ? null : args.getString(ARG_EDIT_POST_ID);
        String editBody = args == null ? null : args.getString(ARG_EDIT_BODY);
        if (editPostId != null && !editPostId.isBlank()) {
            boolean firstInit = !Boolean.TRUE.equals(viewModel.getUiState().getValue() != null
                    && viewModel.getUiState().getValue().isEditMode());
            viewModel.startEdit(editPostId, editBody);
            if (firstInit && savedInstanceState == null && editBody != null) {
                syncingBody = true;
                binding.composeInput.setText(editBody);
                binding.composeInput.setSelection(editBody.length());
                syncingBody = false;
            }
        }

        binding.composeToolbar.setNavigationOnClickListener(
                ignored -> NavHostFragment.findNavController(this).navigateUp());
        binding.composeSubmit.setOnClickListener(ignored -> viewModel.submit());
        binding.composeAddImage.setOnClickListener(ignored -> imagePicker.launch(
                new PickVisualMediaRequest.Builder()
                        .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                        .build()));
        binding.composeAddVideo.setOnClickListener(ignored -> videoPicker.launch(
                new PickVisualMediaRequest.Builder()
                        .setMediaType(ActivityResultContracts.PickVisualMedia.VideoOnly.INSTANCE)
                        .build()));
        binding.composeImageRemove.setOnClickListener(ignored -> viewModel.setImage(null));
        binding.composeVideoRemove.setOnClickListener(ignored -> viewModel.setVideo(null));
        binding.composeInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }

            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }

            @Override public void afterTextChanged(Editable editable) {
                if (syncingBody) {
                    return;
                }
                viewModel.setBody(editable == null ? "" : editable.toString());
            }
        });
        viewModel.getUiState().observe(getViewLifecycleOwner(), this::render);
        viewModel.getOutcome().observe(getViewLifecycleOwner(), this::handleOutcome);
    }

    private void render(@NonNull PostComposeViewModel.UiState state) {
        if (binding == null) {
            return;
        }
        binding.composeToolbar.setTitle(
                state.isEditMode() ? R.string.feed_edit_post_title : R.string.feed_compose_title);
        binding.composeSubmit.setText(
                state.isEditMode() ? R.string.feed_compose_save : R.string.feed_compose_submit);
        binding.composeCounter.setText(getString(
                R.string.feed_compose_counter, state.getLength(), FeedRules.MAX_POST_LENGTH));
        binding.composeSubmit.setEnabled(state.canSubmit() && !state.isBusy());
        binding.composeProgress.setVisibility(state.isBusy() ? View.VISIBLE : View.GONE);

        if (state.isEditMode()) {
            binding.composeMediaActions.setVisibility(View.GONE);
            binding.composeImageFrame.setVisibility(View.GONE);
            binding.composeVideoFrame.setVisibility(View.GONE);
            binding.composeVideoDisabledNote.setVisibility(View.GONE);
            binding.composeVideoCaptionNote.setVisibility(View.GONE);
            return;
        }

        binding.composeMediaActions.setVisibility(View.VISIBLE);
        binding.composeAddImage.setEnabled(!state.isBusy());
        binding.composeAddVideo.setEnabled(!state.isBusy() && state.isVideoUploadsEnabled());
        binding.composeVideoDisabledNote.setVisibility(
                state.isVideoUploadsEnabled() ? View.GONE : View.VISIBLE);

        Uri image = state.getImage();
        binding.composeImageFrame.setVisibility(image == null ? View.GONE : View.VISIBLE);
        if (image == null) {
            Glide.with(binding.composeImage).clear(binding.composeImage);
        } else {
            binding.composeImage.setClipToOutline(true);
            Glide.with(binding.composeImage).load(image).centerCrop().into(binding.composeImage);
        }

        Uri video = state.getVideo();
        binding.composeVideoFrame.setVisibility(video == null ? View.GONE : View.VISIBLE);
        if (video == null) {
            Glide.with(binding.composeVideoThumb).clear(binding.composeVideoThumb);
            binding.composeVideoCaptionNote.setVisibility(View.GONE);
        } else {
            binding.composeVideoFrame.setClipToOutline(true);
            Glide.with(binding.composeVideoThumb)
                    .load(video)
                    .centerCrop()
                    .into(binding.composeVideoThumb);
            boolean missingCaption = FeedRules.normalize(state.getBody()) == null;
            binding.composeVideoCaptionNote.setVisibility(
                    missingCaption ? View.VISIBLE : View.GONE);
        }
    }

    private void handleOutcome(@Nullable PostComposeViewModel.Outcome outcome) {
        if (outcome == null || binding == null) {
            return;
        }
        viewModel.onOutcomeShown();
        switch (outcome) {
            case POSTED -> {
                getParentFragmentManager().setFragmentResult(
                        PublicFeedFragment.RESULT_POST_CREATED, new Bundle());
                NavHostFragment.findNavController(this).navigateUp();
            }
            case EDITED -> {
                getParentFragmentManager().setFragmentResult(
                        PublicFeedFragment.RESULT_POST_EDITED, new Bundle());
                NavHostFragment.findNavController(this).navigateUp();
            }
            case RATE_LIMITED -> snackbar(R.string.feed_rate_limited);
            case IMAGE_FAILED -> snackbar(R.string.feed_image_failed);
            case VIDEO_DISABLED -> snackbar(R.string.feed_video_disabled);
            case VIDEO_TOO_LARGE -> snackbar(R.string.feed_video_too_large);
            case VIDEO_INVALID_TYPE -> snackbar(R.string.feed_video_invalid_type);
            case VIDEO_FAILED -> snackbar(R.string.feed_video_failed);
            case FAILED -> snackbar(R.string.feed_post_failed);
        }
    }

    private void snackbar(int message) {
        if (binding == null) {
            return;
        }
        Snackbar.make(binding.getRoot(), message, Snackbar.LENGTH_SHORT).show();
    }

    @Override
    public void onDestroyView() {
        binding = null;
        super.onDestroyView();
    }
}
