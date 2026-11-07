package com.nexachat.feature.profile;

import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.FlowLiveDataConversions;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;
import com.nexachat.R;
import com.nexachat.core.common.ApiError;
import com.nexachat.core.network.dto.UserDto;
import com.nexachat.core.ui.ThemeMapper;
import com.nexachat.data.ThemeMode;
import com.nexachat.databinding.FragmentProfileBinding;
import com.nexachat.feature.auth.ApiErrorText;
import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public final class ProfileFragment extends Fragment {
    private FragmentProfileBinding binding;
    private ProfileViewModel viewModel;
    private AppSettingsViewModel settingsViewModel;
    private ActivityResultLauncher<String> avatarPicker;

    public ProfileFragment() {
        avatarPicker = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> { if (uri != null && viewModel != null) viewModel.uploadAvatar(uri); });
    }

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
        binding = FragmentProfileBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        viewModel = new ViewModelProvider(this).get(ProfileViewModel.class);
        settingsViewModel = new ViewModelProvider(requireActivity()).get(AppSettingsViewModel.class);
        binding.profileToolbar.setTitle(R.string.profile_title);
        binding.profileToolbar.setNavigationOnClickListener(v -> NavHostFragment.findNavController(this).navigateUp());
        binding.profileAvatar.setOnClickListener(v -> avatarPicker.launch("image/*"));
        binding.editName.setOnClickListener(v -> viewModel.startEditName());
        binding.nameInput.addTextChangedListener(new SimpleTextWatcher(s -> viewModel.onNameInputChange(s)));
        binding.saveName.setOnClickListener(v -> viewModel.saveDisplayName());
        binding.cancelName.setOnClickListener(v -> viewModel.cancelEditName());
        binding.changePassword.setOnClickListener(v -> { viewModel.openPasswordDialog(); showPasswordDialog(); });
        binding.languageRow.setTitle(getString(R.string.settings_language));
        binding.languageRow.setOnClickListener(v -> NavHostFragment.findNavController(this).navigate(R.id.languageSettingsFragment));
        binding.themeRow.setTitle(getString(R.string.settings_theme_title));
        binding.themeRow.setOnClickListener(v -> showThemeDialog());
        binding.showPresence.setOnCheckedChangeListener((button, checked) -> { if (button.isPressed()) viewModel.setShowPresence(checked); });
        binding.logoutRow.setTitle(getString(R.string.action_logout));
        binding.logoutRow.setOnClickListener(v -> new AlertDialog.Builder(requireContext()).setTitle(R.string.action_logout).setMessage(R.string.action_logout_confirm).setNegativeButton(R.string.action_cancel, null).setPositiveButton(R.string.action_logout, (d,w) -> viewModel.logout()).show());
        binding.logoutAllRow.setTitle(getString(R.string.action_logout_all));
        binding.logoutAllRow.setOnClickListener(v -> new AlertDialog.Builder(requireContext()).setTitle(R.string.action_logout_all).setMessage(R.string.profile_logout_all_confirm).setNegativeButton(R.string.action_cancel, null).setPositiveButton(R.string.action_logout_all, (d,w) -> viewModel.logoutAll()).show());
        FlowLiveDataConversions.asLiveData(viewModel.getUiState()).observe(getViewLifecycleOwner(), this::render);
        FlowLiveDataConversions.asLiveData(viewModel.getShowPresence()).observe(getViewLifecycleOwner(), value -> binding.showPresence.setChecked(Boolean.TRUE.equals(value)));
        FlowLiveDataConversions.asLiveData(settingsViewModel.getThemeMode()).observe(getViewLifecycleOwner(), mode -> binding.themeRow.setTrailingValue(ThemeMapper.resolveThemeLabel(mode, getString(R.string.theme_system), getString(R.string.theme_light), getString(R.string.theme_dark))));
    }

    private void render(ProfileViewModel.UiState state) {
        UserDto user = state.getUser();
        if (user != null) { binding.profileAvatar.setName(user.getDisplayName()); binding.profileAvatar.setAvatarUrl(user.getAvatarUrl()); binding.profileName.setText(user.getDisplayName()); binding.profilePhone.setText(user.getPhone()); }
        binding.nameLayout.setVisibility(state.isEditingName() ? View.VISIBLE : View.GONE);
        binding.nameActions.setVisibility(state.isEditingName() ? View.VISIBLE : View.GONE);
        binding.editName.setVisibility(state.isEditingName() ? View.GONE : View.VISIBLE);
        if (!binding.nameInput.getText().toString().equals(state.getNameInput())) binding.nameInput.setText(state.getNameInput());
        binding.saveName.setEnabled(!state.isSaving() && !state.getNameInput().trim().isEmpty());
        ApiError error = state.getError() != null ? state.getError() : state.getSaveError() != null ? state.getSaveError() : state.getPasswordError() != null ? state.getPasswordError() : state.getAvatarError();
        binding.profileError.setVisibility(error == null ? View.GONE : View.VISIBLE);
        if (error != null) binding.profileError.setText(ApiErrorText.text(requireContext(), error));
        if (state.isPasswordChanged()) { viewModel.consumePasswordChanged(); new AlertDialog.Builder(requireContext()).setMessage(R.string.profile_password_changed).setPositiveButton(android.R.string.ok, null).show(); }
        if (state.getAvatarError() != null) viewModel.consumeAvatarError();
    }

    private void showThemeDialog() {
        ThemeMode[] modes = ThemeMode.values(); String[] labels = { getString(R.string.theme_system), getString(R.string.theme_light), getString(R.string.theme_dark) };
        ThemeMode current = settingsViewModel.getThemeMode().getValue();
        new AlertDialog.Builder(requireContext()).setTitle(R.string.settings_theme_title).setSingleChoiceItems(labels, current.ordinal(), (dialog, which) -> { settingsViewModel.setThemeMode(modes[which]); dialog.dismiss(); }).setNegativeButton(R.string.action_cancel, null).show();
    }

    private void showPasswordDialog() {
        LinearLayout form = new LinearLayout(requireContext()); form.setOrientation(LinearLayout.VERTICAL); int pad = (int)(20 * getResources().getDisplayMetrics().density); form.setPadding(pad, 0, pad, 0);
        EditText old = passwordInput(R.string.auth_current_password_label); EditText next = passwordInput(R.string.auth_new_password_label); EditText confirm = passwordInput(R.string.auth_confirm_password_label);
        form.addView(old); form.addView(next); form.addView(confirm);
        AlertDialog dialog = new AlertDialog.Builder(requireContext()).setTitle(R.string.profile_change_password).setView(form).setNegativeButton(R.string.action_cancel, (d,w) -> viewModel.dismissPasswordDialog()).setPositiveButton(R.string.action_save, null).setOnCancelListener(d -> viewModel.dismissPasswordDialog()).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String oldValue = old.getText().toString();
            String newValue = next.getText().toString();
            String confirmValue = confirm.getText().toString();
            boolean valid = true;
            if (oldValue.isEmpty()) { old.setError(getString(R.string.error_current_password_required)); valid = false; }
            if (newValue.length() < 8) { next.setError(getString(R.string.error_password_short)); valid = false; }
            if (!newValue.equals(confirmValue)) { confirm.setError(getString(R.string.error_password_mismatch)); valid = false; }
            if (valid) {
                viewModel.onOldPasswordChange(oldValue);
                viewModel.onNewPasswordChange(newValue);
                viewModel.onConfirmPasswordChange(confirmValue);
                viewModel.submitPasswordChange();
                dialog.dismiss();
            }
        }));
        dialog.show();
    }
    private EditText passwordInput(int hint) { EditText input = new EditText(requireContext()); input.setHint(hint); input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); return input; }
    @Override public void onDestroyView() { super.onDestroyView(); binding = null; }

    private static final class SimpleTextWatcher implements android.text.TextWatcher {
        private final java.util.function.Consumer<String> consumer; SimpleTextWatcher(java.util.function.Consumer<String> consumer) { this.consumer = consumer; }
        @Override public void beforeTextChanged(CharSequence s,int st,int c,int a){} @Override public void onTextChanged(CharSequence s,int st,int before,int count){consumer.accept(s.toString());} @Override public void afterTextChanged(android.text.Editable e){}
    }
}
