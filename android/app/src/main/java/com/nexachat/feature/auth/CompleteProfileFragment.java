package com.nexachat.feature.auth;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;
import com.nexachat.R;
import com.nexachat.core.network.dto.UserDto;
import com.nexachat.databinding.FragmentCompleteProfileBinding;
import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public final class CompleteProfileFragment extends Fragment {

    private FragmentCompleteProfileBinding binding;
    private CompleteProfileViewModel viewModel;
    private boolean rendering;

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle state) {
        binding = FragmentCompleteProfileBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        viewModel = new ViewModelProvider(this).get(CompleteProfileViewModel.class);
        binding.displayNameInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (!rendering) {
                    viewModel.onDisplayNameChange(s.toString());
                }
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });
        binding.completeProfileContinue.setOnClickListener(v -> viewModel.continueToInbox());
        requireActivity().getOnBackPressedDispatcher().addCallback(
                getViewLifecycleOwner(),
                new OnBackPressedCallback(true) {
                    @Override
                    public void handleOnBackPressed() {
                        openInbox();
                    }
                });
        viewModel.getUiState().observe(getViewLifecycleOwner(), this::render);
        viewModel.getEvents().observe(getViewLifecycleOwner(), event -> {
            if (event == CompleteProfileViewModel.Event.Continue) {
                openInbox();
            }
        });
    }

    private void openInbox() {
        NavHostFragment.findNavController(this).navigate(
                R.id.inboxFragment, null, AuthNav.signedInOptions());
    }

    @Nullable
    private CharSequence displayNameErrorText(
            @Nullable CompleteProfileDisplayNameRules.Problem problem) {
        if (problem == null) {
            return null;
        }
        if (problem == CompleteProfileDisplayNameRules.Problem.BLANK) {
            return getString(R.string.error_display_name_required);
        }
        return getString(R.string.error_display_name_length);
    }

    private void render(CompleteProfileViewModel.UiState state) {
        rendering = true;
        UserDto user = state.getUser();
        if (user != null) {
            binding.completeProfileAvatar.setName(user.getDisplayName());
            String email = user.getEmail();
            binding.completeProfileEmail.setText(email == null ? "" : email);
            boolean verified = user.hasVerifiedRecoveryEmail();
            binding.completeProfileEmailVerified.setVisibility(
                    verified ? View.VISIBLE : View.GONE);
        }
        if (!binding.displayNameInput.getText().toString().equals(state.getDisplayName())) {
            binding.displayNameInput.setText(state.getDisplayName());
        }
        binding.displayNameLayout.setError(displayNameErrorText(state.getDisplayNameProblem()));
        binding.completeProfileError.setVisibility(state.getError() == null
                ? View.GONE : View.VISIBLE);
        if (state.getError() != null) {
            binding.completeProfileError.setText(
                    ApiErrorText.text(requireContext(), state.getError()));
        }
        boolean busy = state.getLoading() || state.getSaving();
        binding.displayNameInput.setEnabled(!busy);
        binding.completeProfileContinue.setEnabled(!busy);
        binding.loadingState.setVisibility(busy ? View.VISIBLE : View.GONE);
        rendering = false;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
