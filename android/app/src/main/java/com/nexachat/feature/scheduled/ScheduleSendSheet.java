package com.nexachat.feature.scheduled;

import android.os.Bundle;
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModelProvider;
import com.google.android.material.datepicker.CalendarConstraints;
import com.google.android.material.datepicker.MaterialDatePicker;
import com.google.android.material.timepicker.MaterialTimePicker;
import com.google.android.material.timepicker.TimeFormat;
import com.nexachat.R;
import com.nexachat.databinding.SheetScheduleSendBinding;
import com.nexachat.feature.ui.NexaBottomSheetFragment;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/**
 * Date/time sheet for scheduling the current composer draft or editing a
 * still-PENDING scheduled row. In create mode the draft body is a frozen
 * snapshot passed as an argument; in edit mode the body becomes editable and
 * the existing send time seeds the picker. The shared conversation-scoped
 * {@link ScheduledMessagesViewModel} owns validation and the request.
 */
public final class ScheduleSendSheet extends NexaBottomSheetFragment {

    private static final String ARG_BODY = "body";
    private static final String ARG_EDIT_ID = "editId";
    private static final String ARG_EDIT_SEND_AT = "editSendAt";
    private static final String STATE_SEND_AT = "schedule.sendAt";

    private SheetScheduleSendBinding binding;
    private ScheduledMessagesViewModel viewModel;
    private String draftBody = "";
    @Nullable private String editId;
    private Instant selected;
    private long initialSavedSequence;

    @NonNull
    public static ScheduleSendSheet newInstance(@NonNull String body) {
        ScheduleSendSheet sheet = new ScheduleSendSheet();
        Bundle args = new Bundle();
        args.putString(ARG_BODY, body);
        sheet.setArguments(args);
        return sheet;
    }

    @NonNull
    public static ScheduleSendSheet newEditInstance(@NonNull ScheduledMessage message) {
        ScheduleSendSheet sheet = new ScheduleSendSheet();
        Bundle args = new Bundle();
        args.putString(ARG_BODY, message.getBody());
        args.putString(ARG_EDIT_ID, message.getId());
        args.putLong(ARG_EDIT_SEND_AT, message.getSendAt().toEpochMilli());
        sheet.setArguments(args);
        return sheet;
    }

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        binding = SheetScheduleSendBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(requireParentFragment())
                .get(ScheduledMessagesViewModel.class);
        viewModel.openScheduler();
        Bundle args = getArguments() == null ? Bundle.EMPTY : getArguments();
        draftBody = args.getString(ARG_BODY, "");
        editId = args.getString(ARG_EDIT_ID, null);
        selected = editId == null
                ? Instant.now().plus(Duration.ofMinutes(5)).truncatedTo(ChronoUnit.MINUTES)
                : Instant.ofEpochMilli(args.getLong(ARG_EDIT_SEND_AT));
        if (savedInstanceState != null) {
            selected = Instant.ofEpochMilli(
                    savedInstanceState.getLong(STATE_SEND_AT, selected.toEpochMilli()));
        }
        initialSavedSequence = viewModel.getUiState().getValue() == null
                ? 0L : viewModel.getUiState().getValue().getSavedSequence();
        if (editId == null) {
            binding.scheduleSendBody.setText(draftBody);
        } else {
            binding.scheduleSendBody.setVisibility(View.GONE);
            binding.scheduleSendBodyEditLayout.setVisibility(View.VISIBLE);
            binding.scheduleSendBodyEdit.setText(draftBody);
            binding.scheduleSendTitle.setText(R.string.scheduled_editor_title_edit);
            binding.scheduleSendSubmit.setText(R.string.action_save);
        }
        binding.scheduleSendClose.setOnClickListener(ignored -> dismiss());
        binding.scheduleSendCancel.setOnClickListener(ignored -> dismiss());
        binding.scheduleSendDate.setOnClickListener(ignored -> pickDate());
        binding.scheduleSendTime.setOnClickListener(ignored -> pickTime());
        binding.scheduleSendSubmit.setOnClickListener(ignored -> submit());
        renderSelection();
        applySheetInsets(binding.getRoot());
        viewModel.getUiState().observe(getViewLifecycleOwner(), this::render);
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putLong(STATE_SEND_AT, selected.toEpochMilli());
    }

    @Override
    public void onDestroyView() {
        binding = null;
        super.onDestroyView();
    }

    private void renderSelection() {
        binding.scheduleSendDate.setText(getString(R.string.scheduled_date_field,
                ScheduledMessageText.dateOnly(requireContext(), selected)));
        binding.scheduleSendTime.setText(getString(R.string.scheduled_time_field,
                ScheduledMessageText.timeOnly(requireContext(), selected)));
    }

    private void render(@NonNull ScheduledMessageUiState state) {
        if (binding == null) {
            return;
        }
        binding.scheduleSendSubmit.setEnabled(!state.isScheduling());
        binding.scheduleSendDate.setEnabled(!state.isScheduling());
        binding.scheduleSendTime.setEnabled(!state.isScheduling());
        if (editId != null) {
            binding.scheduleSendBodyEdit.setEnabled(!state.isScheduling());
        }
        ScheduledMessageEditorError error = state.getEditorError();
        if (error == null) {
            binding.scheduleSendError.setVisibility(View.GONE);
        } else {
            binding.scheduleSendError.setText(ScheduledMessageText.editorError(error));
            binding.scheduleSendError.setVisibility(View.VISIBLE);
        }
        if (!state.isScheduling() && state.getSavedSequence() > initialSavedSequence) {
            initialSavedSequence = state.getSavedSequence();
            dismissAllowingStateLoss();
        }
    }

    private void pickDate() {
        ZoneId zone = ZoneId.systemDefault();
        long today = ScheduledMessageTime.pickerDayMillis(Instant.now(), zone);
        CalendarConstraints constraints = new CalendarConstraints.Builder()
                .setStart(today)
                .build();
        MaterialDatePicker<Long> picker = MaterialDatePicker.Builder.datePicker()
                .setTitleText(R.string.scheduled_date_picker)
                .setSelection(ScheduledMessageTime.pickerDayMillis(selected, zone))
                .setCalendarConstraints(constraints)
                .build();
        picker.addOnPositiveButtonClickListener(day -> {
            java.time.ZonedDateTime current = selected.atZone(zone);
            selected = ScheduledMessageTime.combine(
                    day, current.getHour(), current.getMinute(), zone);
            renderSelection();
        });
        picker.show(getChildFragmentManager(), "schedule_date");
    }

    private void pickTime() {
        ZoneId zone = ZoneId.systemDefault();
        java.time.ZonedDateTime current = selected.atZone(zone);
        MaterialTimePicker picker = new MaterialTimePicker.Builder()
                .setTitleText(R.string.scheduled_time_picker)
                .setTimeFormat(DateFormat.is24HourFormat(requireContext())
                        ? TimeFormat.CLOCK_24H
                        : TimeFormat.CLOCK_12H)
                .setHour(current.getHour())
                .setMinute(current.getMinute())
                .build();
        picker.addOnPositiveButtonClickListener(ignored -> {
            selected = ScheduledMessageTime.combine(
                    ScheduledMessageTime.pickerDayMillis(selected, zone),
                    picker.getHour(),
                    picker.getMinute(),
                    zone);
            renderSelection();
        });
        picker.show(getChildFragmentManager(), "schedule_time");
    }

    private void submit() {
        if (binding == null || viewModel == null) {
            return;
        }
        if (editId == null) {
            viewModel.schedule(draftBody, selected);
        } else {
            CharSequence edited = binding.scheduleSendBodyEdit.getText();
            viewModel.edit(editId, edited == null ? "" : edited.toString(), selected);
        }
    }
}
