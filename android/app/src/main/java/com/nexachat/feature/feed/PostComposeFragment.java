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

/** Writes one public post: text up to the server limit plus one optional image. */
@AndroidEntryPoint
public final class PostComposeFragment extends Fragment {

    private FragmentPostComposeBinding binding;
    private PostComposeViewModel viewModel;
    private final androidx.activity.result.ActivityResultLauncher<PickVisualMediaRequest> imagePicker =
            registerForActivityResult(
                    new ActivityResultContracts.PickVisualMedia(),
                    uri -> {
                        if (uri != null && viewModel != null) {
                            viewModel.setImage(uri);
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
        binding.composeToolbar.setNavigationOnClickListener(
                ignored -> NavHostFragment.findNavController(this).navigateUp());
        binding.composeSubmit.setOnClickListener(ignored -> viewModel.submit());
        binding.composeAddImage.setOnClickListener(ignored -> imagePicker.launch(
                new PickVisualMediaRequest.Builder()
                        .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                        .build()));
        binding.composeImageRemove.setOnClickListener(ignored -> viewModel.setImage(null));
        binding.composeInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }

            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }

            @Override public void afterTextChanged(Editable editable) {
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
        binding.composeCounter.setText(getString(
                R.string.feed_compose_counter, state.getLength(), FeedRules.MAX_POST_LENGTH));
        binding.composeSubmit.setEnabled(state.canSubmit() && !state.isBusy());
        binding.composeProgress.setVisibility(state.isBusy() ? View.VISIBLE : View.GONE);
        binding.composeAddImage.setEnabled(!state.isBusy());
        Uri image = state.getImage();
        binding.composeImageFrame.setVisibility(image == null ? View.GONE : View.VISIBLE);
        if (image == null) {
            Glide.with(binding.composeImage).clear(binding.composeImage);
        } else {
            Glide.with(binding.composeImage).load(image).centerCrop().into(binding.composeImage);
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
            case RATE_LIMITED -> snackbar(R.string.feed_rate_limited);
            case IMAGE_FAILED -> snackbar(R.string.feed_image_failed);
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
