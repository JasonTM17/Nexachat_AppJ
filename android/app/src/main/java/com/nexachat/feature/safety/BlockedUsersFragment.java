package com.nexachat.feature.safety;

import android.os.Bundle;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.snackbar.Snackbar;
import com.nexachat.R;
import com.nexachat.databinding.FragmentBlockedUsersBinding;
import dagger.hilt.android.AndroidEntryPoint;

/** The caller's blocked-user list; each row offers inline unblock with a confirmation snackbar. */
@AndroidEntryPoint
public final class BlockedUsersFragment extends Fragment {

    private FragmentBlockedUsersBinding binding;
    private BlockedUsersViewModel viewModel;
    private BlockedUserAdapter adapter;

    public BlockedUsersFragment() {
        super(R.layout.fragment_blocked_users);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        binding = FragmentBlockedUsersBinding.bind(view);
        viewModel = new ViewModelProvider(this).get(BlockedUsersViewModel.class);
        binding.blockedToolbar.setNavigationOnClickListener(
                ignored -> NavHostFragment.findNavController(this).navigateUp());
        adapter = new BlockedUserAdapter(user -> {
            viewModel.unblock(user.userId());
            String label = user.displayName() == null || user.displayName().isBlank()
                    ? "" : user.displayName();
            int message = label.isEmpty()
                    ? R.string.user_unblocked_snackbar
                    : R.string.user_unblocked_with_name;
            Snackbar.make(binding.getRoot(),
                            label.isEmpty()
                                    ? getString(message)
                                    : getString(message, label),
                            Snackbar.LENGTH_SHORT)
                    .show();
        });
        binding.blockedList.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.blockedList.setAdapter(adapter);
        binding.blockedList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                LinearLayoutManager layout =
                        (LinearLayoutManager) recyclerView.getLayoutManager();
                if (layout != null
                        && layout.findLastVisibleItemPosition()
                                >= adapter.getItemCount() - 5) {
                    viewModel.loadMore();
                }
            }
        });
        binding.blockedEmpty.setIcon(R.drawable.ic_block);
        binding.blockedEmpty.setTitle(getString(R.string.blocked_users_empty));
        binding.blockedEmpty.setMessage(getString(R.string.blocked_users_empty_hint));
        binding.blockedError.setOnRetry(() -> viewModel.refresh());
        viewModel.getUiState().observe(getViewLifecycleOwner(), state -> {
            adapter.submit(state.getItems());
            binding.blockedProgress.setVisibility(
                    state.isLoading() ? View.VISIBLE : View.GONE);
            // A failed fetch shows the error surface with retry, not the
            // "no blocked users" empty label.
            boolean showError = state.isError() && state.getItems().isEmpty();
            binding.blockedError.setVisibility(showError ? View.VISIBLE : View.GONE);
            if (showError) {
                binding.blockedError.setMessage(getString(R.string.safety_error_generic));
            }
            binding.blockedEmpty.setVisibility(
                    !state.isLoading() && !state.isError() && state.getItems().isEmpty()
                            ? View.VISIBLE
                            : View.GONE);
            if (state.isError() && !state.getItems().isEmpty() && binding != null) {
                Snackbar.make(binding.getRoot(), R.string.safety_error_generic,
                        Snackbar.LENGTH_SHORT).show();
                viewModel.consumeError();
            }
        });
    }

    @Override
    public void onDestroyView() {
        binding = null;
        super.onDestroyView();
    }
}
