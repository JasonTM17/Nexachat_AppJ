package com.nexachat.feature.contacts;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import com.nexachat.R;
import com.nexachat.core.common.ApiError;
import com.nexachat.core.network.dto.UserDto;
import com.nexachat.data.Contact;
import com.nexachat.databinding.FragmentUserSearchBinding;
import com.nexachat.feature.contacts.adapter.UserSearchAdapter;
import dagger.hilt.android.AndroidEntryPoint;
import java.util.ArrayList;
import java.util.List;

/** User discovery by phone or name prefix. */
@AndroidEntryPoint
public final class UserSearchFragment extends Fragment {

    private FragmentUserSearchBinding binding;
    private ContactsViewModel viewModel;
    private UserSearchAdapter adapter;
    private boolean rendering;
    @Nullable private ContactDetailBinder detailBinder;

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle state) {
        binding = FragmentUserSearchBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        viewModel = new ViewModelProvider(this).get(ContactsViewModel.class);
        detailBinder = new ContactDetailBinder(this, viewModel);
        binding.toolbar.setTitle(R.string.search_title);
        adapter = new UserSearchAdapter(userId -> {
            ContactsViewModel.UiState ui = viewModel.getUiState().getValue();
            for (UserDto user : ui.getSearchResults()) {
                if (user.getId().equals(userId)) {
                    viewModel.openUserDetail(user);
                    return;
                }
            }
            for (Contact contact : ui.getContacts()) {
                if (contact.getUserId().equals(userId)) {
                    viewModel.openContactDetail(contact);
                    return;
                }
            }
        });
        binding.resultsList.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.resultsList.setAdapter(adapter);
        binding.toolbar.setNavigationOnClickListener(v -> NavHostFragment.findNavController(this).navigateUp());
        binding.errorState.setOnRetry(viewModel::search);
        binding.emptyState.setMessage(getString(R.string.search_no_results));
        binding.emptyState.setIcon(R.drawable.ic_search_outline);
        binding.searchLayout.setStartIconOnClickListener(v -> viewModel.search());
        binding.searchInput.setOnEditorActionListener((v, action, event) -> {
            if (action == EditorInfo.IME_ACTION_SEARCH || action == EditorInfo.IME_ACTION_DONE) {
                viewModel.search();
                return true;
            }
            return false;
        });
        binding.searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (!rendering) {
                    viewModel.onQueryChange(s.toString());
                }
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        rendering = true;
        binding.searchInput.setText(viewModel.getUiState().getValue().getQuery());
        rendering = false;
        viewModel.getUiState()
                .observe(getViewLifecycleOwner(), this::render);
        viewModel.getEvents()
                .observe(getViewLifecycleOwner(), event -> {
                    if (event instanceof ContactsViewModel.Event.OpenConversation open) {
                        Bundle args = new Bundle();
                        args.putString("conversationId", open.getConversationId());
                        NavHostFragment.findNavController(this).navigate(R.id.chatFragment, args);
                    }
                });
    }

    private void render(@NonNull ContactsViewModel.UiState state) {
        if (binding == null) {
            return;
        }
        rendering = true;
        String query = state.getQuery();
        if (!binding.searchInput.getText().toString().equals(query)) {
            binding.searchInput.setText(query);
        }
        rendering = false;

        List<UserDto> users = state.getSearchResults();
        boolean showingSavedContacts = false;
        if (!state.isSearched() && query.trim().isEmpty() && users.isEmpty() && !state.getContacts().isEmpty()) {
            showingSavedContacts = true;
            List<UserDto> contactUsers = new ArrayList<>(state.getContacts().size());
            for (Contact c : state.getContacts()) {
                contactUsers.add(new UserDto(
                        c.getUserId(),
                        c.getPhone() == null ? "" : c.getPhone(),
                        c.getPrimaryLabel(),
                        c.getAvatarUrl()));
            }
            users = contactUsers;
        }
        adapter.submitList(users);
        binding.sectionHeader.setVisibility(showingSavedContacts ? View.VISIBLE : View.GONE);

        boolean showLoading = state.isLoading();
        ApiError error = state.getError();
        boolean showError = error != null;
        boolean showEmpty = !showLoading && !showError && users.isEmpty();
        boolean showList = !users.isEmpty();

        binding.loadingState.setVisibility(showLoading ? View.VISIBLE : View.GONE);
        binding.resultsList.setVisibility(showList ? View.VISIBLE : View.GONE);
        binding.emptyState.setVisibility(showEmpty ? View.VISIBLE : View.GONE);
        binding.errorState.setVisibility(showError ? View.VISIBLE : View.GONE);
        if (showEmpty) {
            binding.emptyState.setMessage(getString(
                    state.isSearched() ? R.string.search_no_results : R.string.search_label));
        }
        if (showError && error != null) {
            binding.errorState.setMessage(getString(ContactErrorIds.messageId(error)));
        }

        if (detailBinder != null) {
            detailBinder.render(state);
        }
    }

    @Override
    public void onDestroyView() {
        if (detailBinder != null) {
            detailBinder.releaseForViewDestroy();
            detailBinder = null;
        }
        if (binding != null) {
            binding.resultsList.setAdapter(null);
        }
        adapter = null;
        super.onDestroyView();
        binding = null;
    }
}
