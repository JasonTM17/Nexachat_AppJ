package com.nexachat.feature.group;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.nexachat.R;
import com.nexachat.core.common.ApiError;
import com.nexachat.core.ui.GroupDetailListRules;
import com.nexachat.data.GroupMember;
import com.nexachat.databinding.FragmentGroupDetailBinding;
import com.nexachat.feature.group.adapter.GroupMemberAdapter;
import com.nexachat.feature.inbox.ConversationPreferenceMenu;
import com.nexachat.feature.ui.FormDialogs;
import dagger.hilt.android.AndroidEntryPoint;
import java.util.Objects;

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
    @Nullable private AlertDialog onlyAdminDialog;
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
        renameItem.getIcon().mutate().setTint(MaterialColors.getColor(
                binding.toolbar, com.google.android.material.R.attr.colorOnSurfaceVariant));
        renameItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        binding.toolbar.setOnMenuItemClickListener(this::onMenuItem);
        binding.addMemberButton.setOnClickListener(v -> viewModel.openAddMember());
        binding.inviteLinkButton.setOnClickListener(v -> openInviteManager());
        binding.leaveGroupButton.setOnClickListener(v -> onLeaveClicked());
        binding.muteTile.setOnClickListener(v -> onMuteClicked());
        binding.errorState.setOnRetry(viewModel::loadMembers);
        binding.emptyState.setMessage(getString(R.string.group_members_empty));
        binding.emptyState.setIcon(R.drawable.ic_person_outline);
        viewModel.getUiState()
                .observe(getViewLifecycleOwner(), this::render);
        viewModel.getEvents()
                .observe(getViewLifecycleOwner(), raw -> {
                    GroupDetailEvent event = raw == null ? null : raw.consume();
                    if (event instanceof GroupDetailEvent.ExitToInbox) {
                        GroupEntryExitNavigation.leave(NavHostFragment.findNavController(this));
                    } else if (event instanceof GroupDetailEvent.PreferenceFeedback feedback) {
                        showPreferenceFeedback(feedback);
                    }
                });
    }

    void openInviteManager() {
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
        boolean isAdmin = state.isIAmAdmin();
        if (isAdmin) {
            binding.groupAvatar.setOnClickListener(v -> avatarPicker.launch("image/*"));
            binding.groupTitle.setOnClickListener(v -> viewModel.openRename());
            binding.groupAvatar.setContentDescription(getString(R.string.group_change_avatar));
            binding.groupTitle.setContentDescription(getString(R.string.action_rename_group));
        } else {
            binding.groupAvatar.setOnClickListener(null);
            binding.groupTitle.setOnClickListener(null);
            binding.groupAvatar.setContentDescription(title);
            binding.groupTitle.setContentDescription(null);
        }
        binding.groupAvatar.setClickable(state.isIAmAdmin());
        binding.groupAvatar.setFocusable(isAdmin);
        binding.groupTitle.setClickable(state.isIAmAdmin());
        binding.groupTitle.setFocusable(isAdmin);
        binding.avatarEditBadge.setVisibility(isAdmin ? View.VISIBLE : View.GONE);
        memberAdapter.setShowRemove(state.isIAmAdmin());
        memberAdapter.submitList(state.getMembers());
        binding.groupSubtitle.setText(com.nexachat.res.QuantityStrings.memberCount(
                getResources(), state.getMembers().size()));
        binding.membersHeader.setTitle(getString(R.string.group_members_label));
        binding.addMemberButton.setVisibility(state.isIAmAdmin() ? View.VISIBLE : View.GONE);
        binding.inviteLinkButton.setVisibility(state.isIAmAdmin() ? View.VISIBLE : View.GONE);
        binding.toolbar.getMenu().findItem(MENU_RENAME).setVisible(state.isIAmAdmin());
        binding.leaveGroupButton.setEnabled(!state.isBusy());

        boolean muted = mutedNow(state);
        binding.muteIcon.setImageResource(
                muted ? R.drawable.ic_notifications_off : R.drawable.ic_notification);
        binding.muteText.setText(muted ? R.string.unmute : R.string.mute_menu_title);
        binding.muteTile.setContentDescription(
                getString(muted ? R.string.unmute : R.string.mute_menu_title));

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

        AddMemberSheetFragment addMemberSheet = addMemberSheet();
        if (state.isShowAddPicker()) {
            if (addMemberSheet == null && !getChildFragmentManager().isStateSaved()) {
                new AddMemberSheetFragment()
                        .show(getChildFragmentManager(), AddMemberSheetFragment.TAG);
            }
        } else if (addMemberSheet != null) {
            addMemberSheet.dismiss();
        }

        binding.loadingState.setVisibility(state.isLoading() || state.isBusy() ? View.VISIBLE : View.GONE);
    }

    private static boolean mutedNow(@NonNull GroupDetailUiState state) {
        return GroupDetailViewModelSupport.isMutedAt(
                state.isMuted(), state.getMutedUntilEpochMs(), System.currentTimeMillis());
    }

    private void onMuteClicked() {
        GroupDetailUiState state = viewModel.getUiState().getValue();
        if (state != null && mutedNow(state)) {
            viewModel.unmuteConversation();
            return;
        }
        ConversationPreferenceMenu.showMutePresets(
                requireContext(), false, preferenceListener());
    }

    private ConversationPreferenceMenu.Listener preferenceListener() {
        return new ConversationPreferenceMenu.Listener() {
            @Override
            public void onMute(@Nullable java.time.Instant until) {
                viewModel.muteConversation(until);
            }

            @Override
            public void onUnmute() {
                viewModel.unmuteConversation();
            }

            @Override
            public void onArchive(boolean archive) {}
        };
    }

    private void showPreferenceFeedback(@NonNull GroupDetailEvent.PreferenceFeedback feedback) {
        if (binding == null) {
            return;
        }
        Snackbar snackbar = Snackbar.make(
                binding.getRoot(),
                feedback.isMuted() ? R.string.conversation_muted : R.string.conversation_unmuted,
                Snackbar.LENGTH_LONG);
        Runnable undo = feedback.getUndo();
        if (undo != null) {
            snackbar.setAction(R.string.action_undo, ignored -> undo.run());
        }
        snackbar.show();
    }

    private void showRemoveDialog(@NonNull GroupMember member) {
        if (removeDialog != null && removeDialog.isShowing()) {
            return;
        }
        removeDialog = FormDialogs.markDestructive(
                new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.action_remove_member)
                .setMessage(getString(R.string.group_remove_member_confirm, member.getDisplayName()))
                .setNegativeButton(R.string.action_cancel, (d, w) -> viewModel.dismissRemoveMember())
                .setPositiveButton(R.string.action_remove, (d, w) -> viewModel.confirmRemoveMember())
                .setOnDismissListener(d -> removeDialog = null)
                .show());
    }

    private void onLeaveClicked() {
        int explanation = viewModel.onlyAdminLeaveExplanation();
        if (explanation != 0) {
            showOnlyAdminLeaveDialog(explanation);
            return;
        }
        viewModel.requestLeave();
    }

    private void showOnlyAdminLeaveDialog(int message) {
        if (onlyAdminDialog != null && onlyAdminDialog.isShowing()) {
            return;
        }
        onlyAdminDialog = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.action_leave_group)
                .setMessage(message)
                .setPositiveButton(R.string.action_close, null)
                .setOnDismissListener(dialog -> onlyAdminDialog = null)
                .show();
    }

    private void showLeaveDialog() {
        if (leaveDialog != null && leaveDialog.isShowing()) {
            return;
        }
        leaveDialog = FormDialogs.markDestructive(
                new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.action_leave_group)
                .setMessage(R.string.group_leave_confirm)
                .setNegativeButton(R.string.action_cancel, (d, w) -> viewModel.dismissLeave())
                .setPositiveButton(R.string.action_leave_group, (d, w) -> viewModel.confirmLeave())
                .setOnDismissListener(d -> leaveDialog = null)
                .show());
    }

    private void showRenameDialog(@NonNull GroupDetailUiState state) {
        if (renameDialog == null) {
            // Same outlined field + dialog padding as every other form dialog.
            TextInputLayout inputLayout = new TextInputLayout(
                    requireContext(),
                    null,
                    com.google.android.material.R.attr.textInputOutlinedStyle);
            float radius = getResources().getDimension(R.dimen.corner_md);
            inputLayout.setBoxCornerRadii(radius, radius, radius, radius);
            inputLayout.setHint(getString(R.string.group_name_label));
            renameInput = new TextInputEditText(inputLayout.getContext());
            renameInput.setSingleLine(true);
            renameInput.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                    if (!renderingRename) {
                        viewModel.onRenameInputChange(s.toString());
                    }
                }
                @Override public void afterTextChanged(Editable s) {}
            });
            inputLayout.addView(renameInput);
            LinearLayout container = new LinearLayout(requireContext());
            int pad = getResources().getDimensionPixelSize(R.dimen.dialog_padding);
            container.setPadding(pad, 0, pad, 0);
            container.addView(inputLayout);
            renameDialog = new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.action_rename_group)
                    .setView(container)
                    .setNegativeButton(R.string.action_cancel, (d, w) -> viewModel.dismissRename())
                    .setPositiveButton(R.string.action_save, (d, w) -> viewModel.confirmRename())
                    .setOnDismissListener(d -> {
                        renameDialog = null;
                        renameInput = null;
                    })
                    .show();
            FormDialogs.resizeAboveIme(renameDialog);
        }
        if (renameInput != null) {
            renderingRename = true;
            if (!renameInput.getText().toString().equals(state.getRenameInput())) {
                renameInput.setText(state.getRenameInput());
            }
            renderingRename = false;
        }
    }

    @Nullable
    private AddMemberSheetFragment addMemberSheet() {
        Fragment sheet = getChildFragmentManager().findFragmentByTag(AddMemberSheetFragment.TAG);
        return sheet instanceof AddMemberSheetFragment ? (AddMemberSheetFragment) sheet : null;
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
        if (onlyAdminDialog != null) {
            onlyAdminDialog.dismiss();
        }
        if (binding != null) {
            binding.memberList.setAdapter(null);
        }
        memberAdapter = null;
        super.onDestroyView();
        binding = null;
    }
}
