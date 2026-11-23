package com.nexachat.feature.scheduled;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.PopupMenu;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.nexachat.R;
import com.nexachat.databinding.SheetScheduledMessagesBinding;
import com.nexachat.feature.ui.FormDialogs;
import com.nexachat.feature.ui.NexaBottomSheetFragment;
import java.util.Objects;

/**
 * Bottom sheet listing the caller's scheduled rows for the open conversation.
 * Rows can be cancelled while still PENDING; a {@code 409 DISPATCHING} answer
 * surfaces a localized notice and re-syncs the list.
 */
public final class ScheduledMessageListSheet extends NexaBottomSheetFragment {

    private SheetScheduledMessagesBinding binding;
    private ScheduledMessagesViewModel viewModel;
    private ScheduledMessageAdapter adapter;
    @Nullable private ScheduledMessageRepository.Failure lastRefreshSnackbarError;

    @NonNull
    public static ScheduledMessageListSheet newInstance() {
        return new ScheduledMessageListSheet();
    }

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        binding = SheetScheduledMessagesBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(requireParentFragment())
                .get(ScheduledMessagesViewModel.class);
        adapter = new ScheduledMessageAdapter(this::showMenu);
        binding.scheduledList.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.scheduledList.setAdapter(adapter);
        binding.scheduledListClose.setOnClickListener(ignored -> dismiss());
        binding.scheduledEmpty.setIcon(R.drawable.ic_hourglass_top);
        binding.scheduledEmpty.setTitle(getString(R.string.scheduled_empty));
        binding.scheduledEmpty.setMessage(getString(R.string.scheduled_empty_hint));
        binding.scheduledLoading.setLabel(getString(R.string.scheduled_loading));
        applySheetInsets(binding.getRoot());
        viewModel.getUiState().observe(getViewLifecycleOwner(), this::render);
        viewModel.getEvents().observe(getViewLifecycleOwner(), event -> {
            if (event == null) {
                return;
            }
            ScheduledMessageEditorError error = event.consume();
            if (error != null) {
                showSnackbar(ScheduledMessageText.editorError(error), null);
            }
        });
        viewModel.load();
    }

    @Override
    public void onDestroyView() {
        binding = null;
        adapter = null;
        super.onDestroyView();
    }

    private void render(@NonNull ScheduledMessageUiState state) {
        if (binding == null || adapter == null) {
            return;
        }
        adapter.submitList(state.getMessages());
        boolean hasRows = !state.getMessages().isEmpty();
        ScheduledMessageRepository.Failure loadError = state.getLoadError();
        boolean showLoading = state.isLoading() && !hasRows;
        boolean showError = loadError != null && !hasRows;
        boolean showEmpty = state.isEmpty();
        binding.scheduledList.setVisibility(hasRows ? View.VISIBLE : View.GONE);
        binding.scheduledLoading.setVisibility(showLoading ? View.VISIBLE : View.GONE);
        binding.scheduledError.setVisibility(showError ? View.VISIBLE : View.GONE);
        binding.scheduledEmpty.setVisibility(showEmpty ? View.VISIBLE : View.GONE);
        if (showError) {
            binding.scheduledError.setMessage(
                    getString(ScheduledMessageText.loadError(loadError)));
            binding.scheduledError.setOnRetry(
                    offersRetry(loadError) ? () -> viewModel.retry() : null);
        } else {
            binding.scheduledError.setOnRetry(null);
        }

        ScheduledMessageRepository.Failure refreshError = state.getRefreshError();
        if (refreshError != null && hasRows
                && !Objects.equals(refreshError, lastRefreshSnackbarError)) {
            lastRefreshSnackbarError = refreshError;
            showSnackbar(ScheduledMessageText.loadError(refreshError),
                    ignored -> viewModel.retryRefresh());
        } else if (refreshError == null) {
            lastRefreshSnackbarError = null;
        }
    }

    private static boolean offersRetry(@Nullable ScheduledMessageRepository.Failure failure) {
        return failure != null
                && failure != ScheduledMessageRepository.Failure.NOT_MEMBER
                && failure != ScheduledMessageRepository.Failure.FORBIDDEN
                && failure != ScheduledMessageRepository.Failure.NOT_FOUND
                && failure != ScheduledMessageRepository.Failure.E2EE;
    }

    private void showSnackbar(int message, @Nullable View.OnClickListener action) {
        if (binding == null) {
            return;
        }
        Snackbar bar = Snackbar.make(binding.getRoot(), message, Snackbar.LENGTH_LONG);
        if (action != null) {
            bar.setAction(R.string.action_retry, action);
        }
        bar.show();
    }

    private void showMenu(@NonNull ScheduledMessage message, @NonNull View anchor) {
        if (binding == null || viewModel == null) {
            return;
        }
        PopupMenu menu = new PopupMenu(requireContext(), anchor);
        menu.getMenu().add(0, 1, 0, R.string.scheduled_action_cancel);
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                confirmCancel(message);
                return true;
            }
            return false;
        });
        menu.show();
    }

    private void confirmCancel(@NonNull ScheduledMessage message) {
        FormDialogs.markDestructive(new MaterialAlertDialogBuilder(requireContext())
                .setMessage(R.string.scheduled_cancel_confirm)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.scheduled_action_cancel, (dialog, which) -> {
                    if (viewModel != null) {
                        viewModel.cancelScheduled(message.getId());
                    }
                })
                .show());
    }
}
