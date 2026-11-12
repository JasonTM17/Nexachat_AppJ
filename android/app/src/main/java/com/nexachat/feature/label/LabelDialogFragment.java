package com.nexachat.feature.label;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.content.DialogInterface;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.fragment.app.DialogFragment;
import androidx.lifecycle.Observer;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.ViewModelProvider;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.nexachat.R;

/** Label manager and conversation assignment dialog. */
public final class LabelDialogFragment extends DialogFragment {
    private static final String ARG_CONVERSATION_ID = "conversationId";
    private static final int DIALOG_PADDING_DP = 20;
    private static final int ROW_MIN_HEIGHT_DP = 52;

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
        content.setPadding(dp(DIALOG_PADDING_DP), dp(4), dp(DIALOG_PADDING_DP), dp(4));

        status = new TextView(requireContext());
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        status.setVisibility(View.GONE);
        LinearLayout.LayoutParams statusLp = matchWrap();
        statusLp.bottomMargin = dp(8);
        content.addView(status, statusLp);

        retryButton = new MaterialButton(
                requireContext(),
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        retryButton.setText(R.string.label_retry);
        retryButton.setMinHeight(dp(ROW_MIN_HEIGHT_DP));
        retryButton.setVisibility(View.GONE);
        retryButton.setOnClickListener(v -> viewModel.reloadLabels());
        LinearLayout.LayoutParams retryLp = matchWrap();
        retryLp.bottomMargin = dp(8);
        content.addView(retryButton, retryLp);

        progress = new ProgressBar(requireContext());
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressLp = new LinearLayout.LayoutParams(dp(28), dp(28));
        progressLp.gravity = Gravity.CENTER_HORIZONTAL;
        progressLp.bottomMargin = dp(8);
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
        modeButton.setMinHeight(dp(ROW_MIN_HEIGHT_DP));
        modeButton.setOnClickListener(v -> {
            manageMode = !manageMode;
            render(viewModel.getUiState().getValue());
        });
        LinearLayout.LayoutParams modeLp = matchWrap();
        modeLp.topMargin = dp(8);
        content.addView(modeButton, modeLp);

        createButton = new MaterialButton(requireContext());
        createButton.setText(R.string.label_create);
        createButton.setMinHeight(dp(ROW_MIN_HEIGHT_DP));
        createButton.setOnClickListener(v -> openEditor(null));
        LinearLayout.LayoutParams createLp = matchWrap();
        createLp.topMargin = dp(8);
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
            row.setMinimumHeight(dp(ROW_MIN_HEIGHT_DP));

            TextView labelName = new TextView(requireContext());
            labelName.setText(label.getName());
            labelName.setTextColor(Color.WHITE);
            labelName.setTypeface(null, Typeface.BOLD);
            labelName.setGravity(Gravity.CENTER_VERTICAL);
            labelName.setPadding(dp(12), 0, dp(12), 0);
            labelName.setMinHeight(dp(48));
            labelName.setClickable(true);
            labelName.setFocusable(true);
            labelName.setBackground(roundColor(label.getColor().getBackgroundColor()));
            labelName.setContentDescription(getString(R.string.label_chip_description, label.getName()));
            labelName.setEnabled(!label.isPending() && !state.isLabelsMutating()
                    && !state.isAssignmentSaving());
            labelName.setOnClickListener(v -> openEditor(label));
            LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            nameLp.setMarginEnd(dp(8));
            row.addView(labelName, nameLp);

            MaterialButton delete = new MaterialButton(
                    requireContext(),
                    null,
                    com.google.android.material.R.attr.borderlessButtonStyle);
            delete.setText(R.string.action_delete);
            delete.setContentDescription(getString(R.string.label_delete_description, label.getName()));
            delete.setMinHeight(dp(ROW_MIN_HEIGHT_DP));
            delete.setEnabled(!label.isPending() && !state.isLabelsMutating()
                    && !state.isAssignmentSaving());
            delete.setOnClickListener(v -> confirmDelete(label));
            row.addView(delete, wrapWrap());
            LinearLayout.LayoutParams rowLp = matchWrap();
            rowLp.bottomMargin = dp(6);
            rows.addView(row, rowLp);
        }
    }

    private void renderAssignmentRows(@NonNull LabelUiState state) {
        boolean enabled = !state.isAssignmentLoading() && !state.isAssignmentSaving()
                && !state.isLabelsMutating();
        for (ConversationLabel label : state.getLabels()) {
            LinearLayout row = new LinearLayout(requireContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(dp(ROW_MIN_HEIGHT_DP));

            CheckBox checkBox = new CheckBox(requireContext());
            checkBox.setText(label.getName());
            checkBox.setMinHeight(dp(ROW_MIN_HEIGHT_DP));
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

        LinearLayout form = new LinearLayout(requireContext());
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(12), dp(20), dp(8));

        TextInputLayout nameLayout = new TextInputLayout(
                requireContext(),
                null,
                com.google.android.material.R.attr.textInputOutlinedStyle);
        float radius = getResources().getDimension(R.dimen.corner_md);
        nameLayout.setBoxCornerRadii(radius, radius, radius, radius);
        nameLayout.setHint(getString(R.string.label_name_hint));
        nameLayout.setCounterEnabled(true);
        nameLayout.setCounterMaxLength(LabelRules.MAX_NAME_CODE_POINTS);

        TextInputEditText name = new TextInputEditText(nameLayout.getContext());
        name.setSingleLine(true);
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        name.setContentDescription(getString(R.string.label_name_hint));
        if (existing != null) name.setText(existing.getName());
        nameLayout.addView(name);
        LinearLayout.LayoutParams nameLp = matchWrap();
        nameLp.bottomMargin = dp(12);
        form.addView(nameLayout, nameLp);

        ChipGroup colorGroup = new ChipGroup(requireContext());
        colorGroup.setSingleSelection(true);
        colorGroup.setSelectionRequired(true);
        colorGroup.setSingleLine(false);
        for (LabelColor color : LabelColor.values()) {
            Chip chip = new Chip(requireContext());
            chip.setId(View.generateViewId());
            chip.setCheckable(true);
            chip.setCheckedIconVisible(true);
            chip.setCheckedIconTint(ColorStateList.valueOf(Color.WHITE));
            chip.setText(LabelText.colorName(color));
            chip.setTextColor(Color.WHITE);
            chip.setChipBackgroundColor(ColorStateList.valueOf(color.getBackgroundColor()));
            chip.setMinHeight(dp(ROW_MIN_HEIGHT_DP));
            chip.setTag(color);
            colorGroup.addView(chip, wrapWrap());
            if (existing != null && existing.getColor() == color) chip.setChecked(true);
            if (existing == null && color == LabelColor.INDIGO) chip.setChecked(true);
        }
        form.addView(colorGroup, matchWrap());

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(existing == null ? R.string.label_create_title : R.string.label_edit_title)
                .setView(form)
                .setPositiveButton(R.string.label_save, null)
                .setNegativeButton(R.string.action_cancel, null);
        androidx.appcompat.app.AlertDialog editor = builder.create();
        editor.setOnShowListener(ignored -> editor.getButton(
                        androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    int checkedId = colorGroup.getCheckedChipId();
                    View checked = checkedId == View.NO_ID ? null : colorGroup.findViewById(checkedId);
                    LabelColor color = checked != null && checked.getTag() instanceof LabelColor value
                            ? value : LabelColor.INDIGO;
                    String value = LabelRules.normalizeName(
                            name.getText() == null ? "" : name.getText().toString());
                    if (!LabelRules.isValidName(value)) {
                        nameLayout.setError(getString(R.string.label_error_validation));
                        return;
                    }
                    nameLayout.setError(null);
                    if (existing == null) viewModel.createLabel(value, color);
                    else viewModel.updateLabel(existing.getId(), value, color);
                    editor.dismiss();
                }));
        editor.show();
    }

    private void confirmDelete(@NonNull ConversationLabel label) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.label_delete_title)
                .setMessage(getString(R.string.label_delete_message, label.getName()))
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_delete,
                        (dialog, which) -> viewModel.deleteLabel(label.getId()))
                .show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
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
