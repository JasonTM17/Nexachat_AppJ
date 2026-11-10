package com.nexachat.feature.contacts;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
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
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.nexachat.R;
import com.nexachat.core.common.ApiError;
import com.nexachat.core.network.dto.UserDto;
import com.nexachat.core.ui.components.AvatarView;
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
    private boolean renderingDetailAlias;
    @Nullable private androidx.appcompat.app.AlertDialog detailDialog;
    @Nullable private EditText detailAliasInput;
    @Nullable private String detailBoundUserId;
    private boolean detailBoundIsContact;

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
                        detailAliasInput = null;
                        detailBoundUserId = null;
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
        UserDto user = state.getDetailUser();
        if (user == null) {
            return;
        }
        var contact = state.getDetailContact();
        boolean isContact = contact != null;
        if (detailAliasInput != null
                && user.getId().equals(detailBoundUserId)
                && isContact == detailBoundIsContact
                && state.getDetailError() == null
                && !state.isDetailBusy()) {
            renderingDetailAlias = true;
            if (!detailAliasInput.getText().toString().equals(state.getAliasInput())) {
                detailAliasInput.setText(state.getAliasInput());
            }
            renderingDetailAlias = false;
            return;
        }
        detailBoundUserId = user.getId();
        detailBoundIsContact = isContact;
        detailAliasInput = null;
        root.removeAllViews();
        int spaceSm = getResources().getDimensionPixelSize(R.dimen.space_sm);
        int spaceMd = getResources().getDimensionPixelSize(R.dimen.space_md);

        String displayTitle = contact != null ? contact.getPrimaryLabel() : user.getDisplayName();
        AvatarView avatar = new AvatarView(requireContext());
        avatar.setName(displayTitle);
        avatar.setAvatarUrl(contact != null ? contact.getAvatarUrl() : user.getAvatarUrl());
        int avatarSize = Math.round(64 * getResources().getDisplayMetrics().density);
        LinearLayout.LayoutParams avatarLp = new LinearLayout.LayoutParams(avatarSize, avatarSize);
        avatarLp.gravity = Gravity.CENTER_HORIZONTAL;
        avatarLp.bottomMargin = spaceSm;
        root.addView(avatar, avatarLp);

        TextView name = new TextView(requireContext());
        name.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleLarge);
        name.setGravity(Gravity.CENTER_HORIZONTAL);
        name.setText(displayTitle);
        root.addView(name, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView phone = new TextView(requireContext());
        phone.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        phone.setGravity(Gravity.CENTER_HORIZONTAL);
        phone.setText(user.getPhone());
        LinearLayout.LayoutParams phoneLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        phoneLp.bottomMargin = spaceMd;
        root.addView(phone, phoneLp);

        ApiError detailError = state.getDetailError();
        if (detailError != null) {
            TextView err = new TextView(requireContext());
            err.setText(getString(ContactErrorIds.messageId(detailError)));
            LinearLayout.LayoutParams errLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            errLp.bottomMargin = spaceSm;
            root.addView(err, errLp);
        }

        if (contact != null) {
            TextInputLayout aliasLayout = new TextInputLayout(
                    requireContext(),
                    null,
                    com.google.android.material.R.attr.textInputOutlinedStyle);
            aliasLayout.setHint(getString(R.string.contact_alias_label));
            float radius = getResources().getDimension(R.dimen.corner_md);
            aliasLayout.setBoxCornerRadii(radius, radius, radius, radius);
            TextInputEditText alias = new TextInputEditText(aliasLayout.getContext());
            alias.setSingleLine(true);
            renderingDetailAlias = true;
            alias.setText(state.getAliasInput());
            renderingDetailAlias = false;
            alias.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                    if (!renderingDetailAlias && !s.toString().equals(state.getAliasInput())) {
                        viewModel.onAliasChange(s.toString());
                    }
                }
                @Override public void afterTextChanged(Editable s) {}
            });
            detailAliasInput = alias;
            aliasLayout.addView(alias);
            LinearLayout.LayoutParams aliasLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            aliasLp.bottomMargin = spaceSm;
            root.addView(aliasLayout, aliasLp);

            MaterialButton saveAlias = new MaterialButton(
                    requireContext(),
                    null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle);
            saveAlias.setText(R.string.action_save);
            saveAlias.setOnClickListener(v -> viewModel.saveAlias());
            LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            saveLp.bottomMargin = spaceSm;
            root.addView(saveAlias, saveLp);
        }

        MaterialButton message = new MaterialButton(requireContext());
        message.setText(R.string.action_message);
        message.setEnabled(!state.isDetailBusy());
        message.setOnClickListener(v -> viewModel.messageSelected());
        LinearLayout.LayoutParams messageLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        messageLp.bottomMargin = spaceSm;
        root.addView(message, messageLp);

        MaterialButton secondary = new MaterialButton(
                requireContext(),
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        secondary.setEnabled(!state.isDetailBusy());
        if (contact == null) {
            secondary.setText(R.string.action_add_contact);
            secondary.setOnClickListener(v -> viewModel.addDetailContact());
        } else {
            secondary.setText(R.string.action_remove_contact);
            secondary.setOnClickListener(v -> {
                new MaterialAlertDialogBuilder(requireContext())
                        .setMessage(getString(R.string.action_remove_contact) + "?")
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(R.string.action_remove_contact, (d, w) -> viewModel.removeSelectedContact())
                        .show();
            });
        }
        root.addView(secondary, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        if (state.isDetailBusy()) {
            ProgressBar bar = new ProgressBar(requireContext());
            LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            barLp.gravity = Gravity.CENTER_HORIZONTAL;
            barLp.topMargin = spaceSm;
            root.addView(bar, barLp);
        }
    }

    @Override
    public void onDestroyView() {
        if (detailDialog != null) {
            detailDialog.dismiss();
            detailDialog = null;
            detailAliasInput = null;
            detailBoundUserId = null;
        }
        if (binding != null) {
            binding.resultsList.setAdapter(null);
        }
        adapter = null;
        super.onDestroyView();
        binding = null;
    }
}
