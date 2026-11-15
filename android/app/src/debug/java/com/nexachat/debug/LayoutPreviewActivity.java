package com.nexachat.debug;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.Nullable;
import com.nexachat.R;

/**
 * Debug-only renderer for evidence screenshots. Starts under
 * {@code adb shell am start} (the manifest gates it behind the DUMP
 * permission, which only the shell uid holds) with an Intent extra
 * {@code scenario}:
 * <ul>
 *   <li>{@code chat_keyset_changed} — fragment_chat with the changed-keyset
 *       notice visible.</li>
 *   <li>{@code chat_downgrade} — fragment_chat with the downgrade notice.</li>
 *   <li>{@code encryption_verify} — fragment_encryption with the NC1
 *       verification card in the not-yet-verified state.</li>
 *   <li>{@code encryption_verified} — same card after confirmation.</li>
 * </ul>
 * Views are inflated standalone; no ViewModel, session or network is touched.
 */
public final class LayoutPreviewActivity extends Activity {

    /** Sample NC1 code — shape only, not a real digest. */
    private static final String SAMPLE_CODE =
            "NC1 A1B2 C3D4 E5F6 0123\n4567 89AB CDEF 0A1B\n"
                    + "2C3D 4E5F 6071 8293\nA4B5 C6D7 E8F9 0B1C";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        render();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        render();
    }

    private void render() {
        String scenario = getIntent().getStringExtra("scenario");
        if (scenario == null) {
            scenario = "chat_keyset_changed";
        }
        View root = switch (scenario) {
            case "encryption_verify" -> renderEncryption(false);
            case "encryption_verified" -> renderEncryption(true);
            case "chat_downgrade" -> renderChat(false);
            default -> renderChat(true);
        };
        setContentView(root);
    }

    private View renderChat(boolean changed) {
        View root = LayoutInflater.from(this)
                .inflate(R.layout.fragment_chat, new FrameLayout(this), false);
        View notice = root.findViewById(R.id.keysetNotice);
        if (notice != null) {
            ((TextView) notice.findViewById(R.id.keysetNoticeText))
                    .setText(changed
                            ? R.string.e2ee_keyset_changed_notice
                            : R.string.e2ee_downgrade_notice);
            ((TextView) notice.findViewById(R.id.keysetNoticeAction))
                    .setText(changed
                            ? R.string.e2ee_keyset_notice_action
                            : R.string.e2ee_downgrade_notice_action);
            notice.setVisibility(View.VISIBLE);
        }
        return root;
    }

    private View renderEncryption(boolean verified) {
        View root = LayoutInflater.from(this)
                .inflate(R.layout.fragment_encryption, new FrameLayout(this), false);
        View card = root.findViewById(R.id.verificationCard);
        if (card != null) {
            ((TextView) card.findViewById(R.id.verificationCode))
                    .setText(SAMPLE_CODE);
            TextView hint = card.findViewById(R.id.verifyHint);
            if (hint != null) {
                hint.setText(getString(R.string.e2ee_safety_code_body, "peer"));
            }
            ImageView icon = card.findViewById(R.id.trustIcon);
            TextView state = card.findViewById(R.id.trustStateText);
            int label = verified
                    ? R.string.e2ee_verify_state_verified
                    : R.string.e2ee_verify_state_not_verified;
            int iconRes = verified
                    ? R.drawable.ic_check_circle
                    : R.drawable.ic_help_outline;
            if (icon != null) {
                icon.setImageResource(iconRes);
                icon.setContentDescription(getString(label));
            }
            if (state != null) {
                state.setText(label);
            }
            View verifyButton = card.findViewById(R.id.verifyButton);
            if (verifyButton != null) {
                verifyButton.setVisibility(verified ? View.GONE : View.VISIBLE);
            }
            card.setVisibility(View.VISIBLE);
        }
        return root;
    }
}
