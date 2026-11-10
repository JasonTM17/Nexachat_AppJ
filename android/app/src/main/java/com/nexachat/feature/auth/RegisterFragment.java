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

@AndroidEntryPoint
public final class RegisterFragment extends Fragment {
    private FragmentRegisterBinding binding;
    private AuthViewModel viewModel;
    private boolean rendering;
    private String confirmation = "";
    private boolean confirmChecked;
    @Nullable private String returnInviteToken;

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
        binding = FragmentRegisterBinding.inflate(inflater, container, false);
        if (state != null) { confirmation = state.getString("confirm", ""); confirmChecked = state.getBoolean("confirmChecked"); }
        return binding.getRoot();
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        viewModel = new ViewModelProvider(this).get(AuthViewModel.class);
        returnInviteToken = getArguments() == null
                ? null : getArguments().getString("returnInviteToken");
        binding.nameInput.addTextChangedListener(watcher(viewModel::onDisplayNameChange));
        binding.phoneInput.addTextChangedListener(watcher(viewModel::onPhoneChange));
        binding.passwordInput.addTextChangedListener(watcher(viewModel::onPasswordChange));
        binding.confirmPasswordInput.setText(confirmation);
        binding.confirmPasswordInput.addTextChangedListener(watcher(s -> { confirmation = s; render(viewModel.getUiState().getValue()); }));
        binding.confirmPasswordInput.setOnEditorActionListener((v, action, event) -> { if (action == EditorInfo.IME_ACTION_DONE) { submit(); return true; } return false; });
        binding.registerButton.setOnClickListener(v -> submit());
        binding.secondaryAction.setOnClickListener(v -> {
            Bundle args = inviteReturnArgs();
            NavHostFragment.findNavController(this).navigate(
                    R.id.loginFragment, args.isEmpty() ? null : args);
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

    private void submit() {
        confirmChecked = true;
        String phone = AuthFieldValidators.normalizeE164(binding.phoneInput.getText().toString());
        binding.phoneLayout.setError(AuthFieldValidators.isE164(phone) ? null : getString(R.string.error_phone_invalid));
        if (!AuthFieldValidators.isE164(phone)) return;
        viewModel.onPhoneChange(phone);
        AuthViewModel.UiState s = viewModel.getUiState().getValue(); render(s);
        if (AuthFieldValidators.passwordsMatch(s.getPassword(), confirmation)) viewModel.register();
        else binding.confirmPasswordLayout.setError(getString(R.string.error_password_mismatch));
    }

    private Bundle inviteReturnArgs() {
        Bundle args = new Bundle();
        if (returnInviteToken != null) args.putString("returnInviteToken", returnInviteToken);
        return args;
    }
    private TextWatcher watcher(java.util.function.Consumer<String> change) { return new TextWatcher() {
        @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
        @Override public void onTextChanged(CharSequence s, int start, int before, int count) { if (!rendering) change.accept(s.toString()); }
        @Override public void afterTextChanged(Editable s) {}
    }; }
    private void render(AuthViewModel.UiState s) {
        rendering = true;
        if (!binding.nameInput.getText().toString().equals(s.getDisplayName())) binding.nameInput.setText(s.getDisplayName());
        if (!binding.phoneInput.getText().toString().equals(s.getPhone())) binding.phoneInput.setText(s.getPhone());
        if (!binding.passwordInput.getText().toString().equals(s.getPassword())) binding.passwordInput.setText(s.getPassword());
        binding.nameLayout.setError(s.getDisplayNameError() ? getString(R.string.error_display_name_required) : null);
        binding.phoneLayout.setError(s.getPhoneError() ? getString(R.string.error_phone_required) : null);
        binding.passwordLayout.setError(s.getPasswordError() ? getString(R.string.error_password_short) : null);
        boolean mismatch = confirmChecked && !AuthFieldValidators.passwordsMatch(s.getPassword(), confirmation);
        binding.confirmPasswordLayout.setError(mismatch ? getString(R.string.error_password_mismatch) : null);
        binding.errorBanner.setVisibility(s.getError() == null ? View.GONE : View.VISIBLE);
        if (s.getError() != null) binding.errorBanner.setText(ApiErrorText.text(requireContext(), s.getError()));
        boolean notLoading = !s.getLoading();
        binding.nameInput.setEnabled(notLoading);
        binding.phoneInput.setEnabled(notLoading);
        binding.passwordInput.setEnabled(notLoading);
        binding.confirmPasswordInput.setEnabled(notLoading);
        binding.registerButton.setEnabled(notLoading);
        binding.secondaryAction.setEnabled(notLoading);
        rendering = false;
    }
    @Override public void onSaveInstanceState(@NonNull Bundle out) { super.onSaveInstanceState(out); out.putString("confirm", confirmation); out.putBoolean("confirmChecked", confirmChecked); }
    @Override public void onDestroyView() { super.onDestroyView(); binding = null; }
}
