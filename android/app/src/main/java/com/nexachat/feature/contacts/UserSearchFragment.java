package com.nexachat.feature.contacts;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.FlowLiveDataConversions;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.nexachat.R;
import com.nexachat.core.common.ApiError;
import com.nexachat.core.network.dto.UserDto;
import com.nexachat.databinding.FragmentUserSearchBinding;
import com.nexachat.feature.contacts.adapter.UserSearchAdapter;
import dagger.hilt.android.AndroidEntryPoint;
import java.util.List;

/** User discovery by phone or name prefix. */
@AndroidEntryPoint
public final class UserSearchFragment extends Fragment {

    private FragmentUserSearchBinding binding;
    private ContactsViewModel viewModel;
    private UserSearchAdapter adapter;
    private boolean rendering;
    @Nullable private androidx.appcompat.app.AlertDialog detailDialog;

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
        binding.toolbar.setTitle(R.string.search_title);
        adapter = new UserSearchAdapter(userId -> {
            for (UserDto user : viewModel.getUiState().getValue().getSearchResults()) {
                if (user.getId().equals(userId)) {
                    viewModel.openUserDetail(user);
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
        binding.searchInput.setOnEditorActionListener((v, action, event) -> {
            if (action == EditorInfo.IME_ACTION_SEARCH) {
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
        FlowLiveDataConversions.asLiveData(viewModel.getUiState())
                .observe(getViewLifecycleOwner(), this::render);
        FlowLiveDataConversions.asLiveData(viewModel.getEvents())
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
        adapter.submitList(users);

        boolean showLoading = state.isLoading();
        ApiError error = state.getError();
        boolean showError = error != null;
        boolean showEmpty = state.isSearched() && users.isEmpty() && !showLoading && !showError;
        boolean showList = !users.isEmpty();

        binding.loadingState.setVisibility(showLoading ? View.VISIBLE : View.GONE);
        binding.resultsList.setVisibility(showList ? View.VISIBLE : View.GONE);
        binding.emptyState.setVisibility(showEmpty ? View.VISIBLE : View.GONE);
        binding.errorState.setVisibility(showError ? View.VISIBLE : View.GONE);
        if (showError && error != null) {
            binding.errorState.setMessage(getString(ContactErrorIds.messageId(error)));
        }

        if (state.getDetailUser() != null) {
            showOrUpdateDetailDialog(state);
        } else if (detailDialog != null) {
            detailDialog.dismiss();
            detailDialog = null;
        }
    }

    private void showOrUpdateDetailDialog(@NonNull ContactsViewModel.UiState state) {
        if (detailDialog == null) {
            LinearLayout root = buildDetailContent(state);
            detailDialog = new MaterialAlertDialogBuilder(requireContext())
                    .setView(root)
                    .setOnDismissListener(d -> {
                        viewModel.closeDetail();
                        detailDialog = null;
                    })
                    .show();
        } else {
            View pane = detailDialog.getWindow() == null
                    ? null
                    : detailDialog.getWindow().getDecorView().findViewWithTag("detail_root");
            if (pane instanceof LinearLayout root) {
                bindDetailContent(root, state);
            }
        }
    }

    private LinearLayout buildDetailContent(@NonNull ContactsViewModel.UiState state) {
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setTag("detail_root");
        int pad = getResources().getDimensionPixelSize(R.dimen.space_lg);
        root.setPadding(pad, pad, pad, pad);
        bindDetailContent(root, state);
        return root;
    }

    private void bindDetailContent(@NonNull LinearLayout root, @NonNull ContactsViewModel.UiState state) {
        root.removeAllViews();
        UserDto user = state.getDetailUser();
        if (user == null) {
            return;
        }
        var contact = state.getDetailContact();
        TextView name = new TextView(requireContext());
        name.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleLarge);
        name.setText(contact != null ? contact.getPrimaryLabel() : user.getDisplayName());
        root.addView(name);

        TextView phone = new TextView(requireContext());
        phone.setText(user.getPhone());
        root.addView(phone);

        ApiError detailError = state.getDetailError();
        if (detailError != null) {
            TextView err = new TextView(requireContext());
            err.setText(getString(ContactErrorIds.messageId(detailError)));
            root.addView(err);
        }

        if (contact != null) {
            EditText alias = new EditText(requireContext());
            alias.setSingleLine(true);
            alias.setHint(R.string.contact_alias_label);
            alias.setText(state.getAliasInput());
            alias.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                    viewModel.onAliasChange(s.toString());
                }
                @Override public void afterTextChanged(Editable s) {}
            });
            root.addView(alias);

            MaterialButton saveAlias = new MaterialButton(requireContext());
            saveAlias.setText(R.string.action_save);
            saveAlias.setOnClickListener(v -> viewModel.saveAlias());
            root.addView(saveAlias);
        }

        MaterialButton message = new MaterialButton(requireContext());
        message.setText(R.string.action_message);
        message.setEnabled(!state.isDetailBusy());
        message.setOnClickListener(v -> viewModel.messageSelected());
        root.addView(message, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        MaterialButton secondary = new MaterialButton(requireContext());
        secondary.setEnabled(!state.isDetailBusy());
        if (contact == null) {
            secondary.setText(R.string.action_add_contact);
            secondary.setOnClickListener(v -> viewModel.addDetailContact());
        } else {
            secondary.setText(R.string.action_remove_contact);
            secondary.setOnClickListener(v -> viewModel.removeSelectedContact());
        }
        root.addView(secondary);

        if (state.isDetailBusy()) {
            root.addView(new ProgressBar(requireContext()));
        }
    }

    @Override
    public void onDestroyView() {
        if (detailDialog != null) {
            detailDialog.dismiss();
            detailDialog = null;
        }
        super.onDestroyView();
        binding = null;
    }
}
