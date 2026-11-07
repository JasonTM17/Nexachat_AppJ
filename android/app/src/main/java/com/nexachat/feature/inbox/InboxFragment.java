package com.nexachat.feature.inbox;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import com.nexachat.R;
import com.nexachat.core.common.ApiError;
import com.nexachat.data.ConversationSummary;
import com.nexachat.databinding.FragmentInboxBinding;
import com.nexachat.feature.chat.ChatErrorIds;
import com.nexachat.feature.inbox.adapter.ConversationListAdapter;
import dagger.hilt.android.AndroidEntryPoint;
import java.util.List;
import java.util.Objects;

/** Inbox conversation list backed by {@link InboxViewModel}. */
@AndroidEntryPoint
public final class InboxFragment extends Fragment {

    private FragmentInboxBinding binding;
    private InboxViewModel viewModel;
    private ConversationListAdapter adapter;
    @Nullable private String boundUserId;

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle state) {
        binding = FragmentInboxBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        viewModel = new ViewModelProvider(this).get(InboxViewModel.class);
        binding.toolbar.setTitle(R.string.inbox_title);
        binding.conversationList.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.swipeRefresh.setOnRefreshListener(viewModel::refresh);
        binding.contactsButton.setOnClickListener(
                v -> NavHostFragment.findNavController(this).navigate(R.id.contactsFragment));
        binding.searchButton.setOnClickListener(
                v -> NavHostFragment.findNavController(this).navigate(R.id.searchFragment));
        binding.profileButton.setOnClickListener(
                v -> NavHostFragment.findNavController(this).navigate(R.id.profileFragment));
        binding.createGroupFab.setOnClickListener(
                v -> NavHostFragment.findNavController(this).navigate(R.id.groupCreateFragment));
        binding.errorState.setOnRetry(viewModel::refresh);
        binding.emptyState.setMessage(getString(R.string.inbox_empty));
        binding.emptyState.setIcon(R.drawable.ic_empty_chat);
        viewModel.getUiState()
                .observe(getViewLifecycleOwner(), this::render);
    }

    private void ensureAdapter(@Nullable String currentUserId) {
        if (adapter != null && Objects.equals(boundUserId, currentUserId)) {
            return;
        }
        boundUserId = currentUserId;
        adapter = new ConversationListAdapter(
                conversationId -> {
                    Bundle args = new Bundle();
                    args.putString("conversationId", conversationId);
                    NavHostFragment.findNavController(this).navigate(R.id.chatFragment, args);
                },
                currentUserId,
                getString(R.string.time_just_now),
                getString(R.string.time_yesterday));
        binding.conversationList.setAdapter(adapter);
    }

    private void render(@NonNull InboxUiState state) {
        if (binding == null) {
            return;
        }
        ensureAdapter(state.getCurrentUserId());
        List<ConversationSummary> rows = state.getConversations();
        adapter.submitList(rows);
        binding.swipeRefresh.setRefreshing(state.isLoading() && !rows.isEmpty());

        boolean showLoading = state.isLoading() && rows.isEmpty();
        ApiError error = state.getError();
        boolean showError = error != null && rows.isEmpty();
        boolean showEmpty = !showLoading && !showError && rows.isEmpty();
        boolean showList = !rows.isEmpty();

        binding.loadingState.setVisibility(showLoading ? View.VISIBLE : View.GONE);
        binding.conversationList.setVisibility(showList ? View.VISIBLE : View.GONE);
        binding.swipeRefresh.setVisibility(showList ? View.VISIBLE : View.GONE);
        binding.emptyState.setVisibility(showEmpty ? View.VISIBLE : View.GONE);
        binding.errorState.setVisibility(showError ? View.VISIBLE : View.GONE);
        if (showError && error != null) {
            binding.errorState.setMessage(getString(ChatErrorIds.messageId(error)));
        }
    }

    @Override
    public void onDestroyView() {
        if (binding != null) {
            binding.conversationList.setAdapter(null);
        }
        adapter = null;
        boundUserId = null;
        super.onDestroyView();
        binding = null;
    }
}
