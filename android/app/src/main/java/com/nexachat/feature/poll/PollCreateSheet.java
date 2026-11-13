package com.nexachat.feature.poll;

import android.app.Dialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModelProvider;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.datepicker.CalendarConstraints;
import com.google.android.material.datepicker.MaterialDatePicker;
import com.nexachat.R;
import com.nexachat.databinding.ItemPollOptionInputBinding;
import com.nexachat.feature.ui.ImeInsets;
import com.nexachat.databinding.SheetCreatePollBinding;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/** Create sheet for a group poll; validation mirrors the server bounds. */
public final class PollCreateSheet extends BottomSheetDialogFragment {

    private static final int MAX_OPTIONS = 10;
    private static final int MIN_OPTIONS = 2;
    private static final String STATE_QUESTION = "poll.question";
    private static final String STATE_OPTIONS = "poll.options";
    private static final String STATE_MULTIPLE = "poll.multiple";
    private static final String STATE_ANONYMOUS = "poll.anonymous";
    private static final String STATE_EXPIRES = "poll.expires";

    private SheetCreatePollBinding binding;
    private PollViewModel viewModel;
    private final List<OptionRow> optionRows = new ArrayList<>();
    @Nullable private Instant closesAt;
    private boolean dismissed;

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        BottomSheetDialog dialog = (BottomSheetDialog) super.onCreateDialog(savedInstanceState);
        dialog.setOnShowListener(ignored -> {
            dialog.getBehavior().setSkipCollapsed(true);
            dialog.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
        });
        return dialog;
    }

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        binding = SheetCreatePollBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(requireParentFragment()).get(PollViewModel.class);
        viewModel.clearCreateState();
        if (savedInstanceState == null) {
            addOptionRow();
            addOptionRow();
        } else {
            restoreForm(savedInstanceState);
        }
        binding.pollCreateClose.setOnClickListener(ignored -> dismiss());
        binding.pollCreateCancel.setOnClickListener(ignored -> dismiss());
        binding.pollAddOption.setOnClickListener(ignored -> addOptionRow());
        binding.pollExpiry.setOnClickListener(ignored -> pickExpiry());
        binding.pollExpiryClear.setOnClickListener(ignored -> {
            closesAt = null;
            renderExpiry();
        });
        binding.pollCreateSubmit.setOnClickListener(ignored -> submit());
        renderExpiry();
        ImeInsets.applyBottomPadding(binding.getRoot());
        viewModel.getUiState().observe(getViewLifecycleOwner(), this::render);
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (binding == null) {
            return;
        }
        outState.putString(STATE_QUESTION, binding.pollQuestionInput.getText() == null
                ? "" : binding.pollQuestionInput.getText().toString());
        ArrayList<String> options = new ArrayList<>();
        for (OptionRow row : optionRows) {
            options.add(row.binding.optionInput.getText() == null
                    ? "" : row.binding.optionInput.getText().toString());
        }
        outState.putStringArrayList(STATE_OPTIONS, options);
        outState.putBoolean(STATE_MULTIPLE, binding.pollMultipleChoice.isChecked());
        outState.putBoolean(STATE_ANONYMOUS, binding.pollAnonymous.isChecked());
        outState.putLong(STATE_EXPIRES, closesAt == null ? 0L : closesAt.toEpochMilli());
    }

    @Override
    public void onDestroyView() {
        binding = null;
        optionRows.clear();
        super.onDestroyView();
    }

    @Override
    public void onDismiss(@NonNull android.content.DialogInterface dialog) {
        if (viewModel != null) {
            viewModel.clearCreateState();
        }
        super.onDismiss(dialog);
    }

    private void restoreForm(@NonNull Bundle state) {
        ArrayList<String> options = state.getStringArrayList(STATE_OPTIONS);
        if (options == null || options.isEmpty()) {
            addOptionRow();
            addOptionRow();
        } else {
            for (String option : options) {
                addOptionRow();
                optionRows.get(optionRows.size() - 1).binding.optionInput.setText(option);
            }
            while (optionRows.size() < MIN_OPTIONS) {
                addOptionRow();
            }
        }
        binding.pollQuestionInput.setText(state.getString(STATE_QUESTION, ""));
        binding.pollMultipleChoice.setChecked(state.getBoolean(STATE_MULTIPLE, false));
        binding.pollAnonymous.setChecked(state.getBoolean(STATE_ANONYMOUS, false));
        long expires = state.getLong(STATE_EXPIRES, 0L);
        closesAt = expires == 0L ? null : Instant.ofEpochMilli(expires);
        renderExpiry();
    }

    private void addOptionRow() {
        if (binding == null || optionRows.size() >= MAX_OPTIONS) {
            return;
        }
        ItemPollOptionInputBinding row = ItemPollOptionInputBinding.inflate(
                LayoutInflater.from(requireContext()), binding.pollOptionInputs, true);
        OptionRow option = new OptionRow(row);
        optionRows.add(option);
        row.optionRemove.setOnClickListener(ignored -> removeOptionRow(option));
        updateOptionRows();
    }

    private void removeOptionRow(@NonNull OptionRow option) {
        if (optionRows.size() <= MIN_OPTIONS || binding == null) {
            return;
        }
        binding.pollOptionInputs.removeView(option.binding.getRoot());
        optionRows.remove(option);
        updateOptionRows();
    }

    private void updateOptionRows() {
        for (int index = 0; index < optionRows.size(); index++) {
            OptionRow row = optionRows.get(index);
            row.binding.optionLayout.setHint(
                    getString(R.string.poll_option_hint, index + 1));
            row.binding.optionRemove.setVisibility(
                    optionRows.size() > MIN_OPTIONS ? View.VISIBLE : View.INVISIBLE);
        }
        binding.pollAddOption.setEnabled(optionRows.size() < MAX_OPTIONS);
    }

    private void pickExpiry() {
        ZoneId zone = ZoneId.systemDefault();
        Instant now = Instant.now();
        long today = PollTime.pickerDayMillis(now, zone);
        CalendarConstraints constraints = new CalendarConstraints.Builder()
                .setStart(today)
                .setEnd(PollTime.maxExpiryDayMillis(now, zone, PollDraft.MAX_EXPIRY_DAYS))
                .build();
        MaterialDatePicker.Builder<Long> builder = MaterialDatePicker.Builder.datePicker()
                .setTitleText(R.string.poll_expiry_picker)
                .setCalendarConstraints(constraints);
        if (closesAt != null) {
            builder.setSelection(PollTime.pickerDayMillis(closesAt, zone));
        }
        MaterialDatePicker<Long> picker = builder.build();
        picker.addOnPositiveButtonClickListener(day -> {
            closesAt = PollTime.endOfDay(day, zone);
            renderExpiry();
        });
        picker.show(getChildFragmentManager(), "poll_expiry");
    }

    private void renderExpiry() {
        if (binding == null) {
            return;
        }
        binding.pollExpiry.setText(closesAt == null
                ? getString(R.string.poll_expiry_add)
                : getString(R.string.poll_expiry_value, PollText.date(requireContext(), closesAt)));
        binding.pollExpiryClear.setVisibility(closesAt == null ? View.GONE : View.VISIBLE);
    }

    private void render(@NonNull PollUiState state) {
        if (binding == null) {
            return;
        }
        binding.pollCreateSubmit.setEnabled(!state.isCreating());
        PollRepository.Failure createError = state.getCreateError();
        if (createError == null) {
            binding.pollCreateError.setVisibility(View.GONE);
        } else {
            binding.pollCreateError.setText(PollText.createError(createError));
            binding.pollCreateError.setVisibility(View.VISIBLE);
        }
        if (state.getCreatedPollId() != null && !dismissed) {
            dismissed = true;
            viewModel.consumeCreatedPoll();
            dismissAllowingStateLoss();
        }
    }

    private void submit() {
        if (binding == null || viewModel == null) {
            return;
        }
        EditText questionInput = binding.pollQuestionInput;
        List<String> options = new ArrayList<>();
        for (OptionRow row : optionRows) {
            EditText input = row.binding.optionInput;
            options.add(input.getText() == null ? "" : input.getText().toString());
        }
        PollDraft draft = new PollDraft(
                questionInput.getText() == null ? "" : questionInput.getText().toString(),
                options,
                binding.pollMultipleChoice.isChecked(),
                binding.pollAnonymous.isChecked(),
                closesAt);
        PollValidation validation = draft.validate(Instant.now());
        if (validation != PollValidation.OK) {
            binding.pollCreateError.setText(PollText.validation(validation));
            binding.pollCreateError.setVisibility(View.VISIBLE);
            if (validation == PollValidation.QUESTION_REQUIRED
                    || validation == PollValidation.QUESTION_TOO_LONG) {
                questionInput.requestFocus();
            }
            return;
        }
        binding.pollCreateError.setVisibility(View.GONE);
        viewModel.createPoll(draft);
    }

    private static final class OptionRow {
        private final ItemPollOptionInputBinding binding;

        OptionRow(@NonNull ItemPollOptionInputBinding binding) {
            this.binding = binding;
        }
    }
}
