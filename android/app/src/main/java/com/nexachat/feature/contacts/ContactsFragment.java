package com.nexachat.feature.contacts;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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
import com.nexachat.data.Contact;
import com.nexachat.databinding.FragmentContactsBinding;
import com.nexachat.feature.contacts.adapter.ContactListAdapter;
import dagger.hilt.android.AndroidEntryPoint;
import java.util.List;

/** Contact list, add-by-phone flow, and contact detail actions. */
@AndroidEntryPoint
public final class ContactsFragment extends Fragment {

    private static final long ADD_DONE_DISMISS_MS = 900L;

    private FragmentContactsBinding binding;
    private ContactsViewModel viewModel;
    private ContactListAdapter adapter;
    @Nullable private androidx.appcompat.app.AlertDialog addDialog;
    @Nullable private TextInputEditText addPhoneInput;
    @Nullable private TextView addErrorText;
    @Nullable private TextView addResultText;
    @Nullable private TextView addDoneText;
    @Nullable private MaterialButton addActionButton;
    @Nullable private androidx.appcompat.app.AlertDialog detailDialog;
    @Nullable private LinearLayout detailRoot;
    @Nullable private EditText detailAliasInput;
    @Nullable private String detailBoundUserId;
    private boolean detailBoundIsContact;
    private boolean renderingDetailAlias;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle state) {
        binding = FragmentContactsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        viewModel = new ViewModelProvider(this).get(ContactsViewModel.class);
        binding.toolbar.setTitle(R.string.contacts_title);
        adapter = new ContactListAdapter(userId -> {
            ContactsViewModel.UiState ui = viewModel.getUiState().getValue();
            for (Contact contact : ui.getContacts()) {
                if (contact.getUserId().equals(userId)) {
                    viewModel.openContactDetail(contact);
                    return;
                }
            }
        });
        binding.contactList.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.contactList.setAdapter(adapter);
        binding.toolbar.setNavigationOnClickListener(v -> NavHostFragment.findNavController(this).navigateUp());
        binding.searchButton.setOnClickListener(
                v -> NavHostFragment.findNavController(this).navigate(R.id.searchFragment));
        binding.addContactFab.setOnClickListener(v -> showAddByPhoneDialog());
        binding.errorState.setOnRetry(viewModel::loadContacts);
        binding.emptyState.setMessage(getString(R.string.contacts_empty));
        binding.emptyState.setIcon(R.drawable.ic_person_outline);
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
        List<Contact> contacts = state.getContacts();
        adapter.submitList(contacts);
        boolean showLoading = state.isLoading() && !state.isContactsLoaded();
        ApiError error = state.getError();
        boolean showError = error != null && contacts.isEmpty();
        boolean showEmpty = !showLoading && !showError && contacts.isEmpty();
        boolean showList = !contacts.isEmpty();

        binding.loadingState.setVisibility(showLoading ? View.VISIBLE : View.GONE);
        binding.contactList.setVisibility(showList ? View.VISIBLE : View.GONE);
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

        if (addDialog != null && addDialog.isShowing()) {
            updateAddDialog(state);
        }

        if (state.isAddDone()) {
            handler.postDelayed(() -> {
                if (addDialog != null) {
                    addDialog.dismiss();
                    addDialog = null;
                }
                viewModel.resetAddFlow();
            }, ADD_DONE_DISMISS_MS);
        }
    }

    private void showAddByPhoneDialog() {
        viewModel.resetAddFlow();
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = getResources().getDimensionPixelSize(R.dimen.space_lg);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(requireContext());
        title.setText(R.string.add_by_phone_title);
        title.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
        root.addView(title);

        TextInputLayout phoneLayout = new TextInputLayout(requireContext());
        addPhoneInput = new TextInputEditText(requireContext());
        addPhoneInput.setSingleLine(true);
        addPhoneInput.setInputType(android.text.InputType.TYPE_CLASS_PHONE);
        phoneLayout.setHint(getString(R.string.auth_phone_label));
        phoneLayout.addView(addPhoneInput);
        root.addView(phoneLayout, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        addErrorText = new TextView(requireContext());
        addErrorText.setVisibility(View.GONE);
        root.addView(addErrorText);

        addResultText = new TextView(requireContext());
        addResultText.setVisibility(View.GONE);
        root.addView(addResultText);

        addDoneText = new TextView(requireContext());
        addDoneText.setVisibility(View.GONE);
        addDoneText.setText(R.string.contact_added);
        root.addView(addDoneText);

        addActionButton = new MaterialButton(requireContext());
        addActionButton.setText(R.string.action_search);
        root.addView(addActionButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        addPhoneInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                viewModel.onAddPhoneChange(s.toString());
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        addActionButton.setOnClickListener(v -> {
            ContactsViewModel.UiState ui = viewModel.getUiState().getValue();
            if (ui.getAddResult() == null) {
                viewModel.findUserForAdd();
            } else {
                viewModel.confirmAdd();
            }
        });

        addDialog = new MaterialAlertDialogBuilder(requireContext())
                .setView(root)
                .setNegativeButton(android.R.string.cancel, (d, w) -> {
                    viewModel.resetAddFlow();
                    addDialog = null;
                })
                .setOnDismissListener(d -> {
                    viewModel.resetAddFlow();
                    clearAddDialogRefs();
                })
                .show();
        updateAddDialog(viewModel.getUiState().getValue());
    }

    private void clearAddDialogRefs() {
        addDialog = null;
        addPhoneInput = null;
        addErrorText = null;
        addResultText = null;
        addDoneText = null;
        addActionButton = null;
    }

    private void updateAddDialog(@NonNull ContactsViewModel.UiState state) {
        if (addPhoneInput == null || addActionButton == null) {
            return;
        }
        if (!addPhoneInput.getText().toString().equals(state.getAddPhone())) {
            addPhoneInput.setText(state.getAddPhone());
        }
        ApiError err = state.getAddError();
        if (addErrorText != null) {
            if (err != null) {
                addErrorText.setVisibility(View.VISIBLE);
                addErrorText.setText(getString(ContactErrorIds.messageId(err)));
            } else {
                addErrorText.setVisibility(View.GONE);
            }
        }
        UserDto found = state.getAddResult();
        if (addResultText != null) {
            if (found != null) {
                addResultText.setVisibility(View.VISIBLE);
                addResultText.setText(getString(
                        R.string.a11y_contact_found_lines,
                        found.getDisplayName(),
                        found.getPhone()));
            } else {
                addResultText.setVisibility(View.GONE);
            }
        }
        if (addDoneText != null) {
            addDoneText.setVisibility(state.isAddDone() ? View.VISIBLE : View.GONE);
        }
        boolean busy = state.isAddSearching() || state.isAdding();
        addActionButton.setEnabled(state.getAddPhone().trim().length() > 0 && !busy && !state.isAddDone());
        addActionButton.setText(found == null ? R.string.action_search : R.string.action_add_contact);
    }

    private void showOrUpdateDetailDialog(@NonNull ContactsViewModel.UiState state) {
        UserDto user = state.getDetailUser();
        if (user == null) {
            return;
        }
        if (detailDialog == null) {
            detailRoot = new LinearLayout(requireContext());
            detailRoot.setOrientation(LinearLayout.VERTICAL);
            int pad = getResources().getDimensionPixelSize(R.dimen.space_lg);
            detailRoot.setPadding(pad, pad, pad, pad);
            bindDetailView(detailRoot, state);
            detailDialog = new MaterialAlertDialogBuilder(requireContext())
                    .setView(detailRoot)
                    .setOnDismissListener(d -> {
                        viewModel.closeDetail();
                        detailDialog = null;
                        detailRoot = null;
                        detailAliasInput = null;
                        detailBoundUserId = null;
                    })
                    .show();
        } else if (detailRoot != null) {
            bindDetailView(detailRoot, state);
        }
    }

    private void bindDetailView(@NonNull LinearLayout root, @NonNull ContactsViewModel.UiState state) {
        UserDto user = state.getDetailUser();
        if (user == null) {
            return;
        }
        Contact contact = state.getDetailContact();
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
            root.addView(alias, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));

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
            secondary.setOnClickListener(v -> {
                new MaterialAlertDialogBuilder(requireContext())
                        .setMessage(getString(R.string.action_remove_contact) + "?")
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(R.string.action_remove_contact, (d, w) -> viewModel.removeSelectedContact())
                        .show();
            });
        }
        root.addView(secondary);

        if (state.isDetailBusy()) {
            ProgressBar bar = new ProgressBar(requireContext());
            root.addView(bar);
        }
    }

    @Override
    public void onDestroyView() {
        handler.removeCallbacksAndMessages(null);
        if (addDialog != null) {
            addDialog.dismiss();
            clearAddDialogRefs();
        }
        if (detailDialog != null) {
            detailDialog.dismiss();
            detailDialog = null;
            detailRoot = null;
            detailAliasInput = null;
            detailBoundUserId = null;
        }
        if (binding != null) {
            binding.contactList.setAdapter(null);
        }
        adapter = null;
        super.onDestroyView();
        binding = null;
    }
}
