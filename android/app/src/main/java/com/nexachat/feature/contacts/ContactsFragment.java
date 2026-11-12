package com.nexachat.feature.contacts;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.LinearLayout;
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
import com.nexachat.core.common.PersonContactSubtitle;
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
    @Nullable private ContactDetailBinder detailBinder;
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
        detailBinder = new ContactDetailBinder(this, viewModel);
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

        if (detailBinder != null) {
            detailBinder.render(state);
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
        int spaceSm = getResources().getDimensionPixelSize(R.dimen.space_sm);
        int spaceMd = getResources().getDimensionPixelSize(R.dimen.space_md);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(requireContext());
        title.setText(R.string.add_contact_title);
        title.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleLarge);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        titleLp.bottomMargin = spaceMd;
        root.addView(title, titleLp);

        TextInputLayout phoneLayout = new TextInputLayout(
                requireContext(),
                null,
                com.google.android.material.R.attr.textInputOutlinedStyle);
        float radius = getResources().getDimension(R.dimen.corner_md);
        phoneLayout.setBoxCornerRadii(radius, radius, radius, radius);
        phoneLayout.setEndIconMode(TextInputLayout.END_ICON_CLEAR_TEXT);
        addPhoneInput = new TextInputEditText(phoneLayout.getContext());
        addPhoneInput.setSingleLine(true);
        addPhoneInput.setInputType(
                android.text.InputType.TYPE_CLASS_TEXT
                        | android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        addPhoneInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        phoneLayout.setHint(getString(R.string.add_contact_field_label));
        phoneLayout.addView(addPhoneInput);
        LinearLayout.LayoutParams phoneLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        phoneLp.bottomMargin = spaceSm;
        root.addView(phoneLayout, phoneLp);

        addErrorText = new TextView(requireContext());
        addErrorText.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        addErrorText.setVisibility(View.GONE);
        LinearLayout.LayoutParams errLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        errLp.bottomMargin = spaceSm;
        root.addView(addErrorText, errLp);

        addResultText = new TextView(requireContext());
        addResultText.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
        addResultText.setVisibility(View.GONE);
        LinearLayout.LayoutParams resLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        resLp.bottomMargin = spaceSm;
        root.addView(addResultText, resLp);

        addDoneText = new TextView(requireContext());
        addDoneText.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        addDoneText.setVisibility(View.GONE);
        addDoneText.setText(R.string.contact_added);
        LinearLayout.LayoutParams doneLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        doneLp.bottomMargin = spaceSm;
        root.addView(addDoneText, doneLp);

        addActionButton = new MaterialButton(requireContext());
        addActionButton.setText(R.string.action_search);
        LinearLayout.LayoutParams actionLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        actionLp.topMargin = spaceSm;
        root.addView(addActionButton, actionLp);

        addPhoneInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                viewModel.onAddPhoneChange(s.toString());
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        addPhoneInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                if (addActionButton != null && addActionButton.isEnabled()) {
                    addActionButton.performClick();
                }
                return true;
            }
            return false;
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
                String second = PersonContactSubtitle.subtitle(found);
                if (second == null) {
                    second = "";
                }
                addResultText.setText(getString(
                        R.string.a11y_contact_found_lines,
                        found.getDisplayName(),
                        second));
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

    @Override
    public void onDestroyView() {
        handler.removeCallbacksAndMessages(null);
        if (addDialog != null) {
            addDialog.dismiss();
            clearAddDialogRefs();
        }
        if (detailBinder != null) {
            detailBinder.releaseForViewDestroy();
            detailBinder = null;
        }
        if (binding != null) {
            binding.contactList.setAdapter(null);
        }
        adapter = null;
        super.onDestroyView();
        binding = null;
    }
}
