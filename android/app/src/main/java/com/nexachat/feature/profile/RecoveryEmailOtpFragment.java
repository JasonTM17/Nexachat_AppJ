package com.nexachat.feature.profile;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;
import com.nexachat.R;
import com.nexachat.core.common.ApiError;
import com.nexachat.databinding.FragmentRecoveryEmailOtpBinding;
import com.nexachat.feature.auth.ApiErrorText;
import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public final class RecoveryEmailOtpFragment extends Fragment {
    private FragmentRecoveryEmailOtpBinding binding;
    private RecoveryEmailOtpViewModel viewModel;
    private boolean rendering;
    private static final String STATE_NOTED_COOLDOWN = "recovery_otp_noted_cooldown";
    private boolean notedInitialCooldown;

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle state) {
        binding = FragmentRecoveryEmailOtpBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        viewModel = new ViewModelProvider(this).get(RecoveryEmailOtpViewModel.class);
        binding.recoveryOtpToolbar.setTitle(R.string.recovery_email_title);
        binding.recoveryOtpToolbar.setNavigationOnClickListener(
                v -> NavHostFragment.findNavController(this).navigateUp());
        binding.recoveryOtpInput.addTextChangedListener(new OtpTextWatcher());
        binding.recoveryOtpInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                if (RecoveryEmailOtpSanitizer.isComplete(binding.recoveryOtpInput.getText().toString())) {
                    viewModel.verify();
                    return true;
                }
            }
            return false;
        });
        binding.recoveryOtpVerify.setOnClickListener(v -> viewModel.verify());
        binding.recoveryOtpResend.setOnClickListener(v -> viewModel.resendCode());
        if (state != null) {
            notedInitialCooldown = state.getBoolean(STATE_NOTED_COOLDOWN, false);
        }
        if (!notedInitialCooldown) {
            notedInitialCooldown = true;
            viewModel.noteCodeRequested();
        }
        viewModel.getUiState().observe(getViewLifecycleOwner(), this::render);
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_NOTED_COOLDOWN, notedInitialCooldown);
    }

    private void render(RecoveryEmailOtpViewModel.UiState state) {
        rendering = true;
        if (!state.getEmail().isEmpty()) {
            binding.recoveryOtpTarget.setText(
                    getString(R.string.recovery_email_otp_sent_to, state.getEmail()));
        }
        if (!binding.recoveryOtpInput.getText().toString().equals(state.getOtp())) {
            binding.recoveryOtpInput.setText(state.getOtp());
            binding.recoveryOtpInput.setSelection(state.getOtp().length());
        }
        binding.recoveryOtpLayout.setError(state.isCodeError()
                ? getString(R.string.recovery_email_code_invalid)
                : null);
        boolean complete = RecoveryEmailOtpSanitizer.isComplete(state.getOtp());
        binding.recoveryOtpVerify.setEnabled(complete && !state.isVerifying() && !state.isResending());
        binding.recoveryOtpResend.setEnabled(
                !state.isResending() && !state.isVerifying() && state.getResendSecondsRemaining() <= 0);
        if (state.getResendSecondsRemaining() > 0) {
            binding.recoveryOtpResend.setText(getString(
                    R.string.recovery_email_resend_countdown,
                    state.getResendSecondsRemaining()));
        } else {
            binding.recoveryOtpResend.setText(R.string.recovery_email_resend);
        }
        ApiError error = state.getError();
        if (error != null && !state.isCodeError()) {
            binding.recoveryOtpError.setVisibility(View.VISIBLE);
            binding.recoveryOtpError.setText(ApiErrorText.text(requireContext(), error));
        } else {
            binding.recoveryOtpError.setVisibility(View.GONE);
        }
        if (state.isVerified()) {
            viewModel.consumeVerified();
            Bundle result = new Bundle();
            result.putBoolean(RecoveryEmailManageViewModel.RESULT_PROFILE_RELOAD, true);
            getParentFragmentManager().setFragmentResult(
                    RecoveryEmailManageViewModel.RESULT_PROFILE_RELOAD, result);
            NavHostFragment.findNavController(this).popBackStack(R.id.profileFragment, false);
        }
        rendering = false;
    }

    private final class OtpTextWatcher implements TextWatcher {
        private boolean selfChange;

        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
            if (selfChange || rendering) {
                return;
            }
            String sanitized = RecoveryEmailOtpSanitizer.sanitize(s.toString());
            if (!sanitized.equals(s.toString())) {
                selfChange = true;
                binding.recoveryOtpInput.setText(sanitized);
                binding.recoveryOtpInput.setSelection(sanitized.length());
                selfChange = false;
            }
            viewModel.onOtpChanged(sanitized);
        }

        @Override
        public void afterTextChanged(Editable e) {
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
