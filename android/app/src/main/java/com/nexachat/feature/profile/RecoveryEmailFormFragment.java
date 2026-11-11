package com.nexachat.feature.profile;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;
import com.nexachat.R;
import com.nexachat.core.common.ApiError;
import com.nexachat.databinding.FragmentRecoveryEmailFormBinding;
import com.nexachat.feature.auth.ApiErrorText;
import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public final class RecoveryEmailFormFragment extends Fragment {
    private FragmentRecoveryEmailFormBinding binding;
    private RecoveryEmailFormViewModel viewModel;
    private boolean rendering;

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle state) {
        binding = FragmentRecoveryEmailFormBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        viewModel = new ViewModelProvider(this).get(RecoveryEmailFormViewModel.class);
        binding.recoveryFormToolbar.setTitle(R.string.recovery_email_title);
        binding.recoveryFormToolbar.setNavigationOnClickListener(
                v -> NavHostFragment.findNavController(this).navigateUp());
        binding.recoveryFormEmailInput.addTextChangedListener(new EmailTextWatcher());
        binding.recoveryFormSend.setOnClickListener(v -> viewModel.sendCode());
        viewModel.getUiState().observe(getViewLifecycleOwner(), this::render);
    }

    private void render(RecoveryEmailFormViewModel.UiState state) {
        rendering = true;
        if (!binding.recoveryFormEmailInput.getText().toString().equals(state.getEmailInput())) {
            binding.recoveryFormEmailInput.setText(state.getEmailInput());
            binding.recoveryFormEmailInput.setSelection(state.getEmailInput().length());
        }
        binding.recoveryFormEmailLayout.setError(state.isEmailInvalid()
                ? getString(R.string.recovery_email_invalid)
                : null);
        binding.recoveryFormEmailInput.setEnabled(!state.isSending());
        binding.recoveryFormSend.setEnabled(!state.isSending());
        ApiError error = state.getError();
        if (error != null) {
            binding.recoveryFormError.setVisibility(View.VISIBLE);
            binding.recoveryFormError.setText(ApiErrorText.text(requireContext(), error));
        } else {
            binding.recoveryFormError.setVisibility(View.GONE);
        }
        if (state.isCodeSent() && state.getPendingEmail() != null) {
            viewModel.consumeCodeSent();
            Bundle args = new Bundle();
            args.putString(RecoveryEmailOtpViewModel.ARG_EMAIL, state.getPendingEmail());
            NavHostFragment.findNavController(this)
                    .navigate(R.id.action_recoveryEmailForm_to_recoveryEmailOtp, args);
        }
        rendering = false;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    private final class EmailTextWatcher implements TextWatcher {
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
            if (!rendering) {
                viewModel.onEmailChanged(s.toString());
            }
        }

        @Override
        public void afterTextChanged(Editable e) {
        }
    }
}
