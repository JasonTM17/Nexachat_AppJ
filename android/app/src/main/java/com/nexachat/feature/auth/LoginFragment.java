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
import com.google.android.material.snackbar.Snackbar;
import com.nexachat.R;
import com.nexachat.databinding.FragmentLoginBinding;
import dagger.hilt.android.AndroidEntryPoint;
import javax.inject.Inject;

@AndroidEntryPoint
public final class LoginFragment extends Fragment {
    @Inject LoginNotice loginNotice;

    private FragmentLoginBinding binding;
    private AuthViewModel viewModel;
    private boolean rendering;
    private boolean sessionExpired;
    private boolean serverStarting;
    @Nullable private String returnInviteToken;
    @Nullable private Integer persistedNoticeResId;
    private static final String STATE_NOTICE_RES_ID = "loginNoticeResId";

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
        binding = FragmentLoginBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        viewModel = new ViewModelProvider(this).get(AuthViewModel.class);
        returnInviteToken = AuthNav.returnInviteToken(getArguments());
        sessionExpired = getArguments() != null
                && getArguments().getBoolean("showSessionExpired", false);
        binding.phoneInput.addTextChangedListener(watcher(viewModel::onPhoneChange));
        binding.passwordInput.addTextChangedListener(watcher(viewModel::onPasswordChange));
        binding.passwordInput.setOnEditorActionListener((v, action, event) -> { if (action == EditorInfo.IME_ACTION_DONE) { submit(); return true; } return false; });
        binding.primaryAction.setText(R.string.action_sign_in);
        binding.primaryAction.setOnClickListener(v -> submit());
        int googleVisibility = viewModel.isGoogleSignInAvailable() ? View.VISIBLE : View.GONE;
        binding.googleSignIn.setVisibility(googleVisibility);
        binding.authOrDivider.getRoot().setVisibility(googleVisibility);
        binding.googleSignIn.setOnClickListener(v -> viewModel.googleSignIn(requireActivity()));
        binding.secondaryAction.setText(R.string.action_create_account);
        View.OnClickListener openRegister = v -> {
            Bundle args = AuthNav.inviteReturnArgs(returnInviteToken);
            NavHostFragment.findNavController(this).navigate(
                    R.id.registerFragment,
                    args,
                    AuthNav.authTabSwitchOptions());
        };
        binding.secondaryAction.setOnClickListener(openRegister);
        binding.authModeRegister.setOnClickListener(openRegister);
        if (binding.loadingState.getContentDescription() == null) {
            binding.loadingState.setContentDescription(
                    getString(AuthLoadingOverlay.contentDescriptionResId()));
        }
        binding.forgotPasswordAction.setOnClickListener(v -> {
            String phone = binding.phoneInput.getText() == null
                    ? "" : binding.phoneInput.getText().toString();
            Bundle args = new Bundle();
            args.putString(ForgotPasswordViewModel.ARG_PREFILL_PHONE, phone);
            NavHostFragment.findNavController(this).navigate(R.id.forgotPasswordFragment, args);
        });
        getParentFragmentManager().setFragmentResultListener(
                ForgotPasswordViewModel.RESULT_PASSWORD_CHANGED,
                getViewLifecycleOwner(),
                (key, bundle) -> Snackbar.make(
                                binding.getRoot(), R.string.forgot_password_changed, Snackbar.LENGTH_LONG)
                        .show());
        viewModel.getUiState().observe(getViewLifecycleOwner(), this::render);
        viewModel.getServerStarting().observe(getViewLifecycleOwner(), starting -> {
            serverStarting = Boolean.TRUE.equals(starting);
            AuthViewModel.UiState current = viewModel.getUiState().getValue();
            if (current != null) render(current);
        });
        if (state != null && state.containsKey(STATE_NOTICE_RES_ID)) {
            persistedNoticeResId = state.getInt(STATE_NOTICE_RES_ID);
        }
        if (persistedNoticeResId == null) {
            persistedNoticeResId = loginNotice.consume();
        }
        if (persistedNoticeResId != null) {
            binding.noticeBanner.setText(persistedNoticeResId);
            binding.noticeBanner.setVisibility(View.VISIBLE);
        }
        viewModel.getEvents().observe(getViewLifecycleOwner(), this::onAuthEvent);
        if (viewModel.hasPendingCompleteProfile()) {
            navigateToCompleteProfile();
        }
    }

    private void onAuthEvent(AuthViewModel.Event event) {
        if (event == AuthViewModel.Event.CompleteProfile) {
            navigateToCompleteProfile();
            return;
        }
        if (event == AuthViewModel.Event.LoggedIn) {
            AuthNav.openSignedIn(NavHostFragment.findNavController(this), returnInviteToken);
        }
    }

    private void navigateToCompleteProfile() {
        if (!viewModel.onCompleteProfileNavigationHandled()) {
            return;
        }
        NavHostFragment.findNavController(this).navigate(
                R.id.completeProfileFragment, AuthNav.inviteReturnArgs(returnInviteToken),
                AuthNav.signedInOptions());
    }

    private TextWatcher watcher(java.util.function.Consumer<String> change) { return new TextWatcher() {
        @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
        @Override public void onTextChanged(CharSequence s, int start, int before, int count) { if (!rendering) change.accept(s.toString()); }
        @Override public void afterTextChanged(Editable s) {}
    }; }

    private void submit() {
        String phone = AuthFieldValidators.normalizeE164(binding.phoneInput.getText().toString());
        binding.phoneLayout.setError(AuthFieldValidators.isE164(phone) ? null : getString(R.string.error_phone_invalid));
        if (!AuthFieldValidators.isE164(phone)) return;
        viewModel.onPhoneChange(phone);
        viewModel.login();
    }


    private void render(AuthViewModel.UiState state) {
        rendering = true;
        if (!binding.phoneInput.getText().toString().equals(state.getPhone())) binding.phoneInput.setText(state.getPhone());
        if (!binding.passwordInput.getText().toString().equals(state.getPassword())) binding.passwordInput.setText(state.getPassword());
        binding.phoneLayout.setError(state.getPhoneError() ? getString(R.string.error_phone_required) : null);
        binding.passwordLayout.setError(state.getPasswordError() ? getString(R.string.error_password_short) : null);
        boolean googleEmailInUse = GoogleAuthErrors.isGoogleEmailInUse(state.getError());
        binding.googleEmailInUseError.getRoot().setVisibility(
                googleEmailInUse ? View.VISIBLE : View.GONE);
        LoginErrorBannerState banner = LoginErrorBannerState.resolve(
                sessionExpired, state.getError(), serverStarting);
        binding.errorBanner.setVisibility(banner.isVisible() ? View.VISIBLE : View.GONE);
        if (banner.isVisible()) {
            binding.errorBanner.setText(banner.getMessageRes());
        }
        if (persistedNoticeResId != null) {
            binding.noticeBanner.setText(persistedNoticeResId);
            binding.noticeBanner.setVisibility(View.VISIBLE);
        }
        boolean notLoading = !state.getLoading();
        binding.phoneInput.setEnabled(notLoading);
        binding.passwordInput.setEnabled(notLoading);
        binding.primaryAction.setEnabled(notLoading);
        binding.googleSignIn.setEnabled(notLoading);
        binding.secondaryAction.setEnabled(notLoading);
        binding.forgotPasswordAction.setEnabled(notLoading);
        binding.authModeRegister.setEnabled(notLoading);
        binding.loadingState.setVisibility(state.getLoading() ? View.VISIBLE : View.GONE);
        binding.primaryAction.setText(R.string.action_sign_in);
        rendering = false;
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (persistedNoticeResId != null) {
            outState.putInt(STATE_NOTICE_RES_ID, persistedNoticeResId);
        }
    }

    @Override public void onDestroyView() { super.onDestroyView(); binding = null; }
}
