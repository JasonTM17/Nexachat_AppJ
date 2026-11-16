package com.nexachat.feature.label;

import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import androidx.annotation.DimenRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.nexachat.R;
import com.nexachat.feature.ui.FormDialogs;

/**
 * New/edit label editor. A real {@link DialogFragment} so the form and its
 * typed input survive configuration changes; the raw AlertDialog it replaces
 * was destroyed with the Activity while the parent labels fragment came back.
 */
public final class LabelEditorDialogFragment extends DialogFragment {

    private static final String TAG = "label-editor";
    private static final String ARG_LABEL_ID = "labelId";
    private static final String ARG_LABEL_NAME = "labelName";
    private static final String ARG_LABEL_COLOR = "labelColor";
    private static final String STATE_NAME = "editorName";
    private static final String STATE_COLOR = "editorColor";

    @Nullable private LabelViewModel viewModel;
    @Nullable private String labelId;
    @Nullable private TextInputLayout nameLayout;
    @Nullable private TextInputEditText nameInput;
    @Nullable private ChipGroup colorGroup;

    public static void show(@NonNull Fragment parent, @Nullable ConversationLabel existing) {
        LabelEditorDialogFragment fragment = new LabelEditorDialogFragment();
        if (existing != null) {
            Bundle args = new Bundle();
            args.putString(ARG_LABEL_ID, existing.getId());
            args.putString(ARG_LABEL_NAME, existing.getName());
            args.putString(ARG_LABEL_COLOR, existing.getColor().name());
            fragment.setArguments(args);
        }
        fragment.show(parent.getChildFragmentManager(), TAG);
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        viewModel = new ViewModelProvider(requireActivity()).get(LabelViewModel.class);
        Bundle args = getArguments();
        labelId = args == null ? null : args.getString(ARG_LABEL_ID);
        String initialName = args == null ? null : args.getString(ARG_LABEL_NAME);
        LabelColor initialColor = args == null
                ? LabelColor.INDIGO : LabelColor.fromId(args.getString(ARG_LABEL_COLOR));
        if (savedInstanceState != null) {
            initialName = savedInstanceState.getString(STATE_NAME, initialName);
            initialColor = LabelColor.fromId(savedInstanceState.getString(STATE_COLOR));
        }

        LinearLayout form = new LinearLayout(requireContext());
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dimenPx(R.dimen.dialog_padding), dimenPx(R.dimen.space_inset),
                dimenPx(R.dimen.dialog_padding), dimenPx(R.dimen.space_sm));

        nameLayout = new TextInputLayout(
                requireContext(),
                null,
                com.google.android.material.R.attr.textInputStyle);
        nameLayout.setHint(getString(R.string.label_name_hint));
        nameLayout.setCounterEnabled(true);
        nameLayout.setCounterMaxLength(LabelRules.MAX_NAME_CODE_POINTS);

        nameInput = new TextInputEditText(nameLayout.getContext());
        nameInput.setSingleLine(true);
        nameInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        nameInput.setContentDescription(getString(R.string.label_name_hint));
        if (initialName != null) {
            nameInput.setText(initialName);
        }
        nameLayout.addView(nameInput);
        LinearLayout.LayoutParams nameLp = matchWrap();
        nameLp.bottomMargin = dimenPx(R.dimen.space_inset);
        form.addView(nameLayout, nameLp);

        colorGroup = new ChipGroup(requireContext());
        colorGroup.setSingleSelection(true);
        colorGroup.setSelectionRequired(true);
        colorGroup.setSingleLine(false);
        colorGroup.setChipSpacingHorizontal(dimenPx(R.dimen.space_sm));
        colorGroup.setChipSpacingVertical(dimenPx(R.dimen.space_sm));
        for (LabelColor color : LabelColor.values()) {
            Chip chip = new Chip(requireContext());
            chip.setId(View.generateViewId());
            chip.setCheckable(true);
            chip.setCheckedIconVisible(true);
            chip.setCheckedIconTint(ColorStateList.valueOf(Color.WHITE));
            chip.setChipCornerRadius(getResources().getDimension(R.dimen.corner_sm));
            chip.setText(LabelText.colorName(color));
            chip.setTextColor(Color.WHITE);
            chip.setChipBackgroundColor(ColorStateList.valueOf(color.getBackgroundColor()));
            chip.setMinHeight(dimenPx(R.dimen.row_min_height_dialog));
            chip.setTag(color);
            colorGroup.addView(chip, wrapWrap());
            if (color == initialColor) {
                chip.setChecked(true);
            }
        }
        form.addView(colorGroup, matchWrap());

        AlertDialog editor = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(labelId == null ? R.string.label_create_title : R.string.label_edit_title)
                .setView(FormDialogs.scrollableBody(form))
                .setPositiveButton(R.string.label_save, null)
                .setNegativeButton(R.string.action_cancel, null)
                .create();
        editor.setOnShowListener(ignored -> {
            FormDialogs.resizeAboveIme(editor);
            editor.getButton(AlertDialog.BUTTON_POSITIVE)
                    .setOnClickListener(v -> submit());
        });
        return editor;
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (nameInput != null && nameInput.getText() != null) {
            outState.putString(STATE_NAME, nameInput.getText().toString());
        }
        LabelColor checked = checkedColor();
        if (checked != null) {
            outState.putString(STATE_COLOR, checked.name());
        }
    }

    @Nullable
    private LabelColor checkedColor() {
        if (colorGroup == null) {
            return null;
        }
        int checkedId = colorGroup.getCheckedChipId();
        View checked = checkedId == View.NO_ID ? null : colorGroup.findViewById(checkedId);
        return checked != null && checked.getTag() instanceof LabelColor value ? value : null;
    }

    private void submit() {
        if (viewModel == null || nameInput == null || nameLayout == null) {
            return;
        }
        LabelColor color = checkedColor();
        if (color == null) {
            color = LabelColor.INDIGO;
        }
        String value = LabelRules.normalizeName(
                nameInput.getText() == null ? "" : nameInput.getText().toString());
        if (!LabelRules.isValidName(value)) {
            nameLayout.setError(getString(R.string.label_error_validation));
            return;
        }
        nameLayout.setError(null);
        LabelUiState.Error rejection = labelId == null
                ? viewModel.createLabel(value, color)
                : viewModel.updateLabel(labelId, value, color);
        if (rejection == LabelUiState.Error.NONE) {
            dismiss();
        } else {
            // Keep the editor open so the typed name isn't lost and mirror the
            // rejection on the name field — the published error lands
            // asynchronously via postValue.
            nameLayout.setError(getString(LabelText.errorMessage(rejection)));
        }
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
}
