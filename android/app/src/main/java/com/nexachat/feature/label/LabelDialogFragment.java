package com.nexachat.feature.label;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.annotation.DimenRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.fragment.app.DialogFragment;
import androidx.lifecycle.Observer;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.ViewModelProvider;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.nexachat.R;
import com.nexachat.feature.ui.FormDialogs;

/** Label manager and conversation assignment dialog. */
public final class LabelDialogFragment extends DialogFragment {
    private static final String ARG_CONVERSATION_ID = "conversationId";

    private LabelViewModel viewModel;
    @Nullable private String conversationId;
    private boolean manageMode;
    private LinearLayout content;
    private LinearLayout rows;
    private TextView status;
    private ProgressBar progress;
    private Button retryButton;
    private Button modeButton;
    private Button createButton;
    private long renderedErrorVersion = -1;
    private Observer<LabelUiState> uiStateObserver;
    private boolean uiStateObserverRegistered;
    @VisibleForTesting private int renderPassCountForTests;

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (uiStateObserver == null) {
            uiStateObserver = this::render;
        }
    }

    @VisibleForTesting
    final Observer<LabelUiState> uiStateObserverForTests() {
        return uiStateObserver;
    }

    @VisibleForTesting
    int renderInvocationCountForTests() {
        return renderPassCountForTests;
    }

    @NonNull
    public static LabelDialogFragment create(@Nullable String conversationId) {
        LabelDialogFragment fragment = new LabelDialogFragment();
        Bundle args = new Bundle();
        if (conversationId != null) args.putString(ARG_CONVERSATION_ID, conversationId);
        fragment.setArguments(args);
        return fragment;
    }

    public static void show(@NonNull FragmentActivity activity, @Nullable String conversationId) {
        create(conversationId).show(activity.getSupportFragmentManager(), "conversation-labels");
    }

    @NonNull
    @Override
    public android.app.Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        detachUiStateObserver();
        discardDialogContent();
        conversationId = getArguments() == null
                ? null : getArguments().getString(ARG_CONVERSATION_ID);
        manageMode = conversationId == null;
        viewModel = new ViewModelProvider(requireActivity()).get(LabelViewModel.class);
        viewModel.load();
        if (conversationId != null) viewModel.loadAssignment(conversationId);

        content = new LinearLayout(requireContext());
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dimenPx(R.dimen.dialog_padding), dimenPx(R.dimen.space_xs), dimenPx(R.dimen.dialog_padding), dimenPx(R.dimen.space_xs));

        status = new TextView(requireContext());
        status.setTextAppearance(R.style.TextAppearance_NexaChat_BodyMedium);
        status.setVisibility(View.GONE);
        LinearLayout.LayoutParams statusLp = matchWrap();
        statusLp.bottomMargin = dimenPx(R.dimen.space_sm);
        content.addView(status, statusLp);

        retryButton = new MaterialButton(
                requireContext(),
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        retryButton.setText(R.string.label_retry);
        retryButton.setMinHeight(dimenPx(R.dimen.row_min_height_dialog));
        retryButton.setVisibility(View.GONE);
        retryButton.setOnClickListener(v -> viewModel.reloadLabels());
        LinearLayout.LayoutParams retryLp = matchWrap();
        retryLp.bottomMargin = dimenPx(R.dimen.space_sm);
        content.addView(retryButton, retryLp);

        progress = new ProgressBar(requireContext());
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressLp = new LinearLayout.LayoutParams(dimenPx(R.dimen.progress_indicator_size), dimenPx(R.dimen.progress_indicator_size));
        progressLp.gravity = Gravity.CENTER_HORIZONTAL;
        progressLp.bottomMargin = dimenPx(R.dimen.space_sm);
        content.addView(progress, progressLp);

        ScrollView scroll = new ScrollView(requireContext());
        scroll.setFillViewport(false);
        rows = new LinearLayout(requireContext());
        rows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rows, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        modeButton = new MaterialButton(
                requireContext(),
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        modeButton.setMinHeight(dimenPx(R.dimen.row_min_height_dialog));
        modeButton.setOnClickListener(v -> {
            manageMode = !manageMode;
            render(viewModel.getUiState().getValue());
        });
        LinearLayout.LayoutParams modeLp = matchWrap();
        modeLp.topMargin = dimenPx(R.dimen.space_sm);
        content.addView(modeButton, modeLp);

        createButton = new MaterialButton(requireContext());
        createButton.setText(R.string.label_create);
        createButton.setMinHeight(dimenPx(R.dimen.row_min_height_dialog));
        createButton.setOnClickListener(v -> openEditor(null));
        LinearLayout.LayoutParams createLp = matchWrap();
        createLp.topMargin = dimenPx(R.dimen.space_sm);
        content.addView(createButton, createLp);

        int title = manageMode ? R.string.label_manage_title : R.string.label_assign_title;
        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(title)
                .setView(content)
                .setNegativeButton(R.string.label_done, null)
                .create();
        return dialog;
    }

    @Override
    public void onStart() {
        super.onStart();
        bindUiStateObserverIfNeeded();
    }

    @Override
    public void onDismiss(@NonNull DialogInterface dialog) {
        releaseDialogBinding();
        super.onDismiss(dialog);
    }

    @VisibleForTesting
    void releaseDialogBindingForTests() {
        releaseDialogBinding();
    }

    @VisibleForTesting
    void bindUiStateObserverIfNeeded() {
        if (viewModel == null || uiStateObserverRegistered) return;
        if (uiStateObserver == null) {
            uiStateObserver = this::render;
        }
        viewModel.getUiState().observe(this, uiStateObserver);
        uiStateObserverRegistered = true;
    }

    @VisibleForTesting
    void detachUiStateObserver() {
        if (viewModel == null || !uiStateObserverRegistered) return;
        viewModel.getUiState().removeObserver(uiStateObserver);
        uiStateObserverRegistered = false;
    }

    private void releaseDialogBinding() {
        detachUiStateObserver();
        discardDialogContent();
    }

    private void discardDialogContent() {
        content = null;
        rows = null;
        status = null;
        progress = null;
        retryButton = null;
        modeButton = null;
        createButton = null;
    }

    private void render(@Nullable LabelUiState state) {
        if (state == null || content == null || !isAdded()) return;
        renderPassCountForTests++;
        android.app.Dialog dialog = getDialog();
        if (dialog instanceof androidx.appcompat.app.AlertDialog alert) {
            alert.setTitle(manageMode ? R.string.label_manage_title : R.string.label_assign_title);
        }
        progress.setVisibility(state.isLabelsLoading() || state.isAssignmentLoading()
                ? View.VISIBLE : View.GONE);
        retryButton.setVisibility(state.getError() != LabelUiState.Error.NONE
                && !state.isLabelsLoaded() ? View.VISIBLE : View.GONE);
        modeButton.setVisibility(conversationId == null ? View.GONE : View.VISIBLE);
        modeButton.setText(manageMode ? R.string.label_back_to_assignment : R.string.label_manage);
        modeButton.setEnabled(!state.isLabelsMutating() && !state.isAssignmentSaving());
        createButton.setVisibility(manageMode ? View.VISIBLE : View.GONE);
        createButton.setEnabled(state.isLabelsLoaded() && !state.isLabelsLoading()
                && !state.isLabelsMutating() && !state.isAssignmentSaving());
        rows.removeAllViews();

        if (state.getError() != LabelUiState.Error.NONE) {
            status.setText(LabelText.errorMessage(state.getError()));
            status.setTextColor(resolveThemeColor(requireContext(),
                    com.google.android.material.R.attr.colorError));
            status.setVisibility(View.VISIBLE);
        } else if (state.isLabelsLoaded() && state.getLabels().isEmpty()) {
            status.setText(manageMode ? R.string.label_empty_manage : R.string.label_empty_assignment);
            status.setTextColor(resolveThemeColor(requireContext(),
                    com.google.android.material.R.attr.colorOnSurfaceVariant));
            status.setVisibility(View.VISIBLE);
        } else {
            status.setVisibility(View.GONE);
        }

        if (manageMode) renderManageRows(state);
        else renderAssignmentRows(state);

        if (state.getErrorVersion() != renderedErrorVersion
                && state.getError() != LabelUiState.Error.NONE) {
            renderedErrorVersion = state.getErrorVersion();
            status.announceForAccessibility(status.getText());
        }
    }

    private void renderManageRows(@NonNull LabelUiState state) {
        for (ConversationLabel label : state.getLabels()) {
            LinearLayout row = new LinearLayout(requireContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(dimenPx(R.dimen.row_min_height_dialog));

            View dot = new View(requireContext());
            int dotSize = dimenPx(R.dimen.metadata_icon_size);
            LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dotSize, dotSize);
            dotLp.setMarginStart(dimenPx(R.dimen.space_xs));
            dotLp.setMarginEnd(dimenPx(R.dimen.space_inset));
            dot.setBackground(roundColor(label.getColor().getBackgroundColor()));
            row.addView(dot, dotLp);

            TextView labelName = new TextView(requireContext());
            labelName.setText(label.getName());
            labelName.setTextAppearance(R.style.TextAppearance_NexaChat_BodyLarge);
            labelName.setTextColor(resolveThemeColor(requireContext(),
                    com.google.android.material.R.attr.colorOnSurface));
            labelName.setGravity(Gravity.CENTER_VERTICAL);
            labelName.setContentDescription(getString(R.string.label_chip_description, label.getName()));
            row.addView(labelName, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            boolean enabled = !label.isPending() && !state.isLabelsMutating()
                    && !state.isAssignmentSaving();
            MaterialButton edit = iconButton(
                    R.drawable.ic_edit,
                    getString(R.string.label_edit_description, label.getName()));
            edit.setEnabled(enabled);
            edit.setOnClickListener(v -> openEditor(label));
            row.addView(edit, wrapWrap());

            MaterialButton delete = iconButton(
                    R.drawable.ic_delete,
                    getString(R.string.label_delete_description, label.getName()));
            delete.setEnabled(enabled);
            delete.setOnClickListener(v -> confirmDelete(label));
            row.addView(delete, wrapWrap());
            LinearLayout.LayoutParams rowLp = matchWrap();
            rowLp.bottomMargin = dimenPx(R.dimen.label_row_gap);
            rows.addView(row, rowLp);
        }
    }

    private MaterialButton iconButton(int iconRes, @NonNull CharSequence contentDescription) {
        MaterialButton button = new MaterialButton(
                requireContext(),
                null,
                com.google.android.material.R.attr.materialIconButtonStyle);
        button.setIconResource(iconRes);
        button.setIconTint(ColorStateList.valueOf(resolveThemeColor(requireContext(),
                com.google.android.material.R.attr.colorOnSurfaceVariant)));
        button.setMinWidth(dimenPx(R.dimen.min_touch_target));
        button.setMinimumWidth(dimenPx(R.dimen.min_touch_target));
        button.setMinHeight(dimenPx(R.dimen.min_touch_target));
        button.setMinimumHeight(dimenPx(R.dimen.min_touch_target));
        button.setContentDescription(contentDescription);
        return button;
    }

    private void renderAssignmentRows(@NonNull LabelUiState state) {
        boolean enabled = !state.isAssignmentLoading() && !state.isAssignmentSaving()
                && !state.isLabelsMutating();
        for (ConversationLabel label : state.getLabels()) {
            LinearLayout row = new LinearLayout(requireContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(dimenPx(R.dimen.row_min_height_dialog));

            CheckBox checkBox = new CheckBox(requireContext());
            checkBox.setText(label.getName());
            checkBox.setMinHeight(dimenPx(R.dimen.row_min_height_dialog));
            checkBox.setButtonTintList(ColorStateList.valueOf(label.getColor().getBackgroundColor()));
            checkBox.setChecked(state.getAssignmentIds().contains(label.getId()));
            checkBox.setEnabled(enabled && !label.isPending());
            checkBox.setContentDescription(getString(R.string.label_assign_checkbox, label.getName()));
            checkBox.setOnCheckedChangeListener((button, checked) -> {
                if (conversationId != null) {
                    viewModel.toggleAssignment(conversationId, label.getId(), checked);
                }
            });
            row.addView(checkBox, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            rows.addView(row, matchWrap());
        }
    }

    private void openEditor(@Nullable ConversationLabel existing) {
        LabelUiState state = viewModel.getUiState().getValue();
        if (existing == null && state != null && state.getLabels().size() >= LabelRules.MAX_LABELS) {
            status.setText(R.string.label_error_label_limit);
            status.setVisibility(View.VISIBLE);
            status.announceForAccessibility(status.getText());
            return;
        }
        // A real DialogFragment so the editor and its typed input survive
        // configuration changes — a raw AlertDialog died with the Activity.
        LabelEditorDialogFragment.show(this, existing);
    }

    private void confirmDelete(@NonNull ConversationLabel label) {
        FormDialogs.markDestructive(new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.label_delete_title)
                .setMessage(getString(R.string.label_delete_message, label.getName()))
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_delete,
                        (dialog, which) -> viewModel.deleteLabel(label.getId()))
                .show());
    }

    private int dimenPx(@DimenRes int dimenRes) {
        return getResources().getDimensionPixelSize(dimenRes);
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static LinearLayout.LayoutParams wrapWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static GradientDrawable roundColor(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(1000f);
        return drawable;
    }

    private static int resolveThemeColor(@NonNull Context context, int attr) {
        android.util.TypedValue value = new android.util.TypedValue();
        if (!context.getTheme().resolveAttribute(attr, value, true)) return Color.GRAY;
        if (value.resourceId != 0) {
            return androidx.core.content.ContextCompat.getColor(context, value.resourceId);
        }
        return value.data;
    }
}
