package com.nexachat.feature.inbox;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.LinearLayoutManager;
import com.nexachat.R;
import com.nexachat.data.ConversationSummary;
import com.nexachat.databinding.FragmentInboxBinding;
import com.nexachat.feature.inbox.adapter.ConversationListAdapter;
import dagger.hilt.android.AndroidEntryPoint;
import java.util.ArrayList;
import java.util.List;

/** XML inbox host. Conversation rows are sourced from the ViewModel's Room-backed state. */
@AndroidEntryPoint
public final class InboxFragment extends Fragment {
    private FragmentInboxBinding binding;
    private InboxViewModel viewModel;
    private ConversationListAdapter adapter;
    private String query = "";

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
        binding = FragmentInboxBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        viewModel = new ViewModelProvider(this).get(InboxViewModel.class);
        adapter = new ConversationListAdapter(id -> {
            Bundle args = new Bundle(); args.putString("conversationId", id);
            Navigation.findNavController(view).navigate(R.id.chatFragment, args);
        }, null, getString(R.string.time_just_now), getString(R.string.time_yesterday));
        binding.conversationList.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.conversationList.setAdapter(adapter);
        binding.swipeRefresh.setOnRefreshListener(viewModel::refresh);
        binding.contactsButton.setOnClickListener(v -> Navigation.findNavController(v).navigate(R.id.contactsFragment));
        binding.profileButton.setOnClickListener(v -> Navigation.findNavController(v).navigate(R.id.profileFragment));
        binding.createGroupFab.setOnClickListener(v -> Navigation.findNavController(v).navigate(R.id.groupCreateFragment));
        render(viewModel.getUiState().getValue());
        viewModel.refresh();
    }

    private void render(InboxUiState state) {
        if (binding == null || state == null) return;
        List<ConversationSummary> rows = new ArrayList<>(state.getConversations());
        adapter.submitList(rows);
        binding.swipeRefresh.setRefreshing(state.isLoading());
    }

    @Override public void onDestroyView() { super.onDestroyView(); binding = null; }
}
