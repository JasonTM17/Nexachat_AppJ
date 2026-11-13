package com.nexachat.feature.profile;

import android.Manifest;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
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
import androidx.core.content.ContextCompat;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;
import com.google.android.material.color.MaterialColors;
import com.nexachat.R;
import com.nexachat.core.common.ApiError;
import com.nexachat.core.network.dto.UserDto;
import com.nexachat.data.ThemeMode;
import com.nexachat.databinding.FragmentProfileBinding;
import com.nexachat.feature.auth.ApiErrorText;
import com.nexachat.feature.ui.ImeInsets;
import com.nexachat.summary.AiConsentRepository;
import dagger.hilt.android.AndroidEntryPoint;
import javax.inject.Inject;

@AndroidEntryPoint
public final class ProfileFragment extends Fragment {
    @Inject
    AiConsentRepository consentRepository;

    private FragmentProfileBinding binding;
    private ProfileViewModel viewModel;
    private LinkedAccountsViewModel linkedAccountsViewModel;
    private AppSettingsViewModel settingsViewModel;
    private ActivityResultLauncher<String> avatarPicker;
    private ActivityResultLauncher<String> notificationPermissionRequest;
    private boolean syncingAiConsent;

    public ProfileFragment() {
        avatarPicker = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> { if (uri != null && viewModel != null) viewModel.uploadAvatar(uri); });
        notificationPermissionRequest = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> { });
    }

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
        binding = FragmentProfileBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        viewModel = new ViewModelProvider(this).get(ProfileViewModel.class);
        linkedAccountsViewModel = new ViewModelProvider(this).get(LinkedAccountsViewModel.class);
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
        binding.changePassword.setOnClickListener(v -> {
            ProfileViewModel.UiState current = viewModel.getUiState().getValue();
            if (current != null && current.canChangePassword()) {
                viewModel.openPasswordDialog();
                showPasswordDialog();
            }
        });
        binding.recoveryEmailRow.setTitle(getString(R.string.recovery_email_title));
        binding.recoveryEmailRow.setLeadingIcon(R.drawable.ic_email);
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
        binding.notificationRow.setTitle(getString(R.string.settings_notifications));
        binding.notificationRow.setLeadingIcon(R.drawable.ic_chat_bubble_outline);
        binding.notificationRow.setOnClickListener(v -> openNotificationSettings());
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
        binding.googleAccountRow.setTitle(getString(R.string.sign_in_method_google));
        binding.googleAccountRow.setLeadingIconUntinted(R.drawable.ic_google_g);
        binding.googleAccountRow.setShowChevron(false);
        binding.googleAccountRow.setOnClickListener(v -> onGoogleAccountClicked());
        binding.signInMethodPhoneRow.setTitle(getString(R.string.sign_in_method_phone));
        binding.signInMethodPhoneRow.setLeadingIcon(R.drawable.ic_call);
        binding.signInMethodPhoneRow.setShowChevron(false);
        binding.errorState.setOnRetry(() -> viewModel.load());
        binding.profileLoadRetry.setOnClickListener(v -> viewModel.load());
        linkedAccountsViewModel.getUiState().observe(getViewLifecycleOwner(), this::renderLinkedAccounts);
        viewModel.getUiState().observe(getViewLifecycleOwner(), this::render);
        viewModel.getShowPresence().observe(getViewLifecycleOwner(), value -> binding.showPresence.setChecked(Boolean.TRUE.equals(value)));
        viewModel.getShowSeen().observe(getViewLifecycleOwner(), value -> binding.showSeen.setChecked(Boolean.TRUE.equals(value)));
        settingsViewModel.getThemeMode().observe(getViewLifecycleOwner(), mode -> {
            ThemeMode active = mode != null ? mode : ThemeMode.SYSTEM;
            binding.themeRow.setTrailingValue(ProfileThemeUiRules.resolveThemeRowTrailingValue(
                    active,
                    getString(R.string.theme_system),
                    getString(R.string.theme_light),
                    getString(R.string.theme_dark),
                    true));
            binding.themeChipSystem.setChecked(active == ThemeMode.SYSTEM);
            binding.themeChipLight.setChecked(active == ThemeMode.LIGHT);
            binding.themeChipDark.setChecked(active == ThemeMode.DARK);
        });
    }

    private void openNotificationSettings() {
        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            boolean requested = requireContext().getSharedPreferences(
                    "notification_settings", android.content.Context.MODE_PRIVATE)
                    .getBoolean("permission_requested", false);
            if (!requested) {
                requireContext().getSharedPreferences(
                        "notification_settings", android.content.Context.MODE_PRIVATE)
                        .edit().putBoolean("permission_requested", true).apply();
                notificationPermissionRequest.launch(Manifest.permission.POST_NOTIFICATIONS);
                return;
            }
        }
        Intent settings = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().getPackageName());
        try {
            startActivity(settings);
        } catch (android.content.ActivityNotFoundException unavailable) {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + requireContext().getPackageName())));
        }
    }

    private void render(ProfileViewModel.UiState state) {
        binding.changePassword.setVisibility(state.canChangePassword() ? View.VISIBLE : View.GONE);
        ApiError loadError = state.getError();
        UserDto user = state.getUser();
        boolean noUser = user == null;
        boolean showInitialLoading = state.isLoading() && noUser;
        boolean showInitialError = loadError != null && noUser;
        boolean showCachedLoadError = loadError != null && !noUser;
        binding.loadingState.setVisibility(showInitialLoading ? View.VISIBLE : View.GONE);
        binding.errorState.setVisibility(showInitialError ? View.VISIBLE : View.GONE);
        if (showInitialError && loadError != null) {
            binding.errorState.setMessage(ApiErrorText.text(requireContext(), loadError));
        }
        binding.profileLoadError.setVisibility(showCachedLoadError ? View.VISIBLE : View.GONE);
        binding.profileLoadRetry.setVisibility(showCachedLoadError ? View.VISIBLE : View.GONE);
        binding.profileLoadRetry.setEnabled(!state.isLoading());
        if (showCachedLoadError && loadError != null) {
            binding.profileLoadError.setText(ApiErrorText.text(requireContext(), loadError));
        }
        if (user != null) {
            binding.profileAvatar.setName(user.getDisplayName());
            binding.profileAvatar.setAvatarUrl(user.getAvatarUrl());
            binding.profileName.setText(user.getDisplayName());
            String headerIdentity = user.getPhone();
            if (headerIdentity == null || headerIdentity.isBlank()) {
                headerIdentity = user.hasVerifiedRecoveryEmail() ? user.getEmail() : null;
            }
            if (headerIdentity == null || headerIdentity.isBlank()) {
                binding.profilePhone.setVisibility(View.GONE);
            } else {
                binding.profilePhone.setVisibility(View.VISIBLE);
                binding.profilePhone.setText(headerIdentity);
            }
            if (user.hasVerifiedRecoveryEmail()) {
                binding.recoveryEmailRow.setTrailingValue(user.getEmail());
                binding.recoveryEmailRow.setContentDescription(
                        getString(R.string.recovery_email_title) + ", " + user.getEmail());
            } else {
                binding.recoveryEmailRow.setTrailingValue(getString(R.string.recovery_email_add));
                binding.recoveryEmailRow.setContentDescription(
                        getString(R.string.recovery_email_title) + ", "
                                + getString(R.string.recovery_email_add));
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
        ApiError error = state.getSaveError() != null ? state.getSaveError() : state.getPasswordError() != null ? state.getPasswordError() : state.getAvatarError();
        binding.profileError.setVisibility(error == null ? View.GONE : View.VISIBLE);
        if (error != null) binding.profileError.setText(ApiErrorText.text(requireContext(), error));
        if (state.isPasswordChanged()) { viewModel.consumePasswordChanged(); new MaterialAlertDialogBuilder(requireContext()).setMessage(R.string.profile_password_changed).setPositiveButton(android.R.string.ok, null).show(); }
        if (state.getAvatarError() != null) viewModel.consumeAvatarError();
    }

    private void renderLinkedAccounts(LinkedAccountsViewModel.UiState state) {
        boolean googleVisible = state.isConfigured();
        binding.googleAccountRow.setVisibility(googleVisible ? View.VISIBLE : View.GONE);
        binding.signInMethodGoogleDivider.setVisibility(
                googleVisible ? View.VISIBLE : View.GONE);

        UserDto me = viewModel.getUiState().getValue() != null
                ? viewModel.getUiState().getValue().getUser() : null;
        String accountPhone = me != null ? me.getPhone() : null;
        boolean phoneVisible = accountPhone != null && !accountPhone.isBlank();
        binding.signInMethodPhoneRow.setVisibility(phoneVisible ? View.VISIBLE : View.GONE);
        binding.signInMethodPhoneDivider.setVisibility(
                googleVisible && phoneVisible ? View.VISIBLE : View.GONE);
        if (phoneVisible) {
            binding.signInMethodPhoneRow.setSubtitle(accountPhone);
            binding.signInMethodPhoneRow.setContentDescription(
                    getString(R.string.sign_in_method_phone) + ", " + accountPhone);
        }

        if (googleVisible) {
            binding.googleAccountRow.setEnabled(!state.isLoading());
            String googleEmail = state.isLinked() ? state.getEmail() : null;
            binding.googleAccountRow.setSubtitle(googleEmail);
            String action = state.isLinked()
                    ? getString(R.string.action_unlink_google_short)
                    : getString(R.string.action_link_google_short);
            binding.googleAccountRow.setTrailingValue(action);
            binding.googleAccountRow.setTrailingEmphasis(true);
            String cdEmail = googleEmail != null ? googleEmail : getString(R.string.settings_google_not_linked);
            binding.googleAccountRow.setContentDescription(
                    getString(R.string.cd_sign_in_method_google) + ", " + cdEmail + ", " + action);
        }

        ApiError inline = state.getInlineError();
        if (inline != null) {
            binding.signInMethodsError.setVisibility(View.VISIBLE);
            binding.signInMethodsError.setText(ApiErrorText.text(requireContext(), inline));
        } else {
            binding.signInMethodsError.setVisibility(View.GONE);
        }

        if (state.getError() != null) {
            Toast.makeText(requireContext(),
                    ApiErrorText.text(requireContext(), state.getError()),
                    Toast.LENGTH_SHORT).show();
            linkedAccountsViewModel.consumeError();
        }
    }

    private void onGoogleAccountClicked() {
        LinkedAccountsViewModel.UiState state =
                linkedAccountsViewModel.getUiState().getValue();
        if (state == null || state.isLoading()) {
            return;
        }
        if (state.isLinked()) {
            linkedAccountsViewModel.consumeInlineError();
            UserDto me = viewModel.getUiState().getValue() != null
                    ? viewModel.getUiState().getValue().getUser() : null;
            boolean hasPhone = me != null && me.getPhone() != null && !me.getPhone().isBlank();
            if (hasPhone) {
                showGooglePasswordDialog(R.string.action_unlink_google,
                        R.string.google_unlink_confirm,
                        password -> linkedAccountsViewModel.unlink(password));
            } else {
                linkedAccountsViewModel.unlinkWithoutPassword();
            }
        } else {
            showGooglePasswordDialog(R.string.action_link_google,
                    R.string.google_link_password_prompt,
                    password -> linkedAccountsViewModel.link(requireActivity(), password));
        }
    }

    private void showGooglePasswordDialog(int titleRes, int messageRes,
                                          java.util.function.Consumer<String> onConfirm) {
        TextInputLayout layout = passwordInputLayout(R.string.auth_password_label);
        TextInputEditText input = (TextInputEditText) layout.getEditText();
        int pad = getResources().getDimensionPixelSize(R.dimen.space_md);
        layout.setPadding(pad, getResources().getDimensionPixelSize(R.dimen.space_sm), pad, 0);
        AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(titleRes)
                .setMessage(messageRes)
                .setView(layout)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(titleRes, null)
                .create();
        dialog.setOnShowListener(ignored -> {
            if (dialog.getWindow() != null) {
                ImeInsets.applyToDecor(dialog.getWindow());
            }
            android.widget.Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            positive.setOnClickListener(v -> {
                String value = input == null || input.getText() == null
                        ? "" : input.getText().toString();
                if (value.isEmpty()) {
                    layout.setError(getString(R.string.error_current_password_required));
                    return;
                }
                layout.setError(null);
                dialog.dismiss();
                onConfirm.accept(value);
            });
            if (input != null) {
                input.setOnEditorActionListener((v, actionId, event) -> {
                    if (actionId == EditorInfo.IME_ACTION_DONE) {
                        positive.performClick();
                        return true;
                    }
                    return false;
                });
            }
        });
        dialog.show();
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
        int pad = getResources().getDimensionPixelSize(R.dimen.space_md);
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
        dialog.setOnShowListener(ignored -> {
            if (dialog.getWindow() != null) {
                ImeInsets.applyToDecor(dialog.getWindow());
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
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
            });
        });
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
        layout.setStartIconDrawable(R.drawable.ic_lock);
        layout.setStartIconTintList(ColorStateList.valueOf(MaterialColors.getColor(
                layout, com.google.android.material.R.attr.colorOnSurfaceVariant)));
        layout.setEndIconMode(TextInputLayout.END_ICON_PASSWORD_TOGGLE);
        layout.setEndIconContentDescription(getString(R.string.cd_password_visibility));
        TextInputEditText input = new TextInputEditText(layout.getContext());
        input.setSaveEnabled(false);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setAutofillHints((hint == R.string.auth_current_password_label || hint == R.string.auth_password_label)
                ? View.AUTOFILL_HINT_PASSWORD : "newPassword");
        input.setImeOptions((hint == R.string.auth_confirm_password_label || hint == R.string.auth_password_label)
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
