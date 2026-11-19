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
import com.nexachat.databinding.FragmentStarredMessagesBinding;
import dagger.hilt.android.AndroidEntryPoint;

/**
 * Caller-wide starred messages. Tapping a row opens its conversation anchored
 * on the message, reusing the chat screen's archive-jump argument.
 */
@AndroidEntryPoint
public final class StarredMessagesFragment extends Fragment {

    private FragmentStarredMessagesBinding binding;
    private StarredMessagesViewModel viewModel;
    private StarredMessageAdapter adapter;

    public StarredMessagesFragment() {
        super(R.layout.fragment_starred_messages);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        binding = FragmentStarredMessagesBinding.bind(view);
        viewModel = new ViewModelProvider(this).get(StarredMessagesViewModel.class);
        binding.starredToolbar.setNavigationOnClickListener(
                ignored -> NavHostFragment.findNavController(this).navigateUp());
        adapter = new StarredMessageAdapter(row -> {
            String conversationId = row.message().getConversationId();
            String messageId = row.message().getId();
            if (conversationId == null || messageId == null || binding == null) {
                return;
            }
            Bundle args = new Bundle();
            args.putString("conversationId", conversationId);
            args.putString("archiveJumpMessageId", messageId);
            try {
                NavHostFragment.findNavController(this).navigate(R.id.chatFragment, args);
            } catch (RuntimeException ignored) {
                if (binding != null) {
                    Snackbar.make(binding.getRoot(), R.string.error_generic,
                            Snackbar.LENGTH_SHORT).show();
                }
            }
        });
        binding.starredList.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.starredList.setAdapter(adapter);
        binding.starredList.addOnScrollListener(new RecyclerView.OnScrollListener() {
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
        binding.starredError.setOnRetry(() -> viewModel.retry());
        viewModel.getUiState().observe(getViewLifecycleOwner(), state -> {
            adapter.submit(state.getItems());
            binding.starredProgress.setVisibility(
                    state.isLoading() ? View.VISIBLE : View.GONE);
            // A load failure gets the full error surface with retry; the empty
            // label must not claim "no starred messages" for a failed fetch.
            boolean showError = state.isError() && state.getItems().isEmpty();
            binding.starredError.setVisibility(showError ? View.VISIBLE : View.GONE);
            if (showError) {
                binding.starredError.setMessage(getString(R.string.safety_error_generic));
            }
            binding.starredEmpty.setVisibility(
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
