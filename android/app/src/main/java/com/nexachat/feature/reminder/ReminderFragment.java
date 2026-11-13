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
import java.util.Objects;

/** Reminder tab of the shared conversation board. */
@AndroidEntryPoint
public final class ReminderFragment extends Fragment {

    private static final String EDITOR_TAG = "reminder_editor";

    private FragmentReminderBinding binding;
    private ReminderViewModel viewModel;
    private ReminderAdapter adapter;
    @Nullable private ReminderRepository.Failure lastRefreshSnackbarError;

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
                showSnackbar(ReminderText.editorError(error), null);
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
        ReminderRepository.Failure loadError = state.getLoadError();
        boolean blocked = BoardListRenderRules.isBlocked(loadError);
        boolean showLoading = state.isLoading() && !hasRows;
        boolean showError = BoardListRenderRules.showError(loadError, hasRows);
        boolean showEmpty = BoardListRenderRules.showEmpty(state.isEmpty());
        binding.reminderList.setVisibility(hasRows ? View.VISIBLE : View.GONE);
        binding.reminderLoading.setVisibility(showLoading ? View.VISIBLE : View.GONE);
        binding.reminderError.setVisibility(showError ? View.VISIBLE : View.GONE);
        binding.reminderEmpty.setVisibility(showEmpty ? View.VISIBLE : View.GONE);
        if (showError) {
            binding.reminderError.setMessage(getString(ReminderText.loadError(loadError)));
            binding.reminderError.setOnRetry(BoardListRenderRules.offersRetry(loadError)
                    ? () -> viewModel.retry()
                    : null);
        } else {
            binding.reminderError.setOnRetry(null);
        }
        if (showEmpty) {
            binding.reminderEmpty.setMessage(getString(R.string.reminder_empty));
        }

        ReminderRepository.Failure refreshError = state.getRefreshError();
        if (refreshError != null && hasRows && !blocked
                && !Objects.equals(refreshError, lastRefreshSnackbarError)) {
            lastRefreshSnackbarError = refreshError;
            showSnackbar(ReminderText.loadError(refreshError),
                    ignored -> viewModel.retryRefresh());
        } else if (refreshError == null) {
            lastRefreshSnackbarError = null;
        }
    }

    private void showSnackbar(int message, @Nullable View.OnClickListener action) {
        if (binding == null) {
            return;
        }
        Snackbar bar = Snackbar.make(binding.getRoot(), message, Snackbar.LENGTH_LONG);
        if (binding.reminderAdd.getVisibility() == View.VISIBLE) {
            bar.setAnchorView(binding.reminderAdd);
        }
        if (action != null) {
            bar.setAction(R.string.action_retry, action);
        }
        bar.show();
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

    /** Empty, error, and dead-end access failures for the reminder board list. */
    static final class BoardListRenderRules {
        private BoardListRenderRules() {
        }

        static boolean isBlocked(@Nullable ReminderRepository.Failure loadError) {
            return loadError == ReminderRepository.Failure.NOT_MEMBER
                    || loadError == ReminderRepository.Failure.FORBIDDEN
                    || loadError == ReminderRepository.Failure.NOT_ADMIN
                    || loadError == ReminderRepository.Failure.NOT_FOUND;
        }

        static boolean showError(@Nullable ReminderRepository.Failure loadError, boolean hasRows) {
            return loadError != null && !hasRows;
        }

        static boolean showEmpty(boolean stateIsEmpty) {
            return stateIsEmpty;
        }

        static boolean offersRetry(@Nullable ReminderRepository.Failure loadError) {
            return loadError != null && !isBlocked(loadError);
        }
    }
}
