package com.nexachat.feature.profile;

import android.Manifest;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.view.inputmethod.EditorInfo;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.snackbar.Snackbar;
import com.nexachat.R;
import com.nexachat.core.common.ApiError;
import com.nexachat.core.network.dto.UserDto;
import com.nexachat.data.ChatColor;
import com.nexachat.data.ThemeMode;
import com.nexachat.databinding.FragmentProfileBinding;
import com.nexachat.feature.auth.ApiErrorText;
import com.nexachat.feature.auth.AuthFieldValidators;
import com.nexachat.feature.ui.FormDialogs;
import com.nexachat.feature.streak.StreakDto;
import com.nexachat.feature.streak.StreakMilestoneSheet;
import com.nexachat.feature.streak.StreakSettingsViewModel;
import com.nexachat.feature.streak.StreakViewModel;
import com.nexachat.summary.AiConsentRepository;
import dagger.hilt.android.AndroidEntryPoint;
import javax.inject.Inject;

@AndroidEntryPoint
public final class ProfileFragment extends Fragment {

    private static final String STATE_GOOGLE_PASSWORD_DIALOG = "googlePasswordDialogShowing";
    /** Swatch cell ids in {@link ChatColor} declaration order. */
    private static final int[] CHAT_COLOR_CELLS = {
            R.id.chatColorSwatchPlum, R.id.chatColorSwatchIndigo, R.id.chatColorSwatchBlue,
            R.id.chatColorSwatchTeal, R.id.chatColorSwatchGreen, R.id.chatColorSwatchAmber,
            R.id.chatColorSwatchRose, R.id.chatColorSwatchSlate };

    @Inject
    AiConsentRepository consentRepository;

    private FragmentProfileBinding binding;
    private ProfileViewModel viewModel;
    private LinkedAccountsViewModel linkedAccountsViewModel;
    private AppSettingsViewModel settingsViewModel;
    private StreakViewModel streakViewModel;
    private StreakSettingsViewModel streakSettingsViewModel;
    /** True while the reminder switch shows a stored value, so it is not saved back. */
    private boolean syncingStreakReminders;
    private ActivityResultLauncher<String> avatarPicker;
    private ActivityResultLauncher<String> notificationPermissionRequest;

    private boolean syncingAiConsent;
    /** True while a switch shows a stored value, so the change is not saved back as a user edit. */
    private boolean syncingPrivacy;
    @Nullable private AlertDialog passwordDialog;
    @Nullable private TextView passwordDialogError;
    @Nullable private AlertDialog googleDialog;
    private boolean googlePasswordDialogShowing;
    @Nullable private AlertDialog phoneDialog;
    @Nullable private TextView phoneDialogError;
    @Nullable private TextInputLayout phoneDialogInputLayout;
    @Nullable private TextInputLayout phoneDialogPasswordLayout;

    public ProfileFragment() {
        avatarPicker = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> { if (uri != null && viewModel != null) viewModel.uploadAvatar(uri); });
        notificationPermissionRequest = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                // The first tap only asked for the permission; whichever way the
                // user answered, continue into the settings screen they wanted.
                granted -> openNotificationSettings());
    }

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
        binding = FragmentProfileBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        viewModel = new ViewModelProvider(this).get(ProfileViewModel.class);
        linkedAccountsViewModel = new ViewModelProvider(this).get(LinkedAccountsViewModel.class);
        settingsViewModel = new ViewModelProvider(requireActivity()).get(AppSettingsViewModel.class);
        streakViewModel = new ViewModelProvider(this).get(StreakViewModel.class);
        streakSettingsViewModel = new ViewModelProvider(this).get(StreakSettingsViewModel.class);
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
        binding.changePassword.setTitle(getString(R.string.profile_change_password));
        binding.changePassword.setLeadingIcon(R.drawable.ic_lock);
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
        binding.languageRow.setTrailingValue(getString(LanguageLabels.label(
                com.nexachat.core.common.AppLocaleManager.current(requireContext()))));
        binding.themeRow.setTitle(getString(R.string.settings_theme_title));
        binding.themeRow.setLeadingIcon(R.drawable.ic_theme_mode);
        binding.themeRow.setOnClickListener(v -> showThemeDialog());
        // The quick chips below already choose the theme; a chevron would promise another screen.
        binding.themeRow.setShowChevron(false);
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
        binding.chatColorRow.setTitle(getString(R.string.settings_chat_color));
        binding.chatColorRow.setLeadingIcon(R.drawable.ic_palette);
        binding.chatColorRow.setShowChevron(false);
        bindChatColorSwatches(view);
        // Render persisted values from the model; widget restoration could invoke the save listeners.
        binding.showPresence.setSaveEnabled(false);
        binding.showSeen.setSaveEnabled(false);
        binding.aiSummaryConsent.setSaveEnabled(false);
        // Save every user change, including TalkBack, keyboard and Switch Access toggles that never
        // press the view; only values pushed in from storage are skipped.
        binding.showPresence.setOnCheckedChangeListener((button, checked) -> {
            if (!syncingPrivacy) viewModel.setShowPresence(checked);
        });
        binding.starredMessagesRow.setTitle(getString(R.string.starred_messages_title));
        binding.starredMessagesRow.setLeadingIcon(R.drawable.ic_star_outline);
        binding.starredMessagesRow.setOnClickListener(v ->
                NavHostFragment.findNavController(this).navigate(R.id.starredMessagesFragment));
        binding.blockedUsersRow.setTitle(getString(R.string.blocked_users_title));
        binding.blockedUsersRow.setLeadingIcon(R.drawable.ic_block);
        binding.blockedUsersRow.setOnClickListener(v ->
                NavHostFragment.findNavController(this).navigate(R.id.blockedUsersFragment));
        binding.showPresenceRow.setOnClickListener(v -> binding.showPresence.toggle());
        binding.showSeen.setOnCheckedChangeListener((button, checked) -> {
            if (!syncingPrivacy) viewModel.setShowSeen(checked);
        });
        binding.showSeenRow.setOnClickListener(v -> binding.showSeen.toggle());
        binding.notificationRow.setTitle(getString(R.string.settings_notifications));
        binding.notificationRow.setLeadingIcon(R.drawable.ic_chat_bubble_outline);
        binding.notificationRow.setOnClickListener(v -> openNotificationSettings());
        bindStreak();
        consentRepository.refresh();
        consentRepository.aiSummaryConsent().observe(getViewLifecycleOwner(), enabled -> {
            syncingAiConsent = true;
            binding.aiSummaryConsent.setChecked(Boolean.TRUE.equals(enabled));
            syncingAiConsent = false;
        });
        consentRepository.error().observe(getViewLifecycleOwner(), error -> {
            if (error != null) {
                Snackbar.make(binding.getRoot(),
                        R.string.settings_consent_update_failed, Snackbar.LENGTH_SHORT).show();
            }
        });
        binding.aiConsentRow.setOnClickListener(v -> binding.aiSummaryConsent.toggle());
        binding.aiSummaryConsent.setOnCheckedChangeListener(this::onAiConsentToggled);
        binding.logoutRow.setTitle(getString(R.string.action_logout));
        binding.logoutRow.setLeadingIcon(R.drawable.ic_logout);
        binding.logoutRow.setOnClickListener(v -> FormDialogs.markDestructive(new MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.action_logout).setMessage(R.string.action_logout_confirm).setNegativeButton(R.string.action_cancel, null).setPositiveButton(R.string.action_logout, (d, w) -> viewModel.logout()).show()));
        binding.logoutAllRow.setTitle(getString(R.string.action_logout_all));
        binding.logoutAllRow.setLeadingIcon(R.drawable.ic_logout);
        binding.logoutAllRow.setOnClickListener(v -> FormDialogs.markDestructive(new MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.action_logout_all).setMessage(R.string.profile_logout_all_confirm).setNegativeButton(R.string.action_cancel, null).setPositiveButton(R.string.action_logout_all, (d, w) -> viewModel.logoutAll()).show()));
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
        binding.signInMethodPhoneRow.setOnClickListener(v -> {
            viewModel.openPhoneDialog();
            showPhoneDialog();
        });
        binding.errorState.setOnRetry(() -> viewModel.load());
        binding.profileLoadRetry.setOnClickListener(v -> viewModel.load());
        linkedAccountsViewModel.getUiState().observe(getViewLifecycleOwner(), this::renderLinkedAccounts);
        viewModel.getUiState().observe(getViewLifecycleOwner(), this::render);
        viewModel.getShowPresence().observe(getViewLifecycleOwner(), value -> {
            syncingPrivacy = true;
            binding.showPresence.setChecked(Boolean.TRUE.equals(value));
            syncingPrivacy = false;
        });
        viewModel.getShowSeen().observe(getViewLifecycleOwner(), value -> {
            syncingPrivacy = true;
            binding.showSeen.setChecked(Boolean.TRUE.equals(value));
            syncingPrivacy = false;
        });
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
        settingsViewModel.getChatColor().observe(getViewLifecycleOwner(),
                this::renderChatColorSelection);
        if (state != null && state.getBoolean(STATE_GOOGLE_PASSWORD_DIALOG, false)) {
            // The Google password prompt is stateless (its secret never leaves
            // the field); the click path re-derives link vs unlink from the
            // ViewModel and reopens it.
            onGoogleAccountClicked();
        }
    }

    @Override public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_GOOGLE_PASSWORD_DIALOG, googlePasswordDialogShowing);
    }

    private void bindStreak() {
        binding.streakRow.setTitle(getString(R.string.streak_title));
        binding.streakRow.setLeadingIconUntinted(R.drawable.ic_streak_flame);
        binding.streakRow.setShowChevron(false);
        renderStreakRow(null);
        binding.streakReminders.setSaveEnabled(false);
        binding.streakRemindersRow.setOnClickListener(v -> binding.streakReminders.toggle());
        binding.streakReminders.setOnCheckedChangeListener((button, checked) -> {
            if (!syncingStreakReminders) streakSettingsViewModel.setReminders(checked);
        });
        StreakMilestoneSheet.bind(this, streakViewModel);
        streakViewModel.getCheckInStreak().observe(getViewLifecycleOwner(), this::renderStreakRow);
        streakViewModel.loadMine();
        streakSettingsViewModel.refresh();
        streakSettingsViewModel.getReminders().observe(getViewLifecycleOwner(), value -> {
            syncingStreakReminders = true;
            binding.streakReminders.setChecked(Boolean.TRUE.equals(value));
            syncingStreakReminders = false;
        });
        streakSettingsViewModel.getUpdateFailed().observe(getViewLifecycleOwner(), failed -> {
            if (Boolean.TRUE.equals(failed)) {
                Snackbar.make(binding.getRoot(),
                        R.string.streak_reminders_update_failed, Snackbar.LENGTH_SHORT).show();
                streakSettingsViewModel.onUpdateFailureShown();
            }
        });
    }

    private void renderStreakRow(@Nullable StreakDto loaded) {
        if (binding == null) return;
        StreakDto streak = loaded == null ? null : loaded.checkInView();
        int count = streak == null ? 0 : streak.getCount();
        if (count <= 0) {
            binding.streakRow.setTrailingValue(null);
            binding.streakRow.setSubtitle(null);
            binding.streakRow.setContentDescription(getString(R.string.streak_title));
            return;
        }
        String days = getResources().getQuantityString(R.plurals.streak_days, count, count);
        binding.streakRow.setTrailingValue(days);
        StringBuilder detail = new StringBuilder();
        if (streak.getBestCount() > 0) {
            detail.append(getString(R.string.streak_best, streak.getBestCount()));
        }
        if (streak.isAtRisk()) {
            if (detail.length() > 0) detail.append(" \u00b7 ");
            detail.append(getString(R.string.streak_at_risk));
        }
        binding.streakRow.setSubtitle(detail.length() == 0 ? null : detail.toString());
        StringBuilder description = new StringBuilder(getString(R.string.streak_title))
                .append(", ").append(days);
        if (detail.length() > 0) description.append(", ").append(detail);
        binding.streakRow.setContentDescription(description);
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
        if (state.isShowPasswordDialog() && passwordDialog == null) {
            showPasswordDialog();
        } else if (passwordDialog != null && !state.isShowPasswordDialog()) {
            passwordDialog.dismiss();
        }
        if (passwordDialogError != null) {
            ApiError passwordError = state.getPasswordError();
            passwordDialogError.setVisibility(passwordError == null ? View.GONE : View.VISIBLE);
            if (passwordError != null) {
                passwordDialogError.setText(ApiErrorText.text(requireContext(), passwordError));
            }
        }
        if (passwordDialog != null && passwordDialog.isShowing()) {
            passwordDialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    .setEnabled(!state.isPasswordSaving());
        }
        if (state.isShowPhoneDialog() && phoneDialog == null) {
            showPhoneDialog();
        } else if (phoneDialog != null && !state.isShowPhoneDialog()) {
            phoneDialog.dismiss();
        }
        if (phoneDialogError != null) {
            ApiError phoneError = state.getPhoneError();
            // A rejected current password is flagged on its field, not on the banner.
            boolean onPasswordField = phoneDialogPasswordLayout != null
                    && phoneError instanceof ApiError.WrongPassword;
            phoneDialogError.setVisibility(
                    phoneError == null || onPasswordField ? View.GONE : View.VISIBLE);
            if (phoneError != null && !onPasswordField) {
                phoneDialogError.setText(phoneDialogErrorText(phoneError));
            }
        }
        if (phoneDialogInputLayout != null) {
            phoneDialogInputLayout.setError(state.isPhoneInvalid()
                    ? getString(R.string.error_phone_invalid) : null);
        }
        if (phoneDialogPasswordLayout != null) {
            ApiError phoneError = state.getPhoneError();
            CharSequence passwordFieldError = null;
            if (state.isPhonePasswordMissing()) {
                passwordFieldError = getString(R.string.error_current_password_required);
            } else if (phoneError instanceof ApiError.WrongPassword) {
                passwordFieldError = ApiErrorText.text(requireContext(), phoneError);
            }
            phoneDialogPasswordLayout.setError(passwordFieldError);
        }
        if (phoneDialog != null && phoneDialog.isShowing()) {
            phoneDialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    .setEnabled(!state.isPhoneSaving() && state.canSubmitPhone());
            android.widget.Button removeButton = phoneDialog.getButton(AlertDialog.BUTTON_NEUTRAL);
            if (removeButton != null) {
                removeButton.setEnabled(!state.isPhoneSaving());
            }
        }
        if (state.isPasswordChanged()) { viewModel.consumePasswordChanged(); new MaterialAlertDialogBuilder(requireContext()).setMessage(R.string.profile_password_changed).setPositiveButton(android.R.string.ok, null).show(); }
        if (state.getAvatarError() != null) viewModel.consumeAvatarError();
        // Sign-in rows combine linked-account config with the loaded profile; re-render
        // so the phone row appears when the user arrives after the linked-accounts state.
        LinkedAccountsViewModel.UiState linked = linkedAccountsViewModel.getUiState().getValue();
        if (linked != null) {
            renderLinkedAccounts(linked);
        }
    }

    private void renderLinkedAccounts(LinkedAccountsViewModel.UiState state) {
        boolean googleVisible = state.isConfigured();
        binding.googleAccountRow.setVisibility(googleVisible ? View.VISIBLE : View.GONE);
        binding.signInMethodGoogleDivider.setVisibility(
                googleVisible ? View.VISIBLE : View.GONE);

        UserDto me = viewModel.getUiState().getValue() != null
                ? viewModel.getUiState().getValue().getUser() : null;
        String accountPhone = me != null ? me.getPhone() : null;
        boolean hasPhone = accountPhone != null && !accountPhone.isBlank();
        binding.signInMethodPhoneRow.setSubtitle(
                hasPhone ? accountPhone : getString(R.string.action_add));
        binding.signInMethodPhoneRow.setContentDescription(
                getString(R.string.sign_in_method_phone) + ", "
                        + (hasPhone ? accountPhone : getString(R.string.action_add)));

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
            Snackbar.make(binding.getRoot(),
                    ApiErrorText.text(requireContext(), state.getError()),
                    Snackbar.LENGTH_SHORT).show();
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
        googleDialog = dialog;
        googlePasswordDialogShowing = true;
        dialog.setOnDismissListener(d -> {
            googleDialog = null;
            googlePasswordDialogShowing = false;
        });
        dialog.setOnShowListener(ignored -> {
            FormDialogs.resizeAboveIme(dialog);
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

    private void bindChatColorSwatches(@NonNull View view) {
        boolean night = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        ChatColor[] colors = ChatColor.values();
        for (int i = 0; i < CHAT_COLOR_CELLS.length; i++) {
            View cell = view.findViewById(CHAT_COLOR_CELLS[i]);
            if (cell == null) {
                continue;
            }
            ChatColor color = colors[i];
            View swatch = cell instanceof ViewGroup ? ((ViewGroup) cell).getChildAt(0) : null;
            if (swatch != null) {
                swatch.setBackgroundTintList(
                        ColorStateList.valueOf(color.bubbleArgb(night)));
            }
            cell.setOnClickListener(v -> settingsViewModel.setChatColor(color));
        }
    }

    private void renderChatColorSelection(@Nullable ChatColor selected) {
        ChatColor active = selected != null ? selected : ChatColor.PLUM;
        ChatColor[] colors = ChatColor.values();
        for (int i = 0; i < CHAT_COLOR_CELLS.length; i++) {
            View cell = requireView().findViewById(CHAT_COLOR_CELLS[i]);
            if (cell == null) {
                continue;
            }
            boolean checked = colors[i] == active;
            View mark = cell instanceof ViewGroup ? ((ViewGroup) cell).getChildAt(1) : null;
            if (mark != null) {
                mark.setVisibility(checked ? View.VISIBLE : View.GONE);
            }
            cell.setSelected(checked);
            ViewCompat.setStateDescription(
                    cell, checked ? getString(R.string.forward_selected) : null);
        }
    }

    private void showThemeDialog() {
        ThemeMode[] modes = ThemeMode.values(); String[] labels = { getString(R.string.theme_system), getString(R.string.theme_light), getString(R.string.theme_dark) };
        ThemeMode current = settingsViewModel.getThemeMode().getValue();
        int selected = current != null ? current.ordinal() : ThemeMode.SYSTEM.ordinal();
        new MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.settings_theme_title).setSingleChoiceItems(labels, selected, (dialog, which) -> { settingsViewModel.setThemeMode(modes[which]); dialog.dismiss(); }).setNegativeButton(R.string.action_cancel, null).show();
    }

    private void showPasswordDialog() {
        if (passwordDialog != null) {
            return;
        }
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

        TextView errorLine = new TextView(requireContext());
        errorLine.setId(R.id.password_dialog_error);
        errorLine.setVisibility(View.GONE);
        errorLine.setBackgroundResource(R.drawable.bg_auth_inline_error);
        int inset = getResources().getDimensionPixelSize(R.dimen.space_inset);
        errorLine.setPadding(inset, inset, inset, inset);
        errorLine.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_error_outline, 0, 0, 0);
        errorLine.setCompoundDrawableTintList(ColorStateList.valueOf(MaterialColors.getColor(
                errorLine, com.google.android.material.R.attr.colorError)));
        errorLine.setCompoundDrawablePadding(spaceSm);
        errorLine.setTextColor(MaterialColors.getColor(
                errorLine, com.google.android.material.R.attr.colorOnErrorContainer));
        android.util.TypedValue body = new android.util.TypedValue();
        requireContext().getTheme().resolveAttribute(
                com.google.android.material.R.attr.textAppearanceBodyMedium, body, true);
        errorLine.setTextAppearance(body.resourceId);
        androidx.core.view.ViewCompat.setAccessibilityLiveRegion(
                errorLine, androidx.core.view.ViewCompat.ACCESSIBILITY_LIVE_REGION_POLITE);
        LinearLayout.LayoutParams errorLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        errorLp.topMargin = spaceSm;
        form.addView(errorLine, errorLp);

        AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.profile_change_password)
                .setView(FormDialogs.scrollableBody(form))
                .setNegativeButton(R.string.action_cancel, (d, w) -> viewModel.dismissPasswordDialog())
                .setPositiveButton(R.string.action_save, null)
                .setOnCancelListener(d -> viewModel.dismissPasswordDialog())
                .create();
        passwordDialog = dialog;
        passwordDialogError = errorLine;
        dialog.setOnDismissListener(d -> {
            passwordDialog = null;
            passwordDialogError = null;
        });
        // Typed values live in the ViewModel (the fields opt out of view-state
        // saving) so an in-progress password change survives rotation.
        ProfileViewModel.UiState current = viewModel.getUiState().getValue();
        if (current != null) {
            if (old != null) old.setText(current.getOldPassword());
            if (next != null) next.setText(current.getNewPassword());
            if (confirm != null) confirm.setText(current.getConfirmPassword());
        }
        if (old != null) old.addTextChangedListener(new SimpleTextWatcher(viewModel::onOldPasswordChange));
        if (next != null) next.addTextChangedListener(new SimpleTextWatcher(viewModel::onNewPasswordChange));
        if (confirm != null) confirm.addTextChangedListener(new SimpleTextWatcher(viewModel::onConfirmPasswordChange));
        dialog.setOnShowListener(ignored -> {
            FormDialogs.resizeAboveIme(dialog);
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
            errorLine.setVisibility(View.GONE);
            if (valid) {
                viewModel.onOldPasswordChange(oldValue);
                viewModel.onNewPasswordChange(newValue);
                viewModel.onConfirmPasswordChange(confirmValue);
                viewModel.submitPasswordChange();
            }
            });
        });
        dialog.show();
    }

    private void showPhoneDialog() {
        ProfileViewModel.UiState current = viewModel.getUiState().getValue();
        if (phoneDialog != null || current == null || !current.isShowPhoneDialog()) {
            return;
        }
        LinearLayout form = new LinearLayout(requireContext());
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = getResources().getDimensionPixelSize(R.dimen.space_md);
        int spaceSm = getResources().getDimensionPixelSize(R.dimen.space_sm);
        form.setPadding(pad, spaceSm, pad, 0);

        TextInputLayout phoneLayout = new TextInputLayout(
                FormDialogs.dialogContext(requireContext()),
                null,
                com.google.android.material.R.attr.textInputStyle);
        phoneLayout.setHint(getString(R.string.sign_in_method_phone));
        phoneLayout.setStartIconDrawable(R.drawable.ic_call);
        phoneLayout.setStartIconTintList(ColorStateList.valueOf(MaterialColors.getColor(
                phoneLayout, com.google.android.material.R.attr.colorOnSurfaceVariant)));
        phoneLayout.setPlaceholderText(getString(R.string.auth_phone_placeholder));
        TextInputEditText input = new TextInputEditText(phoneLayout.getContext());
        input.setSaveEnabled(false);
        input.setSingleLine(true);
        Typeface bodyTypeface = input.getTypeface();
        input.setInputType(InputType.TYPE_CLASS_PHONE);
        input.setTypeface(bodyTypeface);
        input.setAutofillHints(View.AUTOFILL_HINT_PHONE);
        input.setImeOptions(current.isPhonePasswordRequired()
                ? EditorInfo.IME_ACTION_NEXT : EditorInfo.IME_ACTION_DONE);
        phoneLayout.addView(input);
        form.addView(phoneLayout, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // Replacing a linked phone re-authenticates with the current password;
        // first links and passwordless accounts never see this field.
        final TextInputLayout passwordLayout = current.isPhonePasswordRequired()
                ? passwordInputLayout(R.string.auth_current_password_label) : null;
        final TextInputEditText passwordInput = passwordLayout != null
                ? (TextInputEditText) passwordLayout.getEditText() : null;
        if (passwordLayout != null && passwordInput != null) {
            passwordInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
            LinearLayout.LayoutParams passwordLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            passwordLp.topMargin = spaceSm;
            form.addView(passwordLayout, passwordLp);
        }

        TextView errorLine = new TextView(requireContext());
        errorLine.setVisibility(View.GONE);
        errorLine.setBackgroundResource(R.drawable.bg_auth_inline_error);
        int inset = getResources().getDimensionPixelSize(R.dimen.space_inset);
        errorLine.setPadding(inset, inset, inset, inset);
        errorLine.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_error_outline, 0, 0, 0);
        androidx.core.widget.TextViewCompat.setCompoundDrawableTintList(
                errorLine, ColorStateList.valueOf(MaterialColors.getColor(
                        errorLine, com.google.android.material.R.attr.colorError)));
        errorLine.setCompoundDrawablePadding(spaceSm);
        errorLine.setTextColor(MaterialColors.getColor(
                errorLine, com.google.android.material.R.attr.colorOnErrorContainer));
        android.util.TypedValue body = new android.util.TypedValue();
        requireContext().getTheme().resolveAttribute(
                com.google.android.material.R.attr.textAppearanceBodyMedium, body, true);
        androidx.core.widget.TextViewCompat.setTextAppearance(errorLine, body.resourceId);
        ViewCompat.setAccessibilityLiveRegion(
                errorLine, ViewCompat.ACCESSIBILITY_LIVE_REGION_POLITE);
        LinearLayout.LayoutParams errorLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        errorLp.topMargin = spaceSm;
        form.addView(errorLine, errorLp);

        UserDto me = current.getUser();
        boolean hasPhone = me != null && me.getPhone() != null && !me.getPhone().isBlank();
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.sign_in_method_phone)
                .setView(FormDialogs.scrollableBody(form))
                .setNegativeButton(R.string.action_cancel, (d, w) -> viewModel.dismissPhoneDialog())
                .setPositiveButton(R.string.action_save, null)
                .setOnCancelListener(d -> viewModel.dismissPhoneDialog());
        if (hasPhone) {
            builder.setNeutralButton(R.string.profile_phone_remove, null);
        }
        AlertDialog dialog = builder.create();
        phoneDialog = dialog;
        phoneDialogError = errorLine;
        phoneDialogInputLayout = phoneLayout;
        phoneDialogPasswordLayout = passwordLayout;
        dialog.setOnDismissListener(d -> {
            phoneDialog = null;
            phoneDialogError = null;
            phoneDialogInputLayout = null;
            phoneDialogPasswordLayout = null;
        });
        input.setText(current.getPhoneInput());
        input.addTextChangedListener(new SimpleTextWatcher(viewModel::onPhoneInputChange));
        if (passwordInput != null) {
            passwordInput.setText(current.getPhonePassword());
            passwordInput.addTextChangedListener(
                    new SimpleTextWatcher(viewModel::onPhonePasswordChange));
        }
        dialog.setOnShowListener(ignored -> {
            FormDialogs.resizeAboveIme(dialog);
            android.widget.Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            positive.setOnClickListener(v -> {
                String value = input.getText() == null ? "" : input.getText().toString();
                String normalized = AuthFieldValidators.normalizeE164(value);
                if (!AuthFieldValidators.isE164(normalized)) {
                    phoneLayout.setError(getString(R.string.error_phone_invalid));
                    return;
                }
                phoneLayout.setError(null);
                errorLine.setVisibility(View.GONE);
                viewModel.onPhoneInputChange(value);
                viewModel.submitPhoneChange();
            });
            android.widget.Button remove = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
            if (remove != null) {
                remove.setOnClickListener(v -> showRemovePhoneConfirm());
            }
            input.setOnEditorActionListener((v, actionId, event) -> {
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    positive.performClick();
                    return true;
                }
                return false;
            });
            if (passwordInput != null) {
                passwordInput.setOnEditorActionListener((v, actionId, event) -> {
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

    private void showRemovePhoneConfirm() {
        FormDialogs.markDestructive(new MaterialAlertDialogBuilder(requireContext())
                .setMessage(R.string.profile_phone_remove_confirm)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_remove, (d, w) -> viewModel.removePhone())
                .show());
    }

    /**
     * The shared {@link ApiErrorText} table keeps the Google-unlink wording for
     * {@code LAST_SIGN_IN_METHOD}; inside the phone dialog the same code means
     * the number is the only way in, and {@code VALIDATION_FAILED} means the
     * server rejected the number's shape.
     */
    private CharSequence phoneDialogErrorText(@NonNull ApiError error) {
        if (error instanceof ApiError.Unknown unknown) {
            if ("LAST_SIGN_IN_METHOD".equals(unknown.getCode())) {
                return getText(R.string.error_phone_last_sign_in_method);
            }
            if ("VALIDATION_FAILED".equals(unknown.getCode())) {
                return getText(R.string.error_phone_invalid);
            }
        }
        return ApiErrorText.text(requireContext(), error);
    }

    private void onAiConsentToggled(CompoundButton button, boolean checked) {
        if (syncingAiConsent) {
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
                FormDialogs.dialogContext(requireContext()),
                null,
                com.google.android.material.R.attr.textInputStyle);
        layout.setHint(getString(hint));
        layout.setStartIconDrawable(R.drawable.ic_lock);
        layout.setStartIconTintList(ColorStateList.valueOf(MaterialColors.getColor(
                layout, com.google.android.material.R.attr.colorOnSurfaceVariant)));
        layout.setEndIconMode(TextInputLayout.END_ICON_PASSWORD_TOGGLE);
        layout.setEndIconContentDescription(getString(R.string.cd_password_visibility));
        TextInputEditText input = new TextInputEditText(layout.getContext());
        input.setSaveEnabled(false);
        input.setSingleLine(true);
        Typeface bodyTypeface = input.getTypeface();
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setTypeface(bodyTypeface);
        input.setAutofillHints((hint == R.string.auth_current_password_label || hint == R.string.auth_password_label)
                ? View.AUTOFILL_HINT_PASSWORD : "newPassword");
        input.setImeOptions((hint == R.string.auth_confirm_password_label || hint == R.string.auth_password_label)
                ? EditorInfo.IME_ACTION_DONE : EditorInfo.IME_ACTION_NEXT);
        layout.addView(input);
        return layout;
    }

    @Override public void onPause() {
        if (streakViewModel != null) {
            streakViewModel.onScreenHidden();
        }
        super.onPause();
    }

    @Override public void onDestroyView() {
        if (passwordDialog != null) {
            passwordDialog.dismiss();
        }
        if (googleDialog != null) {
            googleDialog.dismiss();
        }
        if (phoneDialog != null) {
            phoneDialog.dismiss();
        }
        binding = null;
        super.onDestroyView();
    }

    private static final class SimpleTextWatcher implements android.text.TextWatcher {
        private final java.util.function.Consumer<String> consumer; SimpleTextWatcher(java.util.function.Consumer<String> consumer) { this.consumer = consumer; }
        @Override public void beforeTextChanged(CharSequence s,int st,int c,int a){} @Override public void onTextChanged(CharSequence s,int st,int before,int count){consumer.accept(s.toString());} @Override public void afterTextChanged(android.text.Editable e){}
    }
}
