package com.nexachat.feature.group;

import android.app.Dialog;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.nexachat.R;
import com.nexachat.core.common.ApiError;
import com.nexachat.core.ui.GroupDetailListRules;
import com.nexachat.core.ui.components.AvatarView;
import com.nexachat.data.Contact;
import com.nexachat.data.GroupMember;
import com.nexachat.databinding.BottomSheetGroupAddMemberBinding;
import com.nexachat.databinding.FragmentGroupDetailBinding;
import com.nexachat.feature.group.adapter.GroupMemberAdapter;
import dagger.hilt.android.AndroidEntryPoint;
import java.util.Objects;
import java.util.Set;

/** Group membership list and admin actions. */
@AndroidEntryPoint
public final class GroupDetailFragment extends Fragment {

    private static final int MENU_RENAME = View.generateViewId();

    private FragmentGroupDetailBinding binding;
    private GroupDetailViewModel viewModel;
    private GroupMemberAdapter memberAdapter;
    private ActivityResultLauncher<String> avatarPicker;
    @Nullable private AlertDialog renameDialog;
    @Nullable private AlertDialog removeDialog;
    @Nullable private AlertDialog leaveDialog;
    @Nullable private Dialog addMemberDialog;
    @Nullable private AddMemberAdapter addMemberPickerAdapter;
    @Nullable private MaterialButton addMemberConfirmButton;
    @Nullable private EditText renameInput;
    private boolean renderingRename;
    @Nullable private ApiError lastSnackbarError;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        avatarPicker = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> {
                    if (uri != null) {
                        viewModel.setGroupAvatar(uri);
                    }
                });
    }

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle state) {
        binding = FragmentGroupDetailBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        viewModel = new ViewModelProvider(this).get(GroupDetailViewModel.class);
        memberAdapter = new GroupMemberAdapter(new GroupMemberAdapter.Listener() {
            @Override public void onMemberClick(@NonNull String id) {}

            @Override public void onRemoveMember(@NonNull String id) {
                for (GroupMember member : viewModel.getUiState().getValue().getMembers()) {
                    if (member.getUserId().equals(id)) {
                        viewModel.requestRemoveMember(member);
                        return;
                    }
                }
            }
        });
        binding.memberList.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.memberList.setAdapter(memberAdapter);
        binding.toolbar.setNavigationOnClickListener(v -> NavHostFragment.findNavController(this).navigateUp());
        MenuItem renameItem = binding.toolbar.getMenu().add(
                Menu.NONE, MENU_RENAME, Menu.NONE, R.string.action_rename_group);
        renameItem.setIcon(R.drawable.ic_edit);
        renameItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        binding.toolbar.setOnMenuItemClickListener(this::onMenuItem);
        binding.membersHeader.setTitle(getString(R.string.group_detail_title));
        binding.addMemberButton.setOnClickListener(v -> viewModel.openAddMember());
        binding.inviteLinkButton.setOnClickListener(v -> openInviteManager());
        binding.leaveGroupButton.setOnClickListener(v -> viewModel.requestLeave());
        binding.errorState.setOnRetry(viewModel::loadMembers);
        binding.emptyState.setMessage(getString(R.string.group_members_empty));
        binding.emptyState.setIcon(R.drawable.ic_person_outline);
        binding.groupAvatar.setOnClickListener(v -> {
            if (viewModel.getUiState().getValue().isIAmAdmin()) {
                avatarPicker.launch("image/*");
            }
        });
        binding.groupTitle.setOnClickListener(v -> {
            if (viewModel.getUiState().getValue().isIAmAdmin()) {
                viewModel.openRename();
            }
        });
        viewModel.getUiState()
                .observe(getViewLifecycleOwner(), this::render);
        viewModel.getEvents()
                .observe(getViewLifecycleOwner(), raw -> {
                    GroupDetailEvent event = raw == null ? null : raw.consume();
                    if (event instanceof GroupDetailEvent.ExitToInbox) {
                        NavHostFragment.findNavController(this).popBackStack(R.id.inboxFragment, false);
                    }
                });
    }

    private void openInviteManager() {
        Bundle args = getArguments();
        String conversationId = args == null ? null : args.getString("conversationId");
        if (conversationId == null || conversationId.isBlank()) return;
        Bundle inviteArgs = new Bundle();
        inviteArgs.putString("conversationId", conversationId);
        NavHostFragment.findNavController(this).navigate(R.id.inviteManageFragment, inviteArgs);
    }

    private boolean onMenuItem(@NonNull MenuItem item) {
        if (item.getItemId() == MENU_RENAME) {
            viewModel.openRename();
            return true;
        }
        return false;
    }

    private void render(@NonNull GroupDetailUiState state) {
        if (binding == null) {
            return;
        }
        String title = state.getTitle().isBlank()
                ? getString(R.string.group_detail_title)
                : state.getTitle();
        binding.toolbar.setTitle(title);
        binding.groupTitle.setText(title);
        binding.groupAvatar.setName(title);
        binding.groupAvatar.setAvatarUrl(state.getGroupAvatar());
        memberAdapter.setShowRemove(state.isIAmAdmin());
        memberAdapter.submitList(state.getMembers());
        binding.addMemberButton.setVisibility(state.isIAmAdmin() ? View.VISIBLE : View.GONE);
        binding.inviteLinkButton.setVisibility(state.isIAmAdmin() ? View.VISIBLE : View.GONE);
        binding.toolbar.getMenu().findItem(MENU_RENAME).setVisible(state.isIAmAdmin());
        binding.leaveGroupButton.setEnabled(!state.isBusy());

        boolean showLoadError = GroupDetailListRules.showLoadError(
                state.isLoading(), state.getMembers().isEmpty(), state.getError() != null);
        boolean showEmpty = !state.isLoading() && !showLoadError && state.getMembers().isEmpty();
        binding.detailColumn.setVisibility(showLoadError || showEmpty ? View.GONE : View.VISIBLE);
        binding.errorState.setVisibility(showLoadError ? View.VISIBLE : View.GONE);
        binding.emptyState.setVisibility(showEmpty ? View.VISIBLE : View.GONE);
        if (showLoadError) {
            ApiError loadError = state.getError();
            binding.errorState.setMessage(getString(
                    loadError == null ? R.string.error_generic : GroupErrorText.resource(loadError)));
        }

        ApiError error = state.getError();
        if (error != null && !showLoadError && !Objects.equals(error, lastSnackbarError)) {
            lastSnackbarError = error;
            Snackbar.make(binding.getRoot(), getString(GroupErrorText.resource(error)), Snackbar.LENGTH_SHORT)
                    .addCallback(new Snackbar.Callback() {
                        @Override public void onDismissed(Snackbar transientBottomBar, int event) {
                            viewModel.dismissError();
                        }
                    })
                    .show();
        } else if (error == null) {
            lastSnackbarError = null;
        }

        GroupMember pending = state.getPendingRemove();
        if (pending != null) {
            showRemoveDialog(pending);
        } else if (removeDialog != null) {
            removeDialog.dismiss();
            removeDialog = null;
        }

        if (state.isConfirmLeave()) {
            showLeaveDialog();
        } else if (leaveDialog != null) {
            leaveDialog.dismiss();
            leaveDialog = null;
        }

        if (state.isShowRename()) {
            showRenameDialog(state);
        } else if (renameDialog != null) {
            renameDialog.dismiss();
            renameDialog = null;
            renameInput = null;
        }

        if (state.isShowAddPicker()) {
            showAddMemberDialog(state);
        } else if (addMemberDialog != null) {
            addMemberDialog.dismiss();
            addMemberDialog = null;
            addMemberPickerAdapter = null;
            addMemberConfirmButton = null;
        }

        binding.loadingState.setVisibility(state.isLoading() || state.isBusy() ? View.VISIBLE : View.GONE);
    }

    private void showRemoveDialog(@NonNull GroupMember member) {
        if (removeDialog != null && removeDialog.isShowing()) {
            return;
        }
        removeDialog = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.action_remove_member)
                .setMessage(getString(R.string.group_remove_member_confirm, member.getDisplayName()))
                .setNegativeButton(R.string.action_cancel, (d, w) -> viewModel.dismissRemoveMember())
                .setPositiveButton(R.string.action_remove, (d, w) -> viewModel.confirmRemoveMember())
                .setOnDismissListener(d -> removeDialog = null)
                .show();
    }

    private void showLeaveDialog() {
        if (leaveDialog != null && leaveDialog.isShowing()) {
            return;
        }
        leaveDialog = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.action_leave_group)
                .setMessage(R.string.group_leave_confirm)
                .setNegativeButton(R.string.action_cancel, (d, w) -> viewModel.dismissLeave())
                .setPositiveButton(R.string.action_leave_group, (d, w) -> viewModel.confirmLeave())
                .setOnDismissListener(d -> leaveDialog = null)
                .show();
    }

    private void showRenameDialog(@NonNull GroupDetailUiState state) {
        if (renameDialog == null) {
            renameInput = new EditText(requireContext());
            renameInput.setHint(R.string.group_name_label);
            renameInput.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                    if (!renderingRename) {
                        viewModel.onRenameInputChange(s.toString());
                    }
                }
                @Override public void afterTextChanged(Editable s) {}
            });
            renameDialog = new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.action_rename_group)
                    .setView(renameInput)
                    .setNegativeButton(R.string.action_cancel, (d, w) -> viewModel.dismissRename())
                    .setPositiveButton(R.string.action_save, (d, w) -> viewModel.confirmRename())
                    .setOnDismissListener(d -> {
                        renameDialog = null;
                        renameInput = null;
                    })
                    .show();
        }
        if (renameInput != null) {
            renderingRename = true;
            if (!renameInput.getText().toString().equals(state.getRenameInput())) {
                renameInput.setText(state.getRenameInput());
            }
            renderingRename = false;
        }
    }

    private void showAddMemberDialog(@NonNull GroupDetailUiState state) {
        if (addMemberDialog != null && addMemberDialog.isShowing()) {
            if (addMemberPickerAdapter != null) {
                addMemberPickerAdapter.submit(state.getAddableContacts(), state.getSelectedAddIds());
            }
            if (addMemberConfirmButton != null) {
                addMemberConfirmButton.setEnabled(!state.getSelectedAddIds().isEmpty() && !state.isBusy());
            }
            return;
        }
        BottomSheetGroupAddMemberBinding sheet =
                BottomSheetGroupAddMemberBinding.inflate(LayoutInflater.from(requireContext()));
        addMemberPickerAdapter = new AddMemberAdapter(viewModel::toggleAddCandidate);
        addMemberPickerAdapter.submit(state.getAddableContacts(), state.getSelectedAddIds());
        sheet.addMemberList.setLayoutManager(new LinearLayoutManager(requireContext()));
        sheet.addMemberList.setAdapter(addMemberPickerAdapter);
        addMemberConfirmButton = sheet.addMemberConfirm;
        addMemberConfirmButton.setEnabled(!state.getSelectedAddIds().isEmpty() && !state.isBusy());
        addMemberConfirmButton.setOnClickListener(v -> viewModel.addSelectedMembers());

        BottomSheetDialog dialog = new BottomSheetDialog(requireContext());
        dialog.setContentView(sheet.getRoot());
        dialog.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
        dialog.getBehavior().setSkipCollapsed(true);
        dialog.setOnDismissListener(d -> {
            viewModel.dismissAddMember();
            addMemberDialog = null;
            addMemberPickerAdapter = null;
            addMemberConfirmButton = null;
        });
        addMemberDialog = dialog;
        dialog.show();
    }

    @Override
    public void onDestroyView() {
        if (renameDialog != null) {
            renameDialog.dismiss();
        }
        if (removeDialog != null) {
            removeDialog.dismiss();
        }
        if (leaveDialog != null) {
            leaveDialog.dismiss();
        }
        if (addMemberDialog != null) {
            addMemberDialog.dismiss();
            addMemberPickerAdapter = null;
            addMemberConfirmButton = null;
        }
        if (binding != null) {
            binding.memberList.setAdapter(null);
        }
        memberAdapter = null;
        super.onDestroyView();
        binding = null;
    }

    private static final class AddMemberAdapter extends ListAdapter<Contact, AddMemberAdapter.Holder> {

        interface ToggleListener {
            void onToggle(@NonNull String userId);
        }

        private final ToggleListener listener;
        private Set<String> selected = Set.of();

        AddMemberAdapter(@NonNull ToggleListener listener) {
            super(DIFF);
            this.listener = listener;
        }

        void submit(@NonNull java.util.List<Contact> contacts, @NonNull Set<String> selectedIds) {
            selected = selectedIds;
            submitList(contacts);
        }

        private static final DiffUtil.ItemCallback<Contact> DIFF = new DiffUtil.ItemCallback<Contact>() {
            @Override
            public boolean areItemsTheSame(@NonNull Contact oldItem, @NonNull Contact newItem) {
                return oldItem.getUserId().equals(newItem.getUserId());
            }

            @Override
            public boolean areContentsTheSame(@NonNull Contact oldItem, @NonNull Contact newItem) {
                return Objects.equals(oldItem, newItem);
            }
        };

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View row = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_user_search, parent, false);
            CheckBox box = row.findViewById(R.id.selectionCheck);
            box.setVisibility(View.VISIBLE);
            return new Holder(row, box);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            Contact contact = getItem(position);
            holder.bind(contact, selected.contains(contact.getUserId()), listener);
        }

        static final class Holder extends RecyclerView.ViewHolder {
            private final AvatarView avatar;
            private final TextView name;
            private final TextView subtitle;
            private final CheckBox box;

            Holder(@NonNull View itemView, @NonNull CheckBox box) {
                super(itemView);
                this.box = box;
                avatar = itemView.findViewById(R.id.avatar);
                name = itemView.findViewById(R.id.name);
                subtitle = itemView.findViewById(R.id.subtitle);
            }

            void bind(@NonNull Contact contact, boolean checked, @NonNull ToggleListener listener) {
                String label = contact.getPrimaryLabel();
                name.setText(label);
                subtitle.setText(contact.getPhone());
                avatar.setName(label);
                avatar.setAvatarUrl(contact.getAvatarUrl());
                box.setOnCheckedChangeListener(null);
                box.setChecked(checked);
                View.OnClickListener toggle = v -> listener.onToggle(contact.getUserId());
                itemView.setOnClickListener(toggle);
                box.setOnCheckedChangeListener((b, c) -> listener.onToggle(contact.getUserId()));
            }
        }
    }
}
