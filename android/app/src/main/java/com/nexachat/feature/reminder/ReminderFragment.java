package com.nexachat.feature.reminder;

import android.os.Bundle;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.nexachat.R;
import com.nexachat.databinding.FragmentReminderBinding;
import dagger.hilt.android.AndroidEntryPoint;

/** Reminder tab of the shared conversation board. */
@AndroidEntryPoint
public final class ReminderFragment extends Fragment {

    private static final String EDITOR_TAG = "reminder_editor";

    private FragmentReminderBinding binding;
    private ReminderViewModel viewModel;
    private ReminderAdapter adapter;

    public ReminderFragment() {
        super(R.layout.fragment_reminder);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        binding = FragmentReminderBinding.bind(view);
        viewModel = new ViewModelProvider(this).get(ReminderViewModel.class);
        adapter = new ReminderAdapter((reminder, anchor) -> showMenu(reminder, anchor));
        binding.reminderList.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.reminderList.setAdapter(adapter);
        binding.reminderEmpty.setIcon(R.drawable.ic_hourglass_top);
        binding.reminderEmpty.setMessage(getString(R.string.reminder_empty));
        binding.reminderLoading.setLabel(getString(R.string.reminder_loading));
        binding.reminderAdd.setOnClickListener(ignored -> openEditor(null));
        viewModel.getUiState().observe(getViewLifecycleOwner(), this::render);
        viewModel.getEvents().observe(getViewLifecycleOwner(), event -> {
            if (event == null) {
                return;
            }
            ReminderEditorError error = event.consume();
            if (error != null) {
                Snackbar.make(binding.getRoot(), ReminderText.editorError(error),
                        Snackbar.LENGTH_LONG).show();
            }
        });
        viewModel.load();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (viewModel != null) {
            viewModel.refresh();
        }
    }

    @Override
    public void onDestroyView() {
        binding = null;
        adapter = null;
        super.onDestroyView();
    }

    private void render(@NonNull ReminderUiState state) {
        if (binding == null) {
            return;
        }
        adapter.submitList(state.getReminders());
        boolean hasRows = !state.getReminders().isEmpty();
        boolean blocked = state.getLoadError() == ReminderRepository.Failure.NOT_MEMBER
                || state.getLoadError() == ReminderRepository.Failure.FORBIDDEN
                || state.getLoadError() == ReminderRepository.Failure.NOT_ADMIN
                || state.getLoadError() == ReminderRepository.Failure.NOT_FOUND;
        boolean showLoading = state.isLoading() && !hasRows;
        boolean showError = state.getLoadError() != null && !hasRows && !blocked;
        boolean showEmpty = state.isEmpty() || (blocked && !hasRows);
        binding.reminderList.setVisibility(hasRows ? View.VISIBLE : View.GONE);
        binding.reminderLoading.setVisibility(showLoading ? View.VISIBLE : View.GONE);
        binding.reminderError.setVisibility(showError ? View.VISIBLE : View.GONE);
        binding.reminderEmpty.setVisibility(showEmpty ? View.VISIBLE : View.GONE);
        if (showError) {
            binding.reminderError.setMessage(
                    getString(ReminderText.loadError(state.getLoadError())));
            binding.reminderError.setOnRetry(() -> viewModel.retry());
        } else {
            binding.reminderError.setOnRetry(null);
        }
        if (showEmpty) {
            binding.reminderEmpty.setMessage(getString(blocked
                    ? ReminderText.loadError(state.getLoadError())
                    : R.string.reminder_empty));
        }
    }

    private void showMenu(@NonNull Reminder reminder, @NonNull View anchor) {
        if (binding == null || viewModel == null) {
            return;
        }
        androidx.appcompat.widget.PopupMenu menu =
                new androidx.appcompat.widget.PopupMenu(requireContext(), anchor);
        menu.getMenu().add(0, 1, 0, R.string.action_edit);
        menu.getMenu().add(0, 2, 1, R.string.action_delete);
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                openEditor(reminder);
                return true;
            }
            if (item.getItemId() == 2) {
                confirmDelete(reminder);
                return true;
            }
            return false;
        });
        menu.show();
    }

    private void confirmDelete(@NonNull Reminder reminder) {
        new MaterialAlertDialogBuilder(requireContext())
                .setMessage(R.string.reminder_delete_confirm)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_delete, (dialog, which) -> {
                    if (viewModel != null) {
                        viewModel.deleteReminder(reminder.getId());
                    }
                })
                .show();
    }

    private void openEditor(@Nullable Reminder reminder) {
        if (getChildFragmentManager().isStateSaved()
                || getChildFragmentManager().findFragmentByTag(EDITOR_TAG) != null) {
            return;
        }
        ReminderEditorSheet sheet = ReminderEditorSheet.newInstance(reminder);
        sheet.show(getChildFragmentManager(), EDITOR_TAG);
    }
}
