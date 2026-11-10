package com.nexachat.feature.profile;

import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.view.inputmethod.EditorInfo;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;
import com.nexachat.R;
import com.nexachat.core.common.ApiError;
import com.nexachat.core.network.dto.UserDto;
import com.nexachat.core.ui.ThemeMapper;
import com.nexachat.data.ThemeMode;
import com.nexachat.databinding.FragmentProfileBinding;
import com.nexachat.feature.auth.ApiErrorText;
import com.nexachat.summary.AiConsentRepository;
import dagger.hilt.android.AndroidEntryPoint;
import javax.inject.Inject;

@AndroidEntryPoint
public final class ProfileFragment extends Fragment {
    @Inject
    AiConsentRepository consentRepository;

    private FragmentProfileBinding binding;
    private ProfileViewModel viewModel;
    private AppSettingsViewModel settingsViewModel;
    private ActivityResultLauncher<String> avatarPicker;
    private boolean syncingAiConsent;

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
        if (binding.cameraBadge != null) {
            binding.cameraBadge.setOnClickListener(v -> avatarPicker.launch("image/*"));
        }
        binding.editName.setOnClickListener(v -> viewModel.startEditName());
        binding.nameInput.addTextChangedListener(new SimpleTextWatcher(s -> viewModel.onNameInputChange(s)));
        binding.saveName.setOnClickListener(v -> viewModel.saveDisplayName());
        binding.cancelName.setOnClickListener(v -> viewModel.cancelEditName());
        binding.changePassword.setOnClickListener(v -> { viewModel.openPasswordDialog(); showPasswordDialog(); });
        binding.recoveryEmailRow.setTitle(getString(R.string.recovery_email_title));
        binding.recoveryEmailRow.setLeadingIcon(R.drawable.ic_email);
        binding.recoveryEmailRow.setContentDescription(getString(R.string.cd_recovery_email_row));
        binding.recoveryEmailRow.setOnClickListener(v -> NavHostFragment.findNavController(this)
                .navigate(R.id.action_profile_to_recoveryEmailManage));
        getParentFragmentManager().setFragmentResultListener(
                RecoveryEmailManageViewModel.RESULT_PROFILE_RELOAD,
                getViewLifecycleOwner(),
                (key, bundle) -> {
                    if (bundle.getBoolean(RecoveryEmailManageViewModel.RESULT_PROFILE_RELOAD, false)) {
                        viewModel.load();
                    }
                });
        binding.languageRow.setTitle(getString(R.string.settings_language));
        binding.languageRow.setLeadingIcon(R.drawable.ic_language);
        binding.languageRow.setOnClickListener(v -> NavHostFragment.findNavController(this).navigate(R.id.languageSettingsFragment));
        binding.themeRow.setTitle(getString(R.string.settings_theme_title));
        binding.themeRow.setLeadingIcon(R.drawable.ic_theme_mode);
        binding.themeRow.setOnClickListener(v -> showThemeDialog());
        View.OnClickListener onThemeChipClick = v -> {
            int id = v.getId();
            if (id == R.id.themeChipSystem) {
                settingsViewModel.setThemeMode(ThemeMode.SYSTEM);
            } else if (id == R.id.themeChipLight) {
                settingsViewModel.setThemeMode(ThemeMode.LIGHT);
            } else if (id == R.id.themeChipDark) {
                settingsViewModel.setThemeMode(ThemeMode.DARK);
            }
        };
        binding.themeChipSystem.setOnClickListener(onThemeChipClick);
        binding.themeChipLight.setOnClickListener(onThemeChipClick);
        binding.themeChipDark.setOnClickListener(onThemeChipClick);
        binding.showPresence.setOnCheckedChangeListener((button, checked) -> { if (button.isPressed()) viewModel.setShowPresence(checked); });
        binding.showPresenceRow.setOnClickListener(v -> {
            boolean next = !binding.showPresence.isChecked();
            binding.showPresence.setChecked(next);
            viewModel.setShowPresence(next);
        });
        binding.showSeen.setOnCheckedChangeListener((button, checked) -> { if (button.isPressed()) viewModel.setShowSeen(checked); });
        binding.showSeenRow.setOnClickListener(v -> {
            boolean next = !binding.showSeen.isChecked();
            binding.showSeen.setChecked(next);
            viewModel.setShowSeen(next);
        });
        consentRepository.refresh();
        consentRepository.aiSummaryConsent().observe(getViewLifecycleOwner(), enabled -> {
            syncingAiConsent = true;
            binding.aiSummaryConsent.setChecked(Boolean.TRUE.equals(enabled));
            syncingAiConsent = false;
        });
        consentRepository.error().observe(getViewLifecycleOwner(), error -> {
            if (error != null) {
                Toast.makeText(requireContext(), R.string.settings_consent_update_failed, Toast.LENGTH_SHORT).show();
            }
        });
        binding.aiConsentRow.setOnClickListener(v -> binding.aiSummaryConsent.toggle());
        binding.aiSummaryConsent.setOnCheckedChangeListener(this::onAiConsentToggled);
        binding.logoutRow.setTitle(getString(R.string.action_logout));
        binding.logoutRow.setLeadingIcon(R.drawable.ic_logout);
        binding.logoutRow.setDestructive(true);
        binding.logoutRow.setOnClickListener(v -> new MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.action_logout).setMessage(R.string.action_logout_confirm).setNegativeButton(R.string.action_cancel, null).setPositiveButton(R.string.action_logout, (d, w) -> viewModel.logout()).show());
        binding.logoutAllRow.setTitle(getString(R.string.action_logout_all));
        binding.logoutAllRow.setLeadingIcon(R.drawable.ic_logout);
        binding.logoutAllRow.setDestructive(true);
        binding.logoutAllRow.setOnClickListener(v -> new MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.action_logout_all).setMessage(R.string.profile_logout_all_confirm).setNegativeButton(R.string.action_cancel, null).setPositiveButton(R.string.action_logout_all, (d, w) -> viewModel.logoutAll()).show());
        binding.deleteAccountRow.setTitle(getString(R.string.action_delete_account));
        binding.deleteAccountRow.setLeadingIcon(R.drawable.ic_delete);
        binding.deleteAccountRow.setDestructive(true);
        binding.deleteAccountRow.setContentDescription(getString(R.string.action_delete_account));
        binding.deleteAccountRow.setOnClickListener(v ->
                NavHostFragment.findNavController(this).navigate(R.id.deleteAccountFragment));
        viewModel.getUiState().observe(getViewLifecycleOwner(), this::render);
        viewModel.getShowPresence().observe(getViewLifecycleOwner(), value -> binding.showPresence.setChecked(Boolean.TRUE.equals(value)));
        viewModel.getShowSeen().observe(getViewLifecycleOwner(), value -> binding.showSeen.setChecked(Boolean.TRUE.equals(value)));
        settingsViewModel.getThemeMode().observe(getViewLifecycleOwner(), mode -> {
            ThemeMode active = mode != null ? mode : ThemeMode.SYSTEM;
            binding.themeRow.setTrailingValue(ThemeMapper.resolveThemeLabel(active, getString(R.string.theme_system), getString(R.string.theme_light), getString(R.string.theme_dark)));
            binding.themeChipSystem.setChecked(active == ThemeMode.SYSTEM);
            binding.themeChipLight.setChecked(active == ThemeMode.LIGHT);
            binding.themeChipDark.setChecked(active == ThemeMode.DARK);
        });
    }

    private void render(ProfileViewModel.UiState state) {
        UserDto user = state.getUser();
        if (user != null) {
            binding.profileAvatar.setName(user.getDisplayName());
            binding.profileAvatar.setAvatarUrl(user.getAvatarUrl());
            binding.profileName.setText(user.getDisplayName());
            binding.profilePhone.setText(user.getPhone());
            if (user.hasVerifiedRecoveryEmail()) {
                binding.recoveryEmailRow.setTrailingValue(user.getEmail());
            } else {
                binding.recoveryEmailRow.setTrailingValue(getString(R.string.recovery_email_add));
            }
        }
        binding.profileAvatar.setContentDescription(getString(R.string.profile_change_avatar));
        binding.profileAvatar.setEnabled(!state.isAvatarUploading());
        if (binding.cameraBadge != null) {
            binding.cameraBadge.setEnabled(!state.isAvatarUploading());
            binding.cameraBadge.setAlpha(state.isAvatarUploading() ? 0.5f : 1f);
        }
        binding.nameLayout.setVisibility(state.isEditingName() ? View.VISIBLE : View.GONE);
        binding.nameActions.setVisibility(state.isEditingName() ? View.VISIBLE : View.GONE);
        binding.editName.setVisibility(state.isEditingName() ? View.GONE : View.VISIBLE);
        if (!binding.nameInput.getText().toString().equals(state.getNameInput())) binding.nameInput.setText(state.getNameInput());
        binding.saveName.setEnabled(!state.isSaving() && !state.getNameInput().trim().isEmpty());
        ApiError error = state.getError() != null ? state.getError() : state.getSaveError() != null ? state.getSaveError() : state.getPasswordError() != null ? state.getPasswordError() : state.getAvatarError();
        binding.profileError.setVisibility(error == null ? View.GONE : View.VISIBLE);
        if (error != null) binding.profileError.setText(ApiErrorText.text(requireContext(), error));
        if (state.isPasswordChanged()) { viewModel.consumePasswordChanged(); new MaterialAlertDialogBuilder(requireContext()).setMessage(R.string.profile_password_changed).setPositiveButton(android.R.string.ok, null).show(); }
        if (state.getAvatarError() != null) viewModel.consumeAvatarError();
    }

    private void showThemeDialog() {
        ThemeMode[] modes = ThemeMode.values(); String[] labels = { getString(R.string.theme_system), getString(R.string.theme_light), getString(R.string.theme_dark) };
        ThemeMode current = settingsViewModel.getThemeMode().getValue();
        int selected = current != null ? current.ordinal() : ThemeMode.SYSTEM.ordinal();
        new MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.settings_theme_title).setSingleChoiceItems(labels, selected, (dialog, which) -> { settingsViewModel.setThemeMode(modes[which]); dialog.dismiss(); }).setNegativeButton(R.string.action_cancel, null).show();
    }

    private void showPasswordDialog() {
        LinearLayout form = new LinearLayout(requireContext());
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        int spaceSm = getResources().getDimensionPixelSize(R.dimen.space_sm);
        form.setPadding(pad, spaceSm, pad, 0);

        TextInputLayout oldLayout = passwordInputLayout(R.string.auth_current_password_label);
        TextInputEditText old = (TextInputEditText) oldLayout.getEditText();
        TextInputLayout nextLayout = passwordInputLayout(R.string.auth_new_password_label);
        TextInputEditText next = (TextInputEditText) nextLayout.getEditText();
        TextInputLayout confirmLayout = passwordInputLayout(R.string.auth_confirm_password_label);
        TextInputEditText confirm = (TextInputEditText) confirmLayout.getEditText();

        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp1.bottomMargin = spaceSm;
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp2.bottomMargin = spaceSm;
        LinearLayout.LayoutParams lp3 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);

        form.addView(oldLayout, lp1);
        form.addView(nextLayout, lp2);
        form.addView(confirmLayout, lp3);
        ScrollView scroll = new ScrollView(requireContext());
        scroll.addView(form);

        AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.profile_change_password)
                .setView(scroll)
                .setNegativeButton(R.string.action_cancel, (d, w) -> viewModel.dismissPasswordDialog())
                .setPositiveButton(R.string.action_save, null)
                .setOnCancelListener(d -> viewModel.dismissPasswordDialog())
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String oldValue = old == null || old.getText() == null ? "" : old.getText().toString();
            String newValue = next == null || next.getText() == null ? "" : next.getText().toString();
            String confirmValue = confirm == null || confirm.getText() == null ? "" : confirm.getText().toString();
            boolean valid = true;
            if (oldValue.isEmpty()) {
                oldLayout.setError(getString(R.string.error_current_password_required));
                valid = false;
            } else {
                oldLayout.setError(null);
            }
            if (newValue.length() < 8) {
                nextLayout.setError(getString(R.string.error_password_short));
                valid = false;
            } else {
                nextLayout.setError(null);
            }
            if (!newValue.equals(confirmValue)) {
                confirmLayout.setError(getString(R.string.error_password_mismatch));
                valid = false;
            } else {
                confirmLayout.setError(null);
            }
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
    private void onAiConsentToggled(CompoundButton button, boolean checked) {
        if (syncingAiConsent || !button.isPressed()) {
            return;
        }
        if (checked) {
            syncingAiConsent = true;
            button.setChecked(false);
            syncingAiConsent = false;
            showAiConsentDialog();
        } else {
            consentRepository.setConsent(false);
        }
    }

    private void showAiConsentDialog() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.dialog_ai_consent_title)
                .setMessage(R.string.dialog_ai_consent_message)
                .setNegativeButton(R.string.dialog_ai_consent_dismiss, null)
                .setPositiveButton(R.string.dialog_ai_consent_confirm, (d, w) -> consentRepository.setConsent(true))
                .show();
    }

    private TextInputLayout passwordInputLayout(int hint) {
        TextInputLayout layout = new TextInputLayout(
                requireContext(),
                null,
                com.google.android.material.R.attr.textInputOutlinedStyle);
        float radius = getResources().getDimension(R.dimen.corner_md);
        layout.setBoxCornerRadii(radius, radius, radius, radius);
        layout.setHint(getString(hint));
        layout.setEndIconMode(TextInputLayout.END_ICON_PASSWORD_TOGGLE);
        layout.setEndIconContentDescription(getString(R.string.cd_password_visibility));
        TextInputEditText input = new TextInputEditText(layout.getContext());
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setAutofillHints(hint == R.string.auth_current_password_label
                ? View.AUTOFILL_HINT_PASSWORD : "newPassword");
        input.setImeOptions(hint == R.string.auth_confirm_password_label
                ? EditorInfo.IME_ACTION_DONE : EditorInfo.IME_ACTION_NEXT);
        layout.addView(input);
        return layout;
    }
    @Override public void onDestroyView() { super.onDestroyView(); binding = null; }

    private static final class SimpleTextWatcher implements android.text.TextWatcher {
        private final java.util.function.Consumer<String> consumer; SimpleTextWatcher(java.util.function.Consumer<String> consumer) { this.consumer = consumer; }
        @Override public void beforeTextChanged(CharSequence s,int st,int c,int a){} @Override public void onTextChanged(CharSequence s,int st,int before,int count){consumer.accept(s.toString());} @Override public void afterTextChanged(android.text.Editable e){}
    }
}
