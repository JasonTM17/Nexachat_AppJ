package com.nexachat.feature.group;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavOptions;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.snackbar.Snackbar;
import com.nexachat.R;
import com.nexachat.core.common.ApiError;
import com.nexachat.core.ui.CreateGroupListRules;
import com.nexachat.core.ui.components.AvatarView;
import com.nexachat.data.Contact;
import com.nexachat.databinding.FragmentCreateGroupBinding;
import dagger.hilt.android.AndroidEntryPoint;
import java.util.Objects;
import java.util.Set;

/** Create a group from selected contacts and open its chat. */
@AndroidEntryPoint
public final class CreateGroupFragment extends Fragment {

    private FragmentCreateGroupBinding binding;
    private CreateGroupViewModel viewModel;
    private SelectableContactAdapter memberAdapter;
    private boolean renderingTitle;
    @Nullable private ApiError lastSnackbarError;

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle state) {
        binding = FragmentCreateGroupBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        viewModel = new ViewModelProvider(this).get(CreateGroupViewModel.class);
        binding.toolbar.setTitle(R.string.group_create_title);
        memberAdapter = new SelectableContactAdapter(viewModel::toggleMember);
        binding.memberPickerList.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.memberPickerList.setAdapter(memberAdapter);
        binding.toolbar.setNavigationOnClickListener(v -> NavHostFragment.findNavController(this).navigateUp());
        binding.errorState.setOnRetry(viewModel::loadContacts);
        binding.emptyState.setMessage(getString(R.string.contacts_empty));
        binding.emptyState.setIcon(R.drawable.ic_person_outline);
        binding.groupNameInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int c, int d) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                if (!renderingTitle) {
                    viewModel.onTitleChange(s.toString());
                }
            }
            @Override public void afterTextChanged(Editable e) {}
        });
        binding.createButton.setOnClickListener(v -> viewModel.create());
        viewModel.getUiState()
                .observe(getViewLifecycleOwner(), this::render);
        viewModel.getEvents()
                .observe(getViewLifecycleOwner(), raw -> {
                    CreateGroupEvent event = raw == null ? null : raw.consume();
                    if (event instanceof CreateGroupEvent.Created created) {
                        Bundle args = new Bundle();
                        args.putString("conversationId", created.getConversationId());
                        NavOptions options = new NavOptions.Builder()
                                .setPopUpTo(R.id.groupCreateFragment, true)
                                .build();
                        NavHostFragment.findNavController(this).navigate(R.id.chatFragment, args, options);
                    }
                });
    }

    private void render(@NonNull CreateGroupUiState state) {
        if (binding == null) {
            return;
        }
        renderingTitle = true;
        if (!binding.groupNameInput.getText().toString().equals(state.getTitle())) {
            binding.groupNameInput.setText(state.getTitle());
        }
        renderingTitle = false;

        memberAdapter.submit(state.getContacts(), state.getSelectedIds());
        binding.createButton.setEnabled(state.isCanCreate());

        boolean showLoadError = CreateGroupListRules.showLoadError(
                state.isLoading(), state.getContacts().isEmpty(), state.getError() != null);
        boolean showLoading = state.isLoading() && !state.isContactsLoaded();
        boolean showEmpty = !showLoading && !showLoadError && state.getContacts().isEmpty();
        boolean showPicker = !state.getContacts().isEmpty();

        binding.loadingState.setVisibility(showLoading ? View.VISIBLE : View.GONE);
        binding.memberPickerList.setVisibility(showPicker ? View.VISIBLE : View.GONE);
        binding.emptyState.setVisibility(showEmpty ? View.VISIBLE : View.GONE);
        binding.errorState.setVisibility(showLoadError ? View.VISIBLE : View.GONE);
        if (showLoadError) {
            ApiError err = state.getError();
            binding.errorState.setMessage(getString(
                    err == null ? R.string.error_generic : GroupErrorText.resource(err)));
        }

        ApiError toastError = state.getError();
        if (toastError != null && !showLoadError && !Objects.equals(toastError, lastSnackbarError)) {
            lastSnackbarError = toastError;
            Snackbar.make(binding.getRoot(), getString(GroupErrorText.resource(toastError)), Snackbar.LENGTH_SHORT)
                    .addCallback(new Snackbar.Callback() {
                        @Override public void onDismissed(Snackbar transientBottomBar, int event) {
                            viewModel.dismissError();
                        }
                    })
                    .show();
        } else if (toastError == null) {
            lastSnackbarError = null;
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    private static final class SelectableContactAdapter
            extends ListAdapter<Contact, SelectableContactAdapter.Holder> {

        interface ToggleListener {
            void onToggle(@NonNull String userId);
        }

        private final ToggleListener listener;
        private Set<String> selectedIds = Set.of();

        SelectableContactAdapter(@NonNull ToggleListener listener) {
            super(DIFF);
            this.listener = listener;
        }

        void submit(@NonNull java.util.List<Contact> contacts, @NonNull Set<String> selected) {
            selectedIds = selected;
            submitList(contacts);
        }

        private static final DiffUtil.ItemCallback<Contact> DIFF = new DiffUtil.ItemCallback<Contact>() {
            @Override
            public boolean areItemsTheSame(@NonNull Contact oldItem, @NonNull Contact newItem) {
                return oldItem.getUserId().equals(newItem.getUserId());
            }

            @Override
            public boolean areContentsTheSame(@NonNull Contact oldItem, @NonNull Contact newItem) {
                return oldItem.equals(newItem) && oldItem.getUserId().equals(newItem.getUserId());
            }
        };

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View row = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_user_search, parent, false);
            CheckBox checkBox = new CheckBox(parent.getContext());
            ((ViewGroup) row).addView(checkBox);
            return new Holder(row, checkBox);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            Contact contact = getItem(position);
            holder.bind(contact, selectedIds.contains(contact.getUserId()), listener);
        }

        static final class Holder extends RecyclerView.ViewHolder {
            private final AvatarView avatar;
            private final TextView name;
            private final TextView subtitle;
            private final CheckBox checkBox;

            Holder(@NonNull View itemView, @NonNull CheckBox checkBox) {
                super(itemView);
                this.checkBox = checkBox;
                avatar = itemView.findViewById(R.id.avatar);
                name = itemView.findViewById(R.id.name);
                subtitle = itemView.findViewById(R.id.subtitle);
            }

            void bind(@NonNull Contact contact, boolean selected, @NonNull ToggleListener listener) {
                String label = contact.getPrimaryLabel();
                name.setText(label);
                subtitle.setText(contact.getPhone());
                avatar.setName(label);
                avatar.setAvatarUrl(contact.getAvatarUrl());
                checkBox.setOnCheckedChangeListener(null);
                checkBox.setChecked(selected);
                View.OnClickListener toggle = v -> listener.onToggle(contact.getUserId());
                itemView.setOnClickListener(toggle);
                checkBox.setOnCheckedChangeListener((b, checked) -> listener.onToggle(contact.getUserId()));
            }
        }
    }
}
