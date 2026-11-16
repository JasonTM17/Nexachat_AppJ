package com.nexachat.feature.encryption;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.nexachat.R;
import com.nexachat.core.network.dto.E2eeDeviceDtos.MemberDeviceDto;
import com.nexachat.feature.ui.FormDialogs;
import dagger.hilt.android.AndroidEntryPoint;

/**
 * Per-conversation encryption screen: e2ee toggle with enable/disable
 * confirmation, the NC1 keyset-verification code for direct chats, and the
 * member-device list. Server error codes map to localized strings; unknown
 * codes fall back to a generic error.
 */
@AndroidEntryPoint
public final class EncryptionFragment extends Fragment {

    private EncryptionViewModel viewModel;
    private MaterialSwitch toggle;
    private View loadingIndicator;
    private TextView status;
    private TextView verificationCode;
    private TextView verifyHint;
    private ImageView trustIcon;
    private TextView trustStateText;
    private View verifyButton;
    private View verificationCard;
    private View devicesCard;
    private LinearLayout devicesList;
    private TextView directoryHint;
    private String peerName;
    private android.widget.CompoundButton.OnCheckedChangeListener toggleListener;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        viewModel = new ViewModelProvider(this).get(EncryptionViewModel.class);
        Bundle args = getArguments();
        peerName = args != null ? args.getString("peerName") : null;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_encryption, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        toggle = view.findViewById(R.id.e2eeToggle);
        loadingIndicator = view.findViewById(R.id.e2eeLoading);
        status = view.findViewById(R.id.e2eeStatus);
        verificationCard = view.findViewById(R.id.verificationCard);
        verificationCode = view.findViewById(R.id.verificationCode);
        verifyHint = view.findViewById(R.id.verifyHint);
        trustIcon = view.findViewById(R.id.trustIcon);
        trustStateText = view.findViewById(R.id.trustStateText);
        verifyButton = view.findViewById(R.id.verifyButton);
        devicesCard = view.findViewById(R.id.devicesCard);
        devicesList = view.findViewById(R.id.devicesList);
        directoryHint = view.findViewById(R.id.e2eeDirectoryHint);
        view.<com.google.android.material.appbar.MaterialToolbar>findViewById(R.id.toolbar)
                .setNavigationOnClickListener(ignored ->
                        NavHostFragment.findNavController(this).navigateUp());
        view.findViewById(R.id.copyCodeButton).setOnClickListener(v -> {
            EncryptionViewModel.UiState state = viewModel.getUiState().getValue();
            if (state == null || state.verificationCode == null) {
                return;
            }
            android.content.ClipboardManager clipboard =
                    (android.content.ClipboardManager) requireContext()
                            .getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText(
                    getString(R.string.e2ee_safety_code_title), state.verificationCode));
            com.google.android.material.snackbar.Snackbar.make(
                    view, R.string.copied_to_clipboard,
                    com.google.android.material.snackbar.Snackbar.LENGTH_SHORT).show();
        });
        verifyButton.setOnClickListener(v -> showVerifyConfirmation());

        toggleListener = (button, checked) -> {
            if (!button.isPressed()) {
                return;
            }
            // Revert the visual change; the confirmed state comes from render().
            toggle.setOnCheckedChangeListener(null);
            toggle.setChecked(!checked);
            toggle.setOnCheckedChangeListener(toggleListener);
            showToggleConfirmation(checked);
        };
        toggle.setOnCheckedChangeListener(toggleListener);

        viewModel.getUiState().observe(getViewLifecycleOwner(), this::render);
        Bundle args = getArguments();
        if (args != null) {
            viewModel.load(args.getString("conversationId", ""));
        }
    }

    private void showToggleConfirmation(boolean enabling) {
        EncryptionViewModel.UiState state = viewModel.getUiState().getValue();
        int body;
        if (enabling) {
            body = R.string.e2ee_enable_confirm_body;
        } else {
            // When reactivation is unavailable the disable warning must say so.
            boolean canReactivate = state != null && state.activationAllowed;
            body = canReactivate ? R.string.e2ee_disable_confirm_body
                    : R.string.e2ee_disable_confirm_body_unavailable;
        }
        androidx.appcompat.app.AlertDialog confirm = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(enabling ? R.string.e2ee_enable_confirm_title
                        : R.string.e2ee_disable_confirm_title)
                .setMessage(body)
                .setPositiveButton(enabling ? R.string.e2ee_action_turn_on
                                : R.string.e2ee_action_turn_off,
                        (d, w) -> viewModel.setEnabled(enabling))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
        if (!enabling) {
            // Turning E2EE off is the destructive half of the same confirm.
            FormDialogs.markDestructive(confirm);
        }
    }

    private void showVerifyConfirmation() {
        String name = peerName == null || peerName.isBlank()
                ? getString(R.string.e2ee_peer_fallback) : peerName;
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.e2ee_verify_confirm_title)
                .setMessage(getString(R.string.e2ee_verify_confirm_body, name))
                .setPositiveButton(R.string.e2ee_action_mark_verified,
                        (d, w) -> viewModel.confirmVerified())
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void render(@NonNull EncryptionViewModel.UiState state) {
        toggle.setEnabled(!state.busy && !state.loading
                && (state.enabled || state.activationAllowed));
        toggle.setOnCheckedChangeListener(null);
        toggle.setChecked(state.enabled);
        toggle.setOnCheckedChangeListener(toggleListener);

        loadingIndicator.setVisibility(
                state.loading || state.busy ? View.VISIBLE : View.GONE);

        if (state.errorCode != null) {
            status.setVisibility(View.VISIBLE);
            status.setTextColor(MaterialColors.getColor(
                    status, StatusRules.statusTextColorAttr(state)));
            status.setText(errorText(state.errorCode));
        } else if (!state.loading && !state.enabled && !state.activationAllowed) {
            // The disabled toggle must explain itself even before any attempt.
            status.setVisibility(View.VISIBLE);
            status.setTextColor(MaterialColors.getColor(
                    status, StatusRules.statusTextColorAttr(state)));
            status.setText(R.string.e2ee_activation_unavailable);
        } else {
            status.setVisibility(View.GONE);
        }

        if (state.verificationCode == null) {
            verificationCard.setVisibility(View.GONE);
        } else {
            verificationCard.setVisibility(View.VISIBLE);
            verificationCode.setText(VerificationCodeText.lines(state.verificationCode));
            String name = peerName == null || peerName.isBlank()
                    ? getString(R.string.e2ee_peer_fallback) : peerName;
            verifyHint.setText(getString(R.string.e2ee_safety_code_body, name));
            renderTrustRow(state.trust);
            verifyButton.setVisibility(state.trust == EncryptionViewModel.Trust.VERIFIED
                    ? View.GONE : View.VISIBLE);
            verifyButton.setEnabled(!state.verifyBusy && !state.busy && !state.loading
                    && state.trust != EncryptionViewModel.Trust.UNAVAILABLE);
        }

        devicesList.removeAllViews();
        int rowCount = 0;
        int rowPadding = getResources().getDimensionPixelSize(R.dimen.space_xs);
        String currentId = viewModel.currentDeviceId();
        String myUserId = viewModel.currentUserId();
        for (MemberDeviceDto d : state.devices) {
            // Malformed directory rows are skipped, never rendered as blanks.
            if (d == null || d.getUserId() == null || d.getUserId().isBlank()
                    || d.getDeviceId() == null || d.getDeviceId().isBlank()
                    || d.getPublicKey() == null || d.getPublicKey().isBlank()) {
                continue;
            }
            TextView row = new TextView(requireContext());
            String shortId = d.getDeviceId().length() > 8
                    ? d.getDeviceId().substring(0, 8) : d.getDeviceId();
            String label;
            if (EncryptionDeviceLabels.isOwnDevice(d, myUserId, currentId)) {
                label = getString(R.string.e2ee_device_row, shortId);
                if (d.getDeviceId().equals(currentId)) {
                    label += " · " + getString(R.string.e2ee_this_device);
                }
            } else if (peerName != null && !peerName.isBlank()) {
                label = getString(R.string.e2ee_device_row_owner, peerName, shortId);
            } else {
                label = getString(R.string.e2ee_device_row, shortId);
            }
            row.setText(label);
            row.setTextAppearance(R.style.TextAppearance_NexaChat_BodyMedium);
            row.setPadding(0, rowPadding, 0, rowPadding);
            devicesList.addView(row);
            rowCount++;
        }
        devicesCard.setVisibility(rowCount == 0 ? View.GONE : View.VISIBLE);
        directoryHint.setVisibility(
                StatusRules.showDirectoryHint(state) ? View.VISIBLE : View.GONE);
    }

    /**
     * The state row reports exactly one status — not verified, verified,
     * changed, or unreadable — announced once by the text; the tinted icon is
     * decorative and marked not important for accessibility.
     */
    private void renderTrustRow(@NonNull EncryptionViewModel.Trust trust) {
        int textRes;
        int iconRes;
        int colorAttr;
        switch (trust) {
            case VERIFIED:
                textRes = R.string.e2ee_verify_state_verified;
                iconRes = R.drawable.ic_check_circle;
                colorAttr = com.google.android.material.R.attr.colorPrimary;
                break;
            case CHANGED:
                textRes = R.string.e2ee_verify_state_changed;
                iconRes = R.drawable.ic_error_outline;
                colorAttr = com.google.android.material.R.attr.colorError;
                break;
            case UNAVAILABLE:
                textRes = R.string.e2ee_verify_state_unavailable;
                iconRes = R.drawable.ic_error_outline;
                colorAttr = com.google.android.material.R.attr.colorOnSurfaceVariant;
                break;
            case NOT_VERIFIED:
            default:
                textRes = R.string.e2ee_verify_state_not_verified;
                iconRes = R.drawable.ic_help_outline;
                colorAttr = com.google.android.material.R.attr.colorOnSurfaceVariant;
                break;
        }
        trustStateText.setText(textRes);
        trustStateText.setTextColor(MaterialColors.getColor(trustStateText, colorAttr));
        trustIcon.setImageResource(iconRes);
        trustIcon.setImageTintList(android.content.res.ColorStateList.valueOf(
                MaterialColors.getColor(trustIcon, colorAttr)));
    }

    @NonNull
    private String errorText(@NonNull String code) {
        switch (code) {
            case "E2EE_PEER_NOT_READY":
                String name = peerName == null || peerName.isBlank()
                        ? getString(R.string.e2ee_peer_fallback) : peerName;
                return getString(R.string.e2ee_error_peer_not_ready, name);
            case "E2EE_UNSUPPORTED_CONVERSATION":
                return getString(R.string.e2ee_error_unsupported);
            case "E2EE_DEVICE_LIMIT":
                return getString(R.string.e2ee_error_limit);
            case "E2EE_DEVICE_CONFLICT":
                return getString(R.string.e2ee_error_conflict);
            case "E2EE_KEYSET_CHANGED":
                return getString(R.string.e2ee_code_changed_refresh);
            case "E2EE_V1_ACTIVATION_DISABLED":
                return getString(R.string.e2ee_activation_unavailable);
            default:
                return getString(R.string.error_generic);
        }
    }

    /** Pure presentation rules for the status line and the directory caption. */
    static final class StatusRules {
        private StatusRules() { }

        /** Only a server failure is an error; the resting unavailability note is informational. */
        static int statusTextColorAttr(@NonNull EncryptionViewModel.UiState state) {
            return state.errorCode != null
                    ? com.google.android.material.R.attr.colorError
                    : com.google.android.material.R.attr.colorOnSurfaceVariant;
        }

        /** Two or more listed devices in an enabled chat without a verification
         *  code can only mean the roster produced no direct transcript: a
         *  complete direct directory always yields a code. */
        static boolean showDirectoryHint(@NonNull EncryptionViewModel.UiState state) {
            return state.enabled && state.verificationCode == null
                    && state.devices.size() >= 2;
        }
    }
}
