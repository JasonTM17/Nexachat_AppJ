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
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.nexachat.R;
import com.nexachat.core.common.ApiError;
import com.nexachat.core.common.PersonContactSubtitle;
import com.nexachat.core.network.dto.UserDto;
import com.nexachat.data.Contact;
import com.nexachat.databinding.FragmentContactsBinding;
import com.nexachat.feature.contacts.adapter.ContactListAdapter;
import com.nexachat.feature.ui.FormDialogs;
import dagger.hilt.android.AndroidEntryPoint;
import java.util.List;

/** Contact list, add-by-phone flow, and contact detail actions. */
@AndroidEntryPoint
public final class ContactsFragment extends Fragment {

    private static final long ADD_DONE_DISMISS_MS = 900L;
    private static final long BUBBLE_HIDE_MS = 400L;
    private static final String STATE_ADD_DIALOG = "addContactDialogShowing";

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
    private boolean addDialogShowing;
    /** Set while tearing the view down so a rotation dismiss doesn't wipe the typed phone. */
    private boolean suppressAddFlowReset;
    private int lastCachedRefreshErrorId;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable hideBubble = () -> {
        if (binding != null) {
            binding.letterBubble.setVisibility(View.GONE);
        }
    };

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
        binding.contactList.addItemDecoration(new ContactLetterHeaderDecoration(adapter));
        binding.contactList.setAdapter(adapter);
        binding.letterRail.setListener(new ContactLetterRailView.Listener() {
            @Override
            public void onLetterActivated(@NonNull String letter, float anchorY) {
                if (binding == null || adapter == null) {
                    return;
                }
                int position = ContactLetterIndex.adapterPosition(
                        adapter.getCurrentList(), letter);
                if (position >= 0) {
                    ((LinearLayoutManager) binding.contactList.getLayoutManager())
                            .scrollToPositionWithOffset(position, 0);
                }
                showLetterBubble(letter, anchorY);
            }

            @Override
            public void onScrubEnd() {
                handler.postDelayed(hideBubble, BUBBLE_HIDE_MS);
            }
        });
        binding.toolbar.setNavigationOnClickListener(v -> NavHostFragment.findNavController(this).navigateUp());
        binding.searchButton.setOnClickListener(
                v -> NavHostFragment.findNavController(this).navigate(R.id.searchFragment));
        binding.searchPill.setOnClickListener(
                v -> NavHostFragment.findNavController(this).navigate(R.id.searchFragment));
        binding.addContactEntry.setTitle(getString(R.string.add_contact_title));
        binding.addContactEntry.setLeadingIcon(R.drawable.ic_person_add);
        binding.addContactEntry.setOnClickListener(v -> showAddByPhoneDialog());
        binding.createGroupEntry.setTitle(getString(R.string.group_create_title));
        binding.createGroupEntry.setLeadingIcon(R.drawable.ic_people);
        binding.createGroupEntry.setOnClickListener(
                v -> NavHostFragment.findNavController(this).navigate(R.id.groupCreateFragment));
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
        if (state != null && state.getBoolean(STATE_ADD_DIALOG, false)) {
            // Reopen the add-by-phone dialog without resetting the flow: the
            // ViewModel still holds the typed phone and in-flight result.
            showAddByPhoneDialog(false);
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_ADD_DIALOG, addDialogShowing);
    }

    private void render(@NonNull ContactsViewModel.UiState state) {
        if (binding == null) {
            return;
        }
        List<Contact> contacts = state.getContacts();
        // submitList diffs asynchronously, so the rail reads the committed list
        // from the commit callback rather than the still-stale current list.
        adapter.submitContacts(contacts, () -> updateLetterRail(!contacts.isEmpty()));
        boolean showLoading = state.isLoading() && !state.isContactsLoaded();
        ApiError error = state.getError();
        boolean showError = error != null && contacts.isEmpty();
        boolean showEmpty = !showLoading && !showError && contacts.isEmpty();
        boolean showList = !contacts.isEmpty();

        if (error == null || contacts.isEmpty()) {
            lastCachedRefreshErrorId = 0;
        } else {
            int errorMessageId = ContactErrorIds.messageId(error);
            if (lastCachedRefreshErrorId != errorMessageId) {
                lastCachedRefreshErrorId = errorMessageId;
                Snackbar.make(
                                binding.getRoot(),
                                getString(errorMessageId),
                                Snackbar.LENGTH_LONG)
                        .setAction(R.string.action_retry, ignored -> viewModel.loadContacts())
                        .show();
            }
        }

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

    /**
     * The rail mirrors the section letters the adapter currently shows. While
     * it is visible the list reserves the rail's width as end padding so rows
     * never sit under it; at large font scale the rail steps aside entirely.
     */
    private void updateLetterRail(boolean showList) {
        if (binding == null) {
            return;
        }
        List<String> letters = ContactLetterIndex.letters(adapter.getCurrentList());
        boolean show = showList
                && ContactLetterIndex.isRailVisible(
                        letters.size(),
                        getResources().getConfiguration().fontScale);
        binding.letterRail.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            binding.letterRail.setLetters(letters);
        }
        int endPadding = show
                ? getResources().getDimensionPixelSize(R.dimen.min_touch_target)
                : 0;
        if (binding.contactList.getPaddingEnd() != endPadding) {
            binding.contactList.setPaddingRelative(
                    binding.contactList.getPaddingStart(),
                    binding.contactList.getPaddingTop(),
                    endPadding,
                    binding.contactList.getPaddingBottom());
        }
    }

    /** Transient drag preview anchored to the letter under the finger. */
    private void showLetterBubble(@NonNull String letter, float anchorY) {
        handler.removeCallbacks(hideBubble);
        binding.letterBubble.setText(letter);
        binding.letterBubble.setVisibility(View.VISIBLE);
        // getHeight() is still 0 on the first activation because the bubble has
        // not been laid out yet; the layout fixes its size via the dimen.
        float bubbleHeight =
                getResources().getDimensionPixelSize(R.dimen.letter_bubble_size);
        float min = binding.letterRail.getTop();
        float max = Math.max(min, binding.letterRail.getBottom() - bubbleHeight);
        float top = binding.letterRail.getTop() + anchorY - bubbleHeight / 2f;
        binding.letterBubble.setTranslationY(Math.max(min, Math.min(top, max)));
    }

    private void showAddByPhoneDialog() {
        showAddByPhoneDialog(true);
    }

    private void showAddByPhoneDialog(boolean resetFlow) {
        if (resetFlow) {
            viewModel.resetAddFlow();
        }
        LinearLayout root = new LinearLayout(requireContext());
        ScrollView scroll = new ScrollView(requireContext());
        scroll.setFillViewport(true);
        scroll.addView(root);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = getResources().getDimensionPixelSize(R.dimen.space_lg);
        int spaceSm = getResources().getDimensionPixelSize(R.dimen.space_sm);
        root.setPadding(pad, pad, pad, pad);

        TextInputLayout phoneLayout = new TextInputLayout(
                requireContext(),
                null,
                com.google.android.material.R.attr.textInputStyle);
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
        addErrorText.setTextAppearance(R.style.TextAppearance_NexaChat_BodyMedium);
        addErrorText.setTextColor(MaterialColors.getColor(
                addErrorText, com.google.android.material.R.attr.colorError));
        addErrorText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        addErrorText.setVisibility(View.GONE);
        LinearLayout.LayoutParams errLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        errLp.bottomMargin = spaceSm;
        root.addView(addErrorText, errLp);

        addResultText = new TextView(requireContext());
        addResultText.setTextAppearance(R.style.TextAppearance_NexaChat_BodyLarge);
        addResultText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        addResultText.setVisibility(View.GONE);
        LinearLayout.LayoutParams resLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        resLp.bottomMargin = spaceSm;
        root.addView(addResultText, resLp);

        addDoneText = new TextView(requireContext());
        addDoneText.setTextAppearance(R.style.TextAppearance_NexaChat_BodyMedium);
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
                .setTitle(R.string.add_contact_title)
                .setView(scroll)
                .setNegativeButton(android.R.string.cancel, (d, w) -> {
                    viewModel.resetAddFlow();
                    addDialog = null;
                })
                .setOnDismissListener(d -> {
                    if (!suppressAddFlowReset) {
                        viewModel.resetAddFlow();
                    }
                    addDialogShowing = false;
                    clearAddDialogRefs();
                })
                .show();
        addDialogShowing = true;
        FormDialogs.resizeAboveIme(addDialog);
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
            suppressAddFlowReset = true;
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
