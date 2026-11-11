package com.nexachat.feature.auth;

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
import com.nexachat.databinding.FragmentForgotPasswordBinding;
import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public final class ForgotPasswordFragment extends Fragment {
    private FragmentForgotPasswordBinding binding;
    private ForgotPasswordViewModel viewModel;
    private boolean rendering;

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle state) {
        binding = FragmentForgotPasswordBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        viewModel = new ViewModelProvider(this).get(ForgotPasswordViewModel.class);
        binding.forgotToolbar.setTitle(R.string.forgot_password_title);
        binding.forgotToolbar.setNavigationOnClickListener(
                v -> NavHostFragment.findNavController(this).navigateUp());
        binding.forgotPhoneInput.addTextChangedListener(watcher(viewModel::onPhoneChanged));
        binding.forgotPhoneInput.setOnEditorActionListener((v, action, event) -> {
            if (action == EditorInfo.IME_ACTION_DONE) {
                viewModel.sendCode();
                return true;
            }
            return false;
        });
        binding.forgotOtpInput.addTextChangedListener(new OtpTextWatcher());
        binding.forgotNewPasswordInput.addTextChangedListener(watcher(viewModel::onNewPasswordChanged));
        binding.forgotConfirmPasswordInput.addTextChangedListener(
                watcher(viewModel::onConfirmPasswordChanged));
        binding.forgotSendCode.setOnClickListener(v -> viewModel.sendCode());
        binding.forgotSubmit.setOnClickListener(v -> viewModel.submitReset());
        binding.forgotResend.setOnClickListener(v -> viewModel.resendCode());
        binding.forgotChangePhone.setOnClickListener(v -> viewModel.changePhoneEntry());
        binding.forgotConfirmPasswordInput.setOnEditorActionListener((v, action, event) -> {
            if (action == EditorInfo.IME_ACTION_DONE) {
                viewModel.submitReset();
                return true;
            }
            return false;
        });
        viewModel.getUiState().observe(getViewLifecycleOwner(), this::render);
        viewModel.getEvents().observe(getViewLifecycleOwner(), event -> {
            if (event == ForgotPasswordViewModel.Event.CodeSent) {
                binding.forgotNeutralMessage.announceForAccessibility(
                        getString(R.string.forgot_password_neutral_sent));
                binding.forgotOtpInput.requestFocus();
            } else if (event == ForgotPasswordViewModel.Event.PasswordChanged) {
                Bundle result = new Bundle();
                result.putBoolean(ForgotPasswordViewModel.RESULT_PASSWORD_CHANGED, true);
                getParentFragmentManager().setFragmentResult(
                        ForgotPasswordViewModel.RESULT_PASSWORD_CHANGED, result);
                NavHostFragment.findNavController(this).navigateUp();
            }
        });
    }

    private TextWatcher watcher(java.util.function.Consumer<String> change) {
        return new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (!rendering) {
                    change.accept(s.toString());
                }
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        };
    }

    private void render(ForgotPasswordViewModel.UiState state) {
        rendering = true;
        if (!binding.forgotPhoneInput.getText().toString().equals(state.getPhone())) {
            binding.forgotPhoneInput.setText(state.getPhone());
        }
        binding.forgotPhoneLayout.setError(state.isPhoneInvalid()
                ? getString(R.string.error_phone_invalid)
                : null);
        binding.forgotPhoneInput.setEnabled(!state.isCodeRequested() && !state.isSending());

        boolean afterSend = state.isCodeRequested();
        binding.forgotSendCode.setVisibility(afterSend ? View.GONE : View.VISIBLE);
        binding.forgotSendCode.setEnabled(!state.isSending());
        binding.forgotNeutralMessage.setVisibility(afterSend ? View.VISIBLE : View.GONE);
        binding.forgotResetSection.setVisibility(afterSend ? View.VISIBLE : View.GONE);
        binding.forgotChangePhone.setVisibility(afterSend ? View.VISIBLE : View.GONE);

        if (afterSend) {
            if (!binding.forgotOtpInput.getText().toString().equals(state.getOtp())) {
                binding.forgotOtpInput.setText(state.getOtp());
                binding.forgotOtpInput.setSelection(state.getOtp().length());
            }
            binding.forgotOtpLayout.setError(state.isCodeError()
                    ? getString(R.string.forgot_password_code_invalid)
                    : null);
            if (!binding.forgotNewPasswordInput.getText().toString().equals(state.getNewPassword())) {
                binding.forgotNewPasswordInput.setText(state.getNewPassword());
            }
            if (!binding.forgotConfirmPasswordInput.getText().toString()
                    .equals(state.getConfirmPassword())) {
                binding.forgotConfirmPasswordInput.setText(state.getConfirmPassword());
            }
            binding.forgotNewPasswordLayout.setError(state.isPasswordTooLong()
                    ? getString(R.string.forgot_password_password_too_long)
                    : state.isPasswordInvalid()
                            ? getString(R.string.error_password_short)
                            : null);
            binding.forgotConfirmPasswordLayout.setError(state.isConfirmMismatch()
                    ? getString(R.string.error_password_mismatch)
                    : null);
            boolean canSubmit = PasswordResetOtpSanitizer.isComplete(state.getOtp());
            binding.forgotSubmit.setEnabled(
                    canSubmit && !state.isSubmitting() && !state.isResending());
            binding.forgotResend.setEnabled(
                    !state.isResending()
                            && !state.isSubmitting()
                            && state.getResendSecondsRemaining() <= 0);
            if (state.getResendSecondsRemaining() > 0) {
                binding.forgotResend.setText(getString(
                        R.string.forgot_password_resend_countdown,
                        state.getResendSecondsRemaining()));
            } else {
                binding.forgotResend.setText(R.string.forgot_password_resend);
            }
            boolean busy = state.isSubmitting();
            binding.forgotOtpInput.setEnabled(!busy);
            binding.forgotNewPasswordInput.setEnabled(!busy);
            binding.forgotConfirmPasswordInput.setEnabled(!busy);
        }

        ApiError error = state.getError();
        if (error != null && !state.isCodeError()) {
            binding.forgotError.setVisibility(View.VISIBLE);
            binding.forgotError.setText(ApiErrorText.text(requireContext(), error));
        } else {
            binding.forgotError.setVisibility(View.GONE);
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
            String sanitized = PasswordResetOtpSanitizer.sanitize(s.toString());
            if (!sanitized.equals(s.toString())) {
                selfChange = true;
                binding.forgotOtpInput.setText(sanitized);
                binding.forgotOtpInput.setSelection(sanitized.length());
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
