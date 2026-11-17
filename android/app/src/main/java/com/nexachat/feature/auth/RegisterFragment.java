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
import com.nexachat.databinding.FragmentRegisterBinding;
import dagger.hilt.android.AndroidEntryPoint;

/**
 * Sign-up with an email address in two steps: the form (name, email, password) sends a code to the
 * address, and entering that code creates the account.
 */
@AndroidEntryPoint
public final class RegisterFragment extends Fragment {
    private FragmentRegisterBinding binding;
    private AuthViewModel viewModel;
    private boolean rendering;
    private boolean serverStarting;
    private String confirmation = "";
    private boolean confirmChecked;
    @Nullable private String returnInviteToken;
    private final Runnable resendTick = this::renderSignup;

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
        binding = FragmentRegisterBinding.inflate(inflater, container, false);
        if (state != null) confirmChecked = state.getBoolean("confirmChecked");
        return binding.getRoot();
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        viewModel = new ViewModelProvider(this).get(AuthViewModel.class);
        returnInviteToken = AuthNav.returnInviteToken(getArguments());
        binding.nameInput.addTextChangedListener(watcher(viewModel::onDisplayNameChange));
        binding.emailInput.addTextChangedListener(watcher(viewModel::onEmailChange));
        binding.passwordInput.addTextChangedListener(watcher(viewModel::onPasswordChange));
        binding.codeInput.addTextChangedListener(watcher(viewModel::onSignupCodeChange));
        binding.confirmPasswordInput.setText(confirmation);
        binding.confirmPasswordInput.addTextChangedListener(watcher(s -> { confirmation = s; render(viewModel.getUiState().getValue()); }));
        binding.confirmPasswordInput.setOnEditorActionListener((v, action, event) -> { if (action == EditorInfo.IME_ACTION_DONE) { submit(); return true; } return false; });
        binding.codeInput.setOnEditorActionListener((v, action, event) -> { if (action == EditorInfo.IME_ACTION_DONE) { viewModel.registerWithEmail(); return true; } return false; });
        binding.registerButton.setOnClickListener(v -> submit());
        binding.resendCodeButton.setOnClickListener(v -> viewModel.resendSignupCode());
        binding.changeEmailButton.setOnClickListener(v -> viewModel.changeSignupEmail());
        int googleVisibility = viewModel.isGoogleSignInAvailable() ? View.VISIBLE : View.GONE;
        binding.googleSignIn.setVisibility(googleVisibility);
        binding.authOrDivider.getRoot().setVisibility(googleVisibility);
        binding.googleSignIn.setOnClickListener(v -> viewModel.googleSignIn(requireActivity()));
        if (googleVisibility == View.VISIBLE) {
            viewModel.prefetchGoogleSignInNonce();
        }
        View.OnClickListener openLogin = v -> {
            Bundle args = AuthNav.inviteReturnArgs(returnInviteToken);
            NavHostFragment.findNavController(this).navigate(
                    R.id.loginFragment,
                    args,
                    AuthNav.authTabSwitchOptions());
        };
        binding.authModeLogin.setOnClickListener(openLogin);
        // Report this screen's mode as checked to accessibility; clicks never
        // toggle it back off (toggleCheckedStateOnClick is off in the layout).
        binding.authModeRegister.setChecked(true);
        if (binding.loadingState.getContentDescription() == null) {
            binding.loadingState.setContentDescription(
                    getString(AuthLoadingOverlay.contentDescriptionResId()));
        }
        viewModel.getUiState().observe(getViewLifecycleOwner(), this::render);
        viewModel.getEmailSignup().observe(getViewLifecycleOwner(), signup -> renderSignup());
        viewModel.getServerStarting().observe(getViewLifecycleOwner(), starting -> {
            serverStarting = Boolean.TRUE.equals(starting);
            AuthViewModel.UiState current = viewModel.getUiState().getValue();
            if (current != null) render(current);
        });
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

    private void submit() {
        AuthViewModel.EmailSignupState signup = viewModel.getEmailSignup().getValue();
        if (signup != null && signup.isCodeSent()) {
            viewModel.registerWithEmail();
            return;
        }
        confirmChecked = true;
        AuthViewModel.UiState s = viewModel.getUiState().getValue();
        if (!AuthFieldValidators.passwordsMatch(s.getPassword(), confirmation)) {
            render(s);
            binding.confirmPasswordLayout.setError(getString(R.string.error_password_mismatch));
            return;
        }
        viewModel.requestSignupCode();
    }

    private TextWatcher watcher(java.util.function.Consumer<String> change) { return new TextWatcher() {
        @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
        @Override public void onTextChanged(CharSequence s, int start, int before, int count) { if (!rendering) change.accept(s.toString()); }
        @Override public void afterTextChanged(Editable s) {}
    }; }
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

    private void render(AuthViewModel.UiState s) {
        if (binding == null || s == null) return;
        rendering = true;
        if (!binding.nameInput.getText().toString().equals(s.getDisplayName())) binding.nameInput.setText(s.getDisplayName());
        if (!binding.passwordInput.getText().toString().equals(s.getPassword())) binding.passwordInput.setText(s.getPassword());
        binding.nameLayout.setError(displayNameErrorText(s.getDisplayNameProblem()));
        binding.passwordLayout.setError(s.getPasswordError() ? getString(R.string.error_password_short) : null);
        boolean mismatch = confirmChecked && !AuthFieldValidators.passwordsMatch(s.getPassword(), confirmation);
        binding.confirmPasswordLayout.setError(mismatch ? getString(R.string.error_password_mismatch) : null);
        boolean googleEmailInUse = GoogleAuthErrors.isGoogleEmailInUse(s.getError());
        binding.googleEmailInUseError.getRoot().setVisibility(
                googleEmailInUse ? View.VISIBLE : View.GONE);
        boolean showBanner = s.getError() != null && !googleEmailInUse && !s.getLoading();
        binding.errorBanner.setVisibility(showBanner ? View.VISIBLE : View.GONE);
        if (showBanner) {
            binding.errorBanner.setText(ApiErrorText.text(requireContext(), s.getError()));
        }
        boolean notLoading = !s.getLoading();
        binding.nameInput.setEnabled(notLoading);
        binding.emailInput.setEnabled(notLoading);
        binding.passwordInput.setEnabled(notLoading);
        binding.confirmPasswordInput.setEnabled(notLoading);
        binding.codeInput.setEnabled(notLoading);
        binding.registerButton.setEnabled(notLoading);
        binding.googleSignIn.setEnabled(notLoading);
        binding.authModeLogin.setEnabled(notLoading);
        binding.changeEmailButton.setEnabled(notLoading);
        binding.loadingState.setLabel(
                s.getLoading() && serverStarting ? getString(R.string.server_starting) : null);
        binding.loadingState.setVisibility(s.getLoading() ? View.VISIBLE : View.GONE);
        rendering = false;
        renderSignup();
    }

    /** Switches between the form and the code step, and keeps the resend countdown current. */
    private void renderSignup() {
        if (binding == null) return;
        AuthViewModel.EmailSignupState signup = viewModel.getEmailSignup().getValue();
        AuthViewModel.UiState s = viewModel.getUiState().getValue();
        if (signup == null || s == null) return;
        rendering = true;
        if (!binding.emailInput.getText().toString().equals(signup.getEmail())) binding.emailInput.setText(signup.getEmail());
        binding.emailLayout.setError(signup.getEmailError() ? getString(R.string.recovery_email_invalid) : null);
        if (!binding.codeInput.getText().toString().equals(signup.getCode())) {
            binding.codeInput.setText(signup.getCode());
            binding.codeInput.setSelection(signup.getCode().length());
        }
        binding.codeLayout.setError(signup.getCodeError() ? getString(R.string.forgot_password_code_invalid) : null);
        boolean codeStep = signup.isCodeSent();
        boolean googleAvailable = viewModel.isGoogleSignInAvailable();
        int formVisibility = codeStep ? View.GONE : View.VISIBLE;
        binding.formFields.setVisibility(formVisibility);
        binding.googleSignIn.setVisibility(googleAvailable ? formVisibility : View.GONE);
        binding.authOrDivider.getRoot().setVisibility(googleAvailable ? formVisibility : View.GONE);
        binding.codeGroup.setVisibility(codeStep ? View.VISIBLE : View.GONE);
        binding.codeActions.setVisibility(codeStep ? View.VISIBLE : View.GONE);
        binding.registerButton.setText(codeStep ? R.string.action_create_account : R.string.signup_send_code);
        if (codeStep) {
            binding.codeSentText.setText(getString(R.string.recovery_email_otp_sent_to, signup.getEmail().trim()));
            int seconds = signup.resendSecondsRemaining(System.currentTimeMillis());
            binding.resendCodeButton.setEnabled(seconds == 0 && !s.getLoading());
            binding.resendCodeButton.setText(seconds == 0
                    ? getString(R.string.recovery_email_resend)
                    : getString(R.string.recovery_email_resend_countdown, seconds));
            binding.getRoot().removeCallbacks(resendTick);
            if (seconds > 0) binding.getRoot().postDelayed(resendTick, 1000L);
        }
        rendering = false;
    }

    @Override public void onSaveInstanceState(@NonNull Bundle out) { super.onSaveInstanceState(out); out.putBoolean("confirmChecked", confirmChecked); }
    @Override public void onDestroyView() {
        if (binding != null) binding.getRoot().removeCallbacks(resendTick);
        super.onDestroyView();
        binding = null;
    }
}
