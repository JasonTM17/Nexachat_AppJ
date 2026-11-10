package com.nexachat.feature.profile;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;
import com.nexachat.R;
import com.nexachat.core.common.ApiError;
import com.nexachat.databinding.FragmentDeleteAccountBinding;
import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public final class DeleteAccountFragment extends Fragment {

    private FragmentDeleteAccountBinding binding;
    private DeleteAccountViewModel viewModel;
    private OnBackPressedCallback backPressedCallback;
    private boolean rendering;

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
        binding = FragmentDeleteAccountBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        viewModel = new ViewModelProvider(this).get(DeleteAccountViewModel.class);
        backPressedCallback = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                // Block system back while the delete request is in flight.
            }
        };
        requireActivity().getOnBackPressedDispatcher().addCallback(
                getViewLifecycleOwner(), backPressedCallback);
        binding.deleteAccountToolbar.setNavigationOnClickListener(v -> {
            DeleteAccountViewModel.UiState uiState = viewModel.getUiState().getValue();
            if (uiState == null || !uiState.isDeleting()) {
                NavHostFragment.findNavController(this).navigateUp();
            }
        });
        binding.passwordInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (!rendering) {
                    viewModel.onPasswordChange(s.toString());
                }
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });
        binding.passwordInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                trySubmit();
                return true;
            }
            return false;
        });
        binding.confirmDelete.setOnClickListener(v -> trySubmit());
        viewModel.getUiState().observe(getViewLifecycleOwner(), this::render);
    }

    private void trySubmit() {
        DeleteAccountViewModel.UiState state = viewModel.getUiState().getValue();
        if (state != null && !state.isDeleting()) {
            viewModel.submit();
        }
    }

    private void render(@NonNull DeleteAccountViewModel.UiState state) {
        rendering = true;
        if (!binding.passwordInput.getText().toString().equals(state.getPassword())) {
            binding.passwordInput.setText(state.getPassword());
        }
        binding.passwordLayout.setError(state.isPasswordMissing()
                ? getString(R.string.error_current_password_required)
                : null);
        ApiError error = state.getError();
        if (error != null) {
            binding.inlineError.setVisibility(View.VISIBLE);
            binding.inlineError.setText(DeleteAccountErrorText.text(requireContext(), error));
        } else {
            binding.inlineError.setVisibility(View.GONE);
        }
        boolean busy = state.isDeleting();
        if (backPressedCallback != null) {
            backPressedCallback.setEnabled(busy);
        }
        binding.confirmDelete.setEnabled(!busy);
        binding.passwordInput.setEnabled(!busy);
        binding.deleteProgress.setVisibility(busy ? View.VISIBLE : View.GONE);
        rendering = false;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
