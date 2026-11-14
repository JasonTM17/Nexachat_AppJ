package com.nexachat.feature.encryption;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.nexachat.R;
import com.nexachat.core.network.dto.E2eeDeviceDtos.MemberDeviceDto;
import dagger.hilt.android.AndroidEntryPoint;

/**
 * Per-conversation encryption screen: e2ee toggle with enable/disable
 * confirmation, the fingerprint safety code, and the member-device list.
 * Server error codes map to localized strings; unknown codes fall back to a
 * generic error.
 */
@AndroidEntryPoint
public final class EncryptionFragment extends Fragment {

    private EncryptionViewModel viewModel;
    private MaterialSwitch toggle;
    private TextView status;
    private TextView safetyCode;
    private TextView safetyHint;
    private View safetyCard;
    private View devicesCard;
    private LinearLayout devicesList;
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
        status = view.findViewById(R.id.e2eeStatus);
        safetyCard = view.findViewById(R.id.safetyCard);
        safetyCode = view.findViewById(R.id.safetyCode);
        safetyHint = view.findViewById(R.id.safetyHint);
        devicesCard = view.findViewById(R.id.devicesCard);
        devicesList = view.findViewById(R.id.devicesList);
        view.findViewById(R.id.copyCodeButton).setOnClickListener(v -> {
            CharSequence code = safetyCode.getText();
            if (code == null || code.length() == 0) {
                return;
            }
            android.content.ClipboardManager clipboard =
                    (android.content.ClipboardManager) requireContext()
                            .getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText(
                    getString(R.string.e2ee_safety_code_title), code));
            com.google.android.material.snackbar.Snackbar.make(
                    view, R.string.copied_to_clipboard,
                    com.google.android.material.snackbar.Snackbar.LENGTH_SHORT).show();
        });

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
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(enabling ? R.string.e2ee_enable_confirm_title
                        : R.string.e2ee_disable_confirm_title)
                .setMessage(enabling ? R.string.e2ee_enable_confirm_body
                        : R.string.e2ee_disable_confirm_body)
                .setPositiveButton(enabling ? R.string.e2ee_action_turn_on
                                : R.string.e2ee_action_turn_off,
                        (d, w) -> viewModel.setEnabled(enabling))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void render(@NonNull EncryptionViewModel.UiState state) {
        toggle.setEnabled(!state.busy && !state.loading);
        toggle.setOnCheckedChangeListener(null);
        toggle.setChecked(state.enabled);
        toggle.setOnCheckedChangeListener(toggleListener);

        if (state.errorCode == null) {
            status.setVisibility(View.GONE);
        } else {
            status.setVisibility(View.VISIBLE);
            status.setText(errorText(state.errorCode));
        }

        if (state.safetyCode == null) {
            safetyCard.setVisibility(View.GONE);
        } else {
            safetyCard.setVisibility(View.VISIBLE);
            safetyCode.setText(state.safetyCode);
            String name = peerName == null || peerName.isBlank()
                    ? getString(R.string.e2ee_peer_fallback) : peerName;
            safetyHint.setText(getString(R.string.e2ee_safety_code_body, name));
        }

        devicesList.removeAllViews();
        if (state.devices.isEmpty()) {
            devicesCard.setVisibility(View.GONE);
        } else {
            devicesCard.setVisibility(View.VISIBLE);
            String currentId = viewModel.currentDeviceId();
            for (MemberDeviceDto d : state.devices) {
                TextView row = new TextView(requireContext());
                String shortId = d.getDeviceId() != null && d.getDeviceId().length() > 8
                        ? d.getDeviceId().substring(0, 8) : String.valueOf(d.getDeviceId());
                String label = getString(R.string.e2ee_device_row, shortId);
                if (d.getDeviceId() != null && d.getDeviceId().equals(currentId)) {
                    label += " · " + getString(R.string.e2ee_this_device);
                }
                row.setText(label);
                row.setTextAppearance(R.style.TextAppearance_NexaChat_BodyMedium);
                devicesList.addView(row);
            }
        }
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
            default:
                return getString(R.string.error_generic);
        }
    }
}
