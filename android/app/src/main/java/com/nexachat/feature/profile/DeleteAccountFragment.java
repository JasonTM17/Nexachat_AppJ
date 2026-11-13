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
        binding.deletionCodeInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (!rendering) {
                    viewModel.onCodeChange(s.toString());
                }
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });
        binding.deletionCodeInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                trySubmit();
                return true;
            }
            return false;
        });
        binding.sendDeletionCode.setOnClickListener(v -> viewModel.sendDeletionCode());
        binding.profileLoadRetry.setOnClickListener(v -> viewModel.retryLoadProfile());
        binding.confirmDelete.setOnClickListener(v -> trySubmit());
        viewModel.getUiState().observe(getViewLifecycleOwner(), this::render);
    }

    private void trySubmit() {
        DeleteAccountViewModel.UiState state = viewModel.getUiState().getValue();
        if (state != null && !state.isDeleting() && !state.isSendingCode()) {
            viewModel.submit();
        }
    }

    private void render(@NonNull DeleteAccountViewModel.UiState state) {
        rendering = true;
        boolean profileReady = state.isProfileLoaded();
        boolean passwordFlow = state.usesPasswordFlow();
        binding.passwordLayout.setVisibility(profileReady && passwordFlow ? View.VISIBLE : View.GONE);
        binding.sendDeletionCode.setVisibility(
                profileReady && !passwordFlow
                        ? View.VISIBLE
                        : View.GONE);
        binding.deletionCodeSentMessage.setVisibility(
                profileReady && !passwordFlow && state.isCodeRequested()
                        ? View.VISIBLE
                        : View.GONE);
        binding.deletionCodeLayout.setVisibility(
                profileReady && !passwordFlow && state.isCodeRequested()
                        ? View.VISIBLE
                        : View.GONE);
        binding.confirmDelete.setVisibility(
                profileReady && (passwordFlow || state.isCodeRequested())
                        ? View.VISIBLE
                        : View.GONE);

        if (passwordFlow) {
            if (!binding.passwordInput.getText().toString().equals(state.getPassword())) {
                binding.passwordInput.setText(state.getPassword());
            }
            binding.passwordLayout.setError(state.isPasswordMissing()
                    ? getString(R.string.error_current_password_required)
                    : null);
        } else if (state.isCodeRequested()) {
            if (!binding.deletionCodeInput.getText().toString().equals(state.getCode())) {
                binding.deletionCodeInput.setText(state.getCode());
                binding.deletionCodeInput.setSelection(state.getCode().length());
            }
            binding.deletionCodeLayout.setError(state.isCodeMissing()
                    ? getString(R.string.error_deletion_code_required)
                    : state.isCodeInputError()
                            ? getString(R.string.delete_account_code_invalid)
                            : null);
        }

        boolean profileLoadError = !profileReady && state.getError() != null;
        boolean codeFlowError = state.isCodeInputError() && state.getError() instanceof ApiError.InvalidOrExpiredCode;
        ApiError error = state.getError();
        if (error != null && !codeFlowError) {
            binding.inlineError.setVisibility(View.VISIBLE);
            binding.inlineError.setText(DeleteAccountErrorText.text(requireContext(), error));
        } else {
            binding.inlineError.setVisibility(View.GONE);
        }
        binding.profileLoadRetry.setVisibility(profileLoadError ? View.VISIBLE : View.GONE);
        boolean busy = state.isDeleting() || state.isSendingCode();
        boolean waitingForProfile = !profileReady && state.isProfileLoading();
        if (backPressedCallback != null) {
            backPressedCallback.setEnabled(state.isDeleting());
        }
        binding.confirmDelete.setEnabled(
                profileReady && !busy && (passwordFlow || state.isCodeRequested()));
        binding.passwordInput.setEnabled(!busy && passwordFlow);
        binding.deletionCodeInput.setEnabled(!busy && state.isCodeRequested());
        binding.sendDeletionCode.setEnabled(!busy && state.getResendSecondsRemaining() <= 0);
        if (state.getResendSecondsRemaining() > 0) {
            binding.sendDeletionCode.setText(getString(R.string.recovery_email_resend_countdown,
                    state.getResendSecondsRemaining()));
        } else {
            binding.sendDeletionCode.setText(state.isCodeRequested()
                    ? R.string.recovery_email_resend : R.string.delete_account_send_code);
        }
        binding.profileLoadRetry.setEnabled(!waitingForProfile);
        binding.deleteProgress.setVisibility(busy || waitingForProfile ? View.VISIBLE : View.GONE);
        rendering = false;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
