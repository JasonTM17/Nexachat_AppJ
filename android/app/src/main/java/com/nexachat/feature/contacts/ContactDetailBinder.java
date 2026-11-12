package com.nexachat.feature.contacts;

import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.fragment.app.Fragment;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.nexachat.R;
import com.nexachat.core.common.ApiError;
import com.nexachat.core.network.dto.UserDto;
import com.nexachat.core.ui.components.AvatarView;
import com.nexachat.data.Contact;
import java.util.Objects;

/**
 * One contact-detail dialog shared by the contacts list and user search.
 * The screens differ only in how the selected user reaches the ViewModel;
 * the dialog content, actions, and alias refresh behavior are identical.
 */
final class ContactDetailBinder {

    private static final String DETAIL_ROOT_TAG = "detail_root";

    private final Fragment fragment;
    private final ContactsViewModel viewModel;
    @Nullable private androidx.appcompat.app.AlertDialog dialog;
    @Nullable private LinearLayout root;
    @Nullable private TextInputEditText aliasInput;
    @Nullable private String boundUserId;
    @Nullable private String boundTitle;
    private boolean boundIsContact;
    private boolean renderingAlias;

    ContactDetailBinder(@NonNull Fragment fragment, @NonNull ContactsViewModel viewModel) {
        this.fragment = fragment;
        this.viewModel = viewModel;
    }

    /** Shows, refreshes, or dismisses the dialog for the selected detail user. */
    void render(@NonNull ContactsViewModel.UiState state) {
        if (state.getDetailUser() == null) {
            dismiss();
            return;
        }
        if (dialog == null) {
            LinearLayout newRoot = new LinearLayout(fragment.requireContext());
            newRoot.setOrientation(LinearLayout.VERTICAL);
            newRoot.setTag(DETAIL_ROOT_TAG);
            int pad = fragment.getResources().getDimensionPixelSize(R.dimen.space_lg);
            newRoot.setPadding(pad, pad, pad, pad);
            bindContent(newRoot, state);
            ScrollView scroll = new ScrollView(fragment.requireContext());
            scroll.addView(newRoot, new ScrollView.LayoutParams(
                    ScrollView.LayoutParams.MATCH_PARENT,
                    ScrollView.LayoutParams.WRAP_CONTENT));
            root = newRoot;
            dialog = new MaterialAlertDialogBuilder(fragment.requireContext())
                    .setView(scroll)
                    .setOnDismissListener(d -> {
                        viewModel.closeDetail();
                        dialog = null;
                        root = null;
                        aliasInput = null;
                        boundUserId = null;
                        boundTitle = null;
                    })
                    .show();
        } else if (root != null) {
            bindContent(root, state);
        }
    }

    /** Dismisses the dialog and forgets the bound content. */
    void dismiss() {
        if (dialog != null) {
            dialog.dismiss();
            dialog = null;
        }
        root = null;
        aliasInput = null;
        boundUserId = null;
        boundTitle = null;
    }

    /**
     * Tears down the dialog when the fragment view is destroyed (for example
     * on rotation) without clearing the selected contact in the ViewModel.
     */
    void releaseForViewDestroy() {
        if (dialog != null) {
            dialog.setOnDismissListener(null);
            dialog.dismiss();
        }
        dialog = null;
        root = null;
        aliasInput = null;
        boundUserId = null;
        boundTitle = null;
    }

    private void bindContent(@NonNull LinearLayout root, @NonNull ContactsViewModel.UiState state) {
        Content content = contentFor(state);
        if (content == null) {
            return;
        }
        if (canReuseAliasField(boundUserId, boundIsContact, boundTitle, content, aliasInput != null)) {
            renderingAlias = true;
            if (!aliasInput.getText().toString().equals(content.getAliasValue())) {
                aliasInput.setText(content.getAliasValue());
            }
            renderingAlias = false;
            return;
        }
        boundUserId = content.getUserId();
        boundIsContact = content.isContact();
        boundTitle = content.getTitle();
        aliasInput = null;
        root.removeAllViews();
        int spaceSm = fragment.getResources().getDimensionPixelSize(R.dimen.space_sm);
        int spaceMd = fragment.getResources().getDimensionPixelSize(R.dimen.space_md);

        AvatarView avatar = new AvatarView(fragment.requireContext());
        avatar.setName(content.getTitle());
        avatar.setAvatarUrl(content.getAvatarUrl());
        int avatarSize = Math.round(64 * fragment.getResources().getDisplayMetrics().density);
        LinearLayout.LayoutParams avatarLp = new LinearLayout.LayoutParams(avatarSize, avatarSize);
        avatarLp.gravity = Gravity.CENTER_HORIZONTAL;
        avatarLp.bottomMargin = spaceSm;
        root.addView(avatar, avatarLp);

        TextView name = new TextView(fragment.requireContext());
        name.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleLarge);
        name.setGravity(Gravity.CENTER_HORIZONTAL);
        name.setText(content.getTitle());
        root.addView(name, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView phone = new TextView(fragment.requireContext());
        phone.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        phone.setGravity(Gravity.CENTER_HORIZONTAL);
        phone.setText(content.getPhone());
        LinearLayout.LayoutParams phoneLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        phoneLp.bottomMargin = spaceMd;
        root.addView(phone, phoneLp);

        if (content.showsError()) {
            TextView err = new TextView(fragment.requireContext());
            err.setText(content.getErrorMessageId());
            LinearLayout.LayoutParams errLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            errLp.bottomMargin = spaceSm;
            root.addView(err, errLp);
        }

        if (content.showsAliasEditor()) {
            TextInputLayout aliasLayout = new TextInputLayout(
                    fragment.requireContext(),
                    null,
                    com.google.android.material.R.attr.textInputOutlinedStyle);
            aliasLayout.setHint(fragment.getString(R.string.contact_alias_label));
            float radius = fragment.getResources().getDimension(R.dimen.corner_md);
            aliasLayout.setBoxCornerRadii(radius, radius, radius, radius);
            TextInputEditText alias = new TextInputEditText(aliasLayout.getContext());
            alias.setSingleLine(true);
            renderingAlias = true;
            alias.setText(content.getAliasValue());
            renderingAlias = false;
            alias.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                    if (!renderingAlias && !s.toString().equals(content.getAliasValue())) {
                        viewModel.onAliasChange(s.toString());
                    }
                }
                @Override public void afterTextChanged(Editable s) {}
            });
            aliasInput = alias;
            aliasLayout.addView(alias);
            LinearLayout.LayoutParams aliasLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            aliasLp.bottomMargin = spaceSm;
            root.addView(aliasLayout, aliasLp);

            MaterialButton saveAlias = new MaterialButton(
                    fragment.requireContext(),
                    null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle);
            saveAlias.setText(R.string.action_save);
            saveAlias.setOnClickListener(v -> viewModel.saveAlias());
            LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            saveLp.bottomMargin = spaceSm;
            root.addView(saveAlias, saveLp);
        }

        MaterialButton message = new MaterialButton(fragment.requireContext());
        message.setText(R.string.action_message);
        message.setEnabled(content.isMessageEnabled());
        message.setOnClickListener(v -> viewModel.messageSelected());
        LinearLayout.LayoutParams messageLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        messageLp.bottomMargin = spaceSm;
        root.addView(message, messageLp);

        MaterialButton secondary = new MaterialButton(
                fragment.requireContext(),
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        secondary.setEnabled(content.isActionEnabled());
        secondary.setText(content.getSecondaryActionTextRes());
        if (content.isContact()) {
            secondary.setOnClickListener(v -> new MaterialAlertDialogBuilder(fragment.requireContext())
                    .setMessage(R.string.confirm_remove_contact)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(
                            R.string.action_remove_contact,
                            (d, w) -> viewModel.removeSelectedContact())
                    .show());
        } else {
            secondary.setOnClickListener(v -> viewModel.addDetailContact());
        }
        root.addView(secondary, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        if (content.showsProgress()) {
            ProgressBar bar = new ProgressBar(fragment.requireContext());
            LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            barLp.gravity = Gravity.CENTER_HORIZONTAL;
            barLp.topMargin = spaceSm;
            root.addView(bar, barLp);
        }
    }

    /**
     * Content shared by both screens. Pure state mapping so it is testable
     * without inflating the dialog.
     */
    @Nullable
    static Content contentFor(@NonNull ContactsViewModel.UiState state) {
        UserDto user = state.getDetailUser();
        if (user == null) {
            return null;
        }
        Contact contact = state.getDetailContact();
        String title = contact != null ? contact.getPrimaryLabel() : user.getDisplayName();
        String avatarUrl = contact != null ? contact.getAvatarUrl() : user.getAvatarUrl();
        return new Content(
                user.getId(),
                contact != null,
                title,
                avatarUrl,
                user.getPhone(),
                state.getAliasInput(),
                state.isDetailBusy(),
                state.getDetailError());
    }

    static boolean canReuseAliasField(
            @Nullable String boundUserId,
            boolean boundIsContact,
            @Nullable String boundTitle,
            @NonNull Content content,
            boolean hasAliasField) {
        return hasAliasField
                && content.showsAliasEditor()
                && content.getUserId().equals(boundUserId)
                && content.isContact() == boundIsContact
                && Objects.equals(boundTitle, content.getTitle())
                && !content.showsError()
                && !content.isBusy();
    }

    static final class Content {

        private final String userId;
        private final boolean contact;
        private final String title;
        @Nullable private final String avatarUrl;
        private final String phone;
        private final String aliasValue;
        private final boolean busy;
        @Nullable private final ApiError error;

        Content(
                String userId,
                boolean contact,
                String title,
                @Nullable String avatarUrl,
                String phone,
                String aliasValue,
                boolean busy,
                @Nullable ApiError error) {
            this.userId = userId;
            this.contact = contact;
            this.title = title;
            this.avatarUrl = avatarUrl;
            this.phone = phone;
            this.aliasValue = aliasValue;
            this.busy = busy;
            this.error = error;
        }

        String getUserId() {
            return userId;
        }

        boolean isContact() {
            return contact;
        }

        String getTitle() {
            return title;
        }

        @Nullable
        String getAvatarUrl() {
            return avatarUrl;
        }

        String getPhone() {
            return phone;
        }

        String getAliasValue() {
            return aliasValue;
        }

        boolean isBusy() {
            return busy;
        }

        @Nullable
        ApiError getError() {
            return error;
        }

        boolean showsAliasEditor() {
            return contact;
        }

        boolean showsError() {
            return error != null;
        }

        @StringRes
        int getErrorMessageId() {
            return error == null ? 0 : ContactErrorIds.messageId(error);
        }

        @StringRes
        int getSecondaryActionTextRes() {
            return contact ? R.string.action_remove_contact : R.string.action_add_contact;
        }

        boolean isMessageEnabled() {
            return !busy;
        }

        boolean isActionEnabled() {
            return !busy;
        }

        boolean showsProgress() {
            return busy;
        }
    }
}
