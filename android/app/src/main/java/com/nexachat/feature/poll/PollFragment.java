package com.nexachat.feature.poll;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.nexachat.R;
import com.nexachat.databinding.FragmentPollBinding;
import dagger.hilt.android.AndroidEntryPoint;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Poll tab of the shared conversation board. */
@AndroidEntryPoint
public final class PollFragment extends Fragment {

    private static final String CREATE_TAG = "poll_create";
    private static final long CHANGE_REFRESH_MS = 15_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refreshTask = new Runnable() {
        @Override
        public void run() {
            if (viewModel != null) {
                viewModel.refreshChanges();
            }
            handler.postDelayed(this, CHANGE_REFRESH_MS);
        }
    };

    private FragmentPollBinding binding;
    private PollViewModel viewModel;
    private PollAdapter adapter;
    @Nullable private PollRepository.Failure lastRefreshSnackbarError;

    public PollFragment() {
        super(R.layout.fragment_poll);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        binding = FragmentPollBinding.bind(view);
        viewModel = new ViewModelProvider(this).get(PollViewModel.class);
        adapter = new PollAdapter(new PollAdapter.Listener() {
            @Override
            public void onToggle(@NonNull String pollId, @NonNull String optionId) {
                viewModel.toggleOption(pollId, optionId);
            }

            @Override
            public void onVote(@NonNull String pollId) {
                viewModel.submitVote(pollId);
            }

            @Override
            public void onRetract(@NonNull String pollId) {
                viewModel.retractVote(pollId);
            }

            @Override
            public void onManage(@NonNull Poll poll, @NonNull View anchor) {
                showManage(poll, anchor);
            }
        });
        binding.pollList.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.pollList.setAdapter(adapter);
        binding.pollEmpty.setIcon(R.drawable.ic_format_list_bulleted);
        binding.pollEmpty.setMessage(getString(R.string.poll_empty));
        binding.pollLoading.setLabel(getString(R.string.poll_loading));
        binding.pollAdd.setOnClickListener(ignored -> openCreateSheet());
        binding.pollList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (dy > 0 && !recyclerView.canScrollVertically(1)) {
                    viewModel.loadMore();
                }
            }
        });
        viewModel.getUiState().observe(getViewLifecycleOwner(), this::render);
        viewModel.getEvents().observe(getViewLifecycleOwner(), event -> {
            if (event == null) {
                return;
            }
            PollActionError error = event.consume();
            if (error != null) {
                Snackbar.make(binding.getRoot(), PollText.actionError(error),
                        Snackbar.LENGTH_LONG).show();
            }
        });
        viewModel.load();
    }

    @Override
    public void onResume() {
        super.onResume();
        handler.removeCallbacks(refreshTask);
        handler.postDelayed(refreshTask, CHANGE_REFRESH_MS);
        if (viewModel != null) {
            viewModel.refresh();
        }
    }

    @Override
    public void onPause() {
        handler.removeCallbacks(refreshTask);
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        handler.removeCallbacks(refreshTask);
        binding = null;
        adapter = null;
        super.onDestroyView();
    }

    private void render(@NonNull PollUiState state) {
        if (binding == null) {
            return;
        }
        List<PollAdapter.Row> rows = new ArrayList<>();
        java.time.Instant now = java.time.Instant.now();
        for (Poll poll : state.getPolls()) {
            rows.add(new PollAdapter.Row(
                    poll,
                    state.selection(poll.getId()),
                    state.getVoterNames(),
                    state.canManage(poll),
                    state.isVoting(poll.getId()),
                    poll.isClosed(now)));
        }
        adapter.submitList(rows);
        boolean hasRows = !rows.isEmpty();
        PollRepository.Failure loadError = state.getLoadError();
        boolean blocked = BoardListRenderRules.isBlocked(loadError);
        boolean showLoading = state.isLoading() && !hasRows;
        boolean showError = BoardListRenderRules.showError(loadError, hasRows);
        boolean showEmpty = BoardListRenderRules.showEmpty(state.isEmpty());
        binding.pollList.setVisibility(hasRows ? View.VISIBLE : View.GONE);
        binding.pollLoading.setVisibility(showLoading ? View.VISIBLE : View.GONE);
        binding.pollError.setVisibility(showError ? View.VISIBLE : View.GONE);
        binding.pollEmpty.setVisibility(showEmpty ? View.VISIBLE : View.GONE);
        binding.pollNextPageProgress.setVisibility(
                state.isLoadingMore() ? View.VISIBLE : View.GONE);
        binding.pollAdd.setVisibility(
                viewModel.isGroup() && !blocked ? View.VISIBLE : View.GONE);
        if (showError) {
            binding.pollError.setMessage(getString(PollText.loadError(loadError)));
            binding.pollError.setOnRetry(BoardListRenderRules.offersRetry(loadError)
                    ? () -> viewModel.retry()
                    : null);
        } else {
            binding.pollError.setOnRetry(null);
        }
        if (showEmpty) {
            binding.pollEmpty.setMessage(getString(R.string.poll_empty));
        }

        PollRepository.Failure refreshError = state.getRefreshError();
        if (refreshError != null && hasRows && !blocked
                && !Objects.equals(refreshError, lastRefreshSnackbarError)) {
            lastRefreshSnackbarError = refreshError;
            Snackbar.make(binding.getRoot(), PollText.loadError(refreshError),
                            Snackbar.LENGTH_LONG)
                    .setAction(R.string.action_retry, ignored -> viewModel.retryRefresh())
                    .show();
        } else if (refreshError == null) {
            lastRefreshSnackbarError = null;
        }
    }

    private void openCreateSheet() {
        if (getChildFragmentManager().isStateSaved()
                || getChildFragmentManager().findFragmentByTag(CREATE_TAG) != null) {
            return;
        }
        new PollCreateSheet().show(getChildFragmentManager(), CREATE_TAG);
    }

    private void showManage(@NonNull Poll poll, @NonNull View anchor) {
        androidx.appcompat.widget.PopupMenu menu =
                new androidx.appcompat.widget.PopupMenu(requireContext(), anchor);
        boolean open = !poll.isClosed(java.time.Instant.now());
        if (open) {
            menu.getMenu().add(0, 1, 0, R.string.poll_close);
        }
        menu.getMenu().add(0, 2, 1, R.string.poll_delete);
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                confirmClose(poll);
                return true;
            }
            if (item.getItemId() == 2) {
                confirmDelete(poll);
                return true;
            }
            return false;
        });
        menu.show();
    }

    private void confirmClose(@NonNull Poll poll) {
        new MaterialAlertDialogBuilder(requireContext())
                .setMessage(R.string.poll_close_confirm)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.poll_close, (dialog, which) -> {
                    if (viewModel != null) {
                        viewModel.closePoll(poll.getId());
                    }
                })
                .show();
    }

    private void confirmDelete(@NonNull Poll poll) {
        new MaterialAlertDialogBuilder(requireContext())
                .setMessage(R.string.poll_delete_confirm)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_delete, (dialog, which) -> {
                    if (viewModel != null) {
                        viewModel.deletePoll(poll.getId());
                    }
                })
                .show();
    }

    /** Empty, error, and dead-end access failures for the poll board list. */
    static final class BoardListRenderRules {
        private BoardListRenderRules() {
        }

        static boolean isBlocked(@Nullable PollRepository.Failure loadError) {
            return loadError == PollRepository.Failure.NOT_A_GROUP
                    || loadError == PollRepository.Failure.NOT_MEMBER;
        }

        static boolean showError(@Nullable PollRepository.Failure loadError, boolean hasRows) {
            return loadError != null && !hasRows;
        }

        static boolean showEmpty(boolean stateIsEmpty) {
            return stateIsEmpty;
        }

        static boolean offersRetry(@Nullable PollRepository.Failure loadError) {
            return loadError != null && !isBlocked(loadError);
        }
    }
}
