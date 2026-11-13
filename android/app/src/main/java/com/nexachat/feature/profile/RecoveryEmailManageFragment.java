package com.nexachat.feature.profile;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.nexachat.R;
import com.nexachat.core.common.ApiError;
import com.nexachat.core.network.dto.UserDto;
import com.nexachat.databinding.FragmentRecoveryEmailManageBinding;
import com.nexachat.feature.auth.ApiErrorText;
import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public final class RecoveryEmailManageFragment extends Fragment {
    private FragmentRecoveryEmailManageBinding binding;
    private RecoveryEmailManageViewModel viewModel;

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle state) {
        binding = FragmentRecoveryEmailManageBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        viewModel = new ViewModelProvider(this).get(RecoveryEmailManageViewModel.class);
        binding.recoveryEmailToolbar.setTitle(R.string.recovery_email_title);
        binding.recoveryEmailToolbar.setNavigationOnClickListener(
                v -> NavHostFragment.findNavController(this).navigateUp());
        binding.recoveryEmailChange.setOnClickListener(v -> navigateToForm());
        binding.recoveryEmailAdd.setOnClickListener(v -> navigateToForm());
        binding.recoveryEmailRemove.setOnClickListener(v -> confirmRemove());
        binding.recoveryEmailRetry.setOnClickListener(v -> viewModel.load());
        viewModel.getUiState().observe(getViewLifecycleOwner(), this::render);
    }

    private void navigateToForm() {
        NavHostFragment.findNavController(this)
                .navigate(R.id.action_recoveryEmailManage_to_recoveryEmailForm);
    }

    private void confirmRemove() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.recovery_email_remove)
                .setMessage(R.string.recovery_email_remove_confirm)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.recovery_email_remove, (dialog, which) -> viewModel.removeEmail())
                .show();
    }

    private void render(RecoveryEmailManageViewModel.UiState state) {
        UserDto user = state.getUser();
        boolean initialLoading = RecoveryEmailManageUiRules.showInitialLoading(
                state.isLoading(), user, state.getError());
        boolean loadFailed = !state.isLoading() && user == null && state.getError() != null;
        boolean verified = user != null && user.hasVerifiedRecoveryEmail();

        if (loadFailed) {
            binding.recoveryEmailStatus.setText(R.string.recovery_email_load_failed);
            binding.recoveryEmailChange.setVisibility(View.GONE);
            binding.recoveryEmailRemove.setVisibility(View.GONE);
            binding.recoveryEmailAdd.setVisibility(View.GONE);
            binding.recoveryEmailRetry.setVisibility(View.VISIBLE);
        } else if (initialLoading) {
            binding.recoveryEmailStatus.setText(R.string.voice_status_loading);
            binding.recoveryEmailChange.setVisibility(View.GONE);
            binding.recoveryEmailRemove.setVisibility(View.GONE);
            binding.recoveryEmailAdd.setVisibility(View.GONE);
            binding.recoveryEmailRetry.setVisibility(View.GONE);
        } else {
            binding.recoveryEmailStatus.setText(verified
                    ? getString(R.string.recovery_email_verified_status, user.getEmail())
                    : getString(R.string.recovery_email_none_status));
            binding.recoveryEmailChange.setVisibility(verified ? View.VISIBLE : View.GONE);
            binding.recoveryEmailRemove.setVisibility(verified ? View.VISIBLE : View.GONE);
            binding.recoveryEmailAdd.setVisibility(verified ? View.GONE : View.VISIBLE);
            binding.recoveryEmailRetry.setVisibility(View.GONE);
            binding.recoveryEmailAdd.setEnabled(!state.isLoading());
        }

        binding.recoveryEmailRemove.setEnabled(!state.isRemoving());
        binding.recoveryEmailChange.setEnabled(!state.isRemoving());

        ApiError error = state.getError();
        binding.recoveryEmailError.setVisibility(error == null ? View.GONE : View.VISIBLE);
        if (error != null) {
            binding.recoveryEmailError.setText(ApiErrorText.text(requireContext(), error));
        }
        if (state.isRemoveComplete()) {
            viewModel.consumeRemoveComplete();
            Bundle result = new Bundle();
            result.putBoolean(RecoveryEmailManageViewModel.RESULT_PROFILE_RELOAD, true);
            getParentFragmentManager().setFragmentResult(
                    RecoveryEmailManageViewModel.RESULT_PROFILE_RELOAD, result);
            NavHostFragment.findNavController(this).popBackStack(R.id.profileFragment, false);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
