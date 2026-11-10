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
import com.nexachat.databinding.FragmentLoginBinding;
import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public final class LoginFragment extends Fragment {
    private FragmentLoginBinding binding;
    private AuthViewModel viewModel;
    private boolean rendering;
    @Nullable private String returnInviteToken;

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
        binding = FragmentLoginBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        viewModel = new ViewModelProvider(this).get(AuthViewModel.class);
        returnInviteToken = getArguments() == null
                ? null : getArguments().getString("returnInviteToken");
        binding.phoneInput.addTextChangedListener(watcher(viewModel::onPhoneChange));
        binding.passwordInput.addTextChangedListener(watcher(viewModel::onPasswordChange));
        binding.passwordInput.setOnEditorActionListener((v, action, event) -> { if (action == EditorInfo.IME_ACTION_DONE) { submit(); return true; } return false; });
        binding.primaryAction.setText(R.string.action_sign_in);
        binding.primaryAction.setOnClickListener(v -> submit());
        binding.googleSignIn.setVisibility(
                viewModel.isGoogleSignInAvailable() ? View.VISIBLE : View.GONE);
        binding.googleSignIn.setOnClickListener(v -> viewModel.googleSignIn(requireActivity()));
        binding.secondaryAction.setText(R.string.action_create_account);
        binding.secondaryAction.setOnClickListener(v -> {
            Bundle args = inviteReturnArgs();
            NavHostFragment.findNavController(this).navigate(
                    R.id.registerFragment, args.isEmpty() ? null : args);
        });
        viewModel.getUiState().observe(getViewLifecycleOwner(), this::render);
        viewModel.getEvents().observe(getViewLifecycleOwner(), e -> {
            if (e == AuthViewModel.Event.LoggedIn) {
                int destination = returnInviteToken == null
                        ? R.id.inboxFragment : R.id.inviteJoinFragment;
                Bundle args = returnInviteToken == null ? null : new Bundle();
                if (args != null) args.putString("token", returnInviteToken);
                NavHostFragment.findNavController(this).navigate(destination, args, AuthNav.signedInOptions());
            }
        });
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

    private Bundle inviteReturnArgs() {
        Bundle args = new Bundle();
        if (returnInviteToken != null) args.putString("returnInviteToken", returnInviteToken);
        return args;
    }

    private void render(AuthViewModel.UiState state) {
        rendering = true;
        if (!binding.phoneInput.getText().toString().equals(state.getPhone())) binding.phoneInput.setText(state.getPhone());
        if (!binding.passwordInput.getText().toString().equals(state.getPassword())) binding.passwordInput.setText(state.getPassword());
        binding.phoneLayout.setError(state.getPhoneError() ? getString(R.string.error_phone_required) : null);
        binding.passwordLayout.setError(state.getPasswordError() ? getString(R.string.error_password_short) : null);
        binding.errorBanner.setVisibility(state.getError() == null ? View.GONE : View.VISIBLE);
        if (state.getError() != null) binding.errorBanner.setText(ApiErrorText.text(requireContext(), state.getError()));
        boolean notLoading = !state.getLoading();
        binding.phoneInput.setEnabled(notLoading);
        binding.passwordInput.setEnabled(notLoading);
        binding.primaryAction.setEnabled(notLoading);
        binding.googleSignIn.setEnabled(notLoading);
        binding.secondaryAction.setEnabled(notLoading);
        binding.primaryAction.setText(R.string.action_sign_in);
        rendering = false;
    }

    @Override public void onDestroyView() { super.onDestroyView(); binding = null; }
}
