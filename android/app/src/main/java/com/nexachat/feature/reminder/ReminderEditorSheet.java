package com.nexachat.feature.reminder;

import android.app.Dialog;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModelProvider;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.datepicker.CalendarConstraints;
import com.google.android.material.datepicker.MaterialDatePicker;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.timepicker.MaterialTimePicker;
import com.google.android.material.timepicker.TimeFormat;
import com.nexachat.R;
import com.nexachat.databinding.SheetReminderEditorBinding;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/** Create/edit sheet for one reminder; the board ViewModel owns validation and the request. */
public final class ReminderEditorSheet extends BottomSheetDialogFragment {

    private static final String ARG_ID = "reminderId";
    private static final String ARG_TITLE = "reminderTitle";
    private static final String ARG_REMIND_AT = "reminderAt";
    private static final String ARG_REPEAT = "reminderRepeat";
    private static final String STATE_TITLE = "editor.title";
    private static final String STATE_REMIND_AT = "editor.remindAt";
    private static final String STATE_REPEAT = "editor.repeat";

    private SheetReminderEditorBinding binding;
    private ReminderViewModel viewModel;
    @Nullable private String reminderId;
    private Instant selected;
    private ReminderRepeat repeat = ReminderRepeat.NONE;
    private long initialSavedSequence;

    @NonNull
    public static ReminderEditorSheet newInstance(@Nullable Reminder reminder) {
        ReminderEditorSheet sheet = new ReminderEditorSheet();
        Bundle args = new Bundle();
        if (reminder != null) {
            args.putString(ARG_ID, reminder.getId());
            args.putString(ARG_TITLE, reminder.getTitle());
            args.putLong(ARG_REMIND_AT, reminder.getRemindAt().toEpochMilli());
            args.putString(ARG_REPEAT, reminder.getRepeat().name());
        }
        sheet.setArguments(args);
        return sheet;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        BottomSheetDialog dialog = (BottomSheetDialog) super.onCreateDialog(savedInstanceState);
        dialog.setOnShowListener(ignored ->
                dialog.getBehavior().setSkipCollapsed(true));
        return dialog;
    }

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        binding = SheetReminderEditorBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(requireParentFragment()).get(ReminderViewModel.class);
        viewModel.clearEditorState();
        viewModel.openEditor();
        readArguments();
        String title = argumentsTitle();
        if (savedInstanceState != null) {
            title = savedInstanceState.getString(STATE_TITLE);
            selected = Instant.ofEpochMilli(savedInstanceState.getLong(
                    STATE_REMIND_AT, selected.toEpochMilli()));
            repeat = ReminderRepeat.fromWire(savedInstanceState.getString(STATE_REPEAT));
        }
        binding.reminderEditorTitle.setText(reminderId == null
                ? R.string.reminder_editor_new : R.string.reminder_editor_edit);
        binding.reminderEditorTitleInput.setText(title);
        binding.reminderEditorClose.setOnClickListener(ignored -> dismiss());
        binding.reminderEditorCancel.setOnClickListener(ignored -> dismiss());
        binding.reminderEditorDate.setOnClickListener(ignored -> pickDate());
        binding.reminderEditorTime.setOnClickListener(ignored -> pickTime());
        binding.reminderEditorRepeat.setOnClickListener(ignored -> pickRepeat());
        binding.reminderEditorSave.setOnClickListener(ignored -> submit());
        renderSelection();
        viewModel.getUiState().observe(getViewLifecycleOwner(), this::render);
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (binding != null) {
            outState.putString(STATE_TITLE, binding.reminderEditorTitleInput.getText() == null
                    ? "" : binding.reminderEditorTitleInput.getText().toString());
        }
        outState.putLong(STATE_REMIND_AT, selected.toEpochMilli());
        outState.putString(STATE_REPEAT, repeat.name());
    }

    @Override
    public void onDestroyView() {
        binding = null;
        super.onDestroyView();
    }

    @Override
    public void onDismiss(@NonNull android.content.DialogInterface dialog) {
        if (viewModel != null) {
            viewModel.clearEditorState();
        }
        super.onDismiss(dialog);
    }

    private void readArguments() {
        Bundle args = getArguments() == null ? Bundle.EMPTY : getArguments();
        reminderId = args.getString(ARG_ID);
        repeat = ReminderRepeat.fromWire(args.getString(ARG_REPEAT));
        long millis = args.getLong(ARG_REMIND_AT, 0L);
        selected = millis > 0L
                ? Instant.ofEpochMilli(millis)
                : Instant.now().plus(Duration.ofHours(1)).truncatedTo(ChronoUnit.MINUTES);
        initialSavedSequence = 0L;
    }

    @Nullable
    private String argumentsTitle() {
        Bundle args = getArguments();
        return args == null ? null : args.getString(ARG_TITLE);
    }

    private void renderSelection() {
        binding.reminderEditorDate.setText(getString(
                R.string.reminder_date_field, ReminderText.dateOnly(requireContext(), selected)));
        binding.reminderEditorTime.setText(getString(
                R.string.reminder_time_field, ReminderText.timeOnly(requireContext(), selected)));
        binding.reminderEditorRepeat.setText(getString(
                R.string.reminder_repeat_field,
                getString(ReminderText.repeatLabel(repeat))));
    }

    private void render(@NonNull ReminderUiState state) {
        if (binding == null) {
            return;
        }
        boolean saving = state.isSaving();
        binding.reminderEditorSave.setEnabled(!saving);
        ReminderEditorError error = state.getEditorError();
        if (error == null) {
            binding.reminderEditorError.setVisibility(View.GONE);
        } else {
            binding.reminderEditorError.setText(ReminderText.editorError(error));
            binding.reminderEditorError.setVisibility(View.VISIBLE);
            if (error == ReminderEditorError.TITLE_REQUIRED
                    || error == ReminderEditorError.TITLE_TOO_LONG) {
                EditText input = binding.reminderEditorTitleInput;
                input.requestFocus();
            }
        }
        if (!saving && state.getSavedSequence() > initialSavedSequence) {
            initialSavedSequence = state.getSavedSequence();
            dismissAllowingStateLoss();
        }
    }

    private void pickDate() {
        ZoneId zone = ZoneId.systemDefault();
        long today = ReminderTime.pickerDayMillis(Instant.now(), zone);
        CalendarConstraints constraints = new CalendarConstraints.Builder()
                .setStart(today)
                .setEnd(ReminderTime.pickerDayMillis(
                        Instant.now().plus(ReminderTime.MAX_LEAD_TIME), zone))
                .build();
        MaterialDatePicker<Long> picker = MaterialDatePicker.Builder.datePicker()
                .setTitleText(R.string.reminder_date_picker)
                .setSelection(ReminderTime.pickerDayMillis(selected, zone))
                .setCalendarConstraints(constraints)
                .build();
        picker.addOnPositiveButtonClickListener(day -> {
            java.time.ZonedDateTime current = selected.atZone(zone);
            selected = ReminderTime.combine(
                    day, current.getHour(), current.getMinute(), zone);
            renderSelection();
        });
        picker.show(getChildFragmentManager(), "reminder_date");
    }

    private void pickTime() {
        ZoneId zone = ZoneId.systemDefault();
        java.time.ZonedDateTime current = selected.atZone(zone);
        MaterialTimePicker picker = new MaterialTimePicker.Builder()
                .setTitleText(R.string.reminder_time_picker)
                .setTimeFormat(DateFormat.is24HourFormat(requireContext())
                        ? TimeFormat.CLOCK_24H
                        : TimeFormat.CLOCK_12H)
                .setHour(current.getHour())
                .setMinute(current.getMinute())
                .build();
        picker.addOnPositiveButtonClickListener(ignored -> {
            selected = ReminderTime.combine(
                    ReminderTime.pickerDayMillis(selected, zone),
                    picker.getHour(),
                    picker.getMinute(),
                    zone);
            renderSelection();
        });
        picker.show(getChildFragmentManager(), "reminder_time");
    }

    private void pickRepeat() {
        CharSequence[] labels = new CharSequence[]{
                getString(R.string.reminder_repeat_none),
                getString(R.string.reminder_repeat_daily),
                getString(R.string.reminder_repeat_weekly),
                getString(R.string.reminder_repeat_monthly)
        };
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.reminder_editor_repeat)
                .setSingleChoiceItems(labels, repeat.ordinal(), (dialog, which) -> {
                    repeat = ReminderRepeat.values()[which];
                    renderSelection();
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void submit() {
        if (binding == null || viewModel == null) {
            return;
        }
        EditText input = binding.reminderEditorTitleInput;
        viewModel.saveReminder(reminderId, input.getText() == null ? "" : input.getText().toString(),
                selected, repeat);
    }
}
