package com.nexachat.feature.contacts;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.LinearLayoutManager;
import com.nexachat.R;
import com.nexachat.core.network.dto.UserDto;
import com.nexachat.databinding.FragmentUserSearchBinding;
import com.nexachat.feature.contacts.adapter.UserSearchAdapter;
import dagger.hilt.android.AndroidEntryPoint;
import java.util.List;

/** Performs explicit phone or name-prefix user search. */
@AndroidEntryPoint
public final class UserSearchFragment extends Fragment {
    private FragmentUserSearchBinding binding;
    private ContactsViewModel viewModel;
    private UserSearchAdapter adapter;
    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) { binding = FragmentUserSearchBinding.inflate(inflater, container, false); return binding.getRoot(); }
    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        viewModel = new ViewModelProvider(this).get(ContactsViewModel.class);
        adapter = new UserSearchAdapter(id -> { for (UserDto user : viewModel.getUiState().getValue().getSearchResults()) if (user.getId().equals(id)) { viewModel.openUserDetail(user); showUserActions(user); break; } });
        binding.resultsList.setLayoutManager(new LinearLayoutManager(requireContext())); binding.resultsList.setAdapter(adapter);
        binding.toolbar.setNavigationOnClickListener(v -> Navigation.findNavController(v).navigateUp());
        binding.searchInput.setText(viewModel.getUiState().getValue().getQuery());
        binding.searchInput.setOnEditorActionListener((v, action, event) -> { if (action == EditorInfo.IME_ACTION_SEARCH) { submit(); return true; } return false; });
        binding.searchInput.addTextChangedListener(new android.text.TextWatcher() { public void beforeTextChanged(CharSequence s,int st,int c,int a){} public void onTextChanged(CharSequence s,int st,int before,int count){ viewModel.onQueryChange(s.toString()); } public void afterTextChanged(android.text.Editable e){} });
        render();
    }
    private void submit() { viewModel.search(); render(); }
    private void render() { if (binding == null) return; ContactsViewModel.UiState state = viewModel.getUiState().getValue(); List<UserDto> users = state.getSearchResults(); adapter.submitList(users); binding.loadingState.setVisibility(state.isLoading() ? View.VISIBLE : View.GONE); }
    private void showUserActions(UserDto user) { new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext()).setTitle(user.getDisplayName()).setItems(new CharSequence[]{getString(R.string.action_message), getString(R.string.action_add_contact)}, (d, which) -> { if (which == 0) viewModel.messageSelected(); else viewModel.addDetailContact(); }).show(); }
    @Override public void onDestroyView() { super.onDestroyView(); binding = null; }
}
