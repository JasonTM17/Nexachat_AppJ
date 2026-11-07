package com.nexachat.feature.group;

import androidx.annotation.Nullable;
import com.nexachat.core.common.ApiError;
import com.nexachat.data.Contact;
import com.nexachat.data.GroupMember;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Immutable group-detail screen state. */
public final class GroupDetailUiState {

    private final boolean loading;
    private final String title;
    private final List<GroupMember> members;
    @Nullable private final String myUserId;
    private final boolean iAmAdmin;
    @Nullable private final GroupMember pendingRemove;
    private final boolean confirmLeave;
    private final boolean showRename;
    private final String renameInput;
    private final boolean showAddPicker;
    private final List<Contact> addableContacts;
    private final Set<String> selectedAddIds;
    private final boolean busy;
    @Nullable private final ApiError error;
    @Nullable private final String groupAvatar;

    public GroupDetailUiState() {
        this(
                true,
                "",
                Collections.emptyList(),
                null,
                false,
                null,
                false,
                false,
                "",
                false,
                Collections.emptyList(),
                Collections.emptySet(),
                false,
                null,
                null);
    }

    public GroupDetailUiState(
            boolean loading,
            String title,
            List<GroupMember> members,
            @Nullable String myUserId,
            boolean iAmAdmin,
            @Nullable GroupMember pendingRemove,
            boolean confirmLeave,
            boolean showRename,
            String renameInput,
            boolean showAddPicker,
            List<Contact> addableContacts,
            Set<String> selectedAddIds,
            boolean busy,
            @Nullable ApiError error,
            @Nullable String groupAvatar) {
        this.loading = loading;
        this.title = Objects.requireNonNull(title, "title");
        this.members = List.copyOf(Objects.requireNonNull(members, "members"));
        this.myUserId = myUserId;
        this.iAmAdmin = iAmAdmin;
        this.pendingRemove = pendingRemove;
        this.confirmLeave = confirmLeave;
        this.showRename = showRename;
        this.renameInput = Objects.requireNonNull(renameInput, "renameInput");
        this.showAddPicker = showAddPicker;
        this.addableContacts = List.copyOf(Objects.requireNonNull(addableContacts, "addableContacts"));
        this.selectedAddIds = Collections.unmodifiableSet(
                new LinkedHashSet<>(Objects.requireNonNull(selectedAddIds, "selectedAddIds")));
        this.busy = busy;
        this.error = error;
        this.groupAvatar = groupAvatar;
    }

    public boolean isLoading() { return loading; }
    public boolean getLoading() { return loading; }
    public String getTitle() { return title; }
    public List<GroupMember> getMembers() { return members; }
    @Nullable public String getMyUserId() { return myUserId; }
    public boolean isIAmAdmin() { return iAmAdmin; }
    public boolean getIAmAdmin() { return iAmAdmin; }
    @Nullable public GroupMember getPendingRemove() { return pendingRemove; }
    public boolean isConfirmLeave() { return confirmLeave; }
    public boolean getConfirmLeave() { return confirmLeave; }
    public boolean isShowRename() { return showRename; }
    public boolean getShowRename() { return showRename; }
    public String getRenameInput() { return renameInput; }
    public boolean isShowAddPicker() { return showAddPicker; }
    public boolean getShowAddPicker() { return showAddPicker; }
    public List<Contact> getAddableContacts() { return addableContacts; }
    public Set<String> getSelectedAddIds() { return selectedAddIds; }
    public boolean isBusy() { return busy; }
    public boolean getBusy() { return busy; }
    @Nullable public ApiError getError() { return error; }
    @Nullable public String getGroupAvatar() { return groupAvatar; }

    public GroupDetailUiState withGroupAvatar(@Nullable String avatar) {
        return copy(loading, title, members, myUserId, iAmAdmin, pendingRemove, confirmLeave,
                showRename, renameInput, showAddPicker, addableContacts, selectedAddIds, busy, error, avatar);
    }

    public GroupDetailUiState withTitle(String nextTitle) {
        return copy(loading, nextTitle, members, myUserId, iAmAdmin, pendingRemove, confirmLeave,
                showRename, renameInput, showAddPicker, addableContacts, selectedAddIds, busy, error, groupAvatar);
    }

    public GroupDetailUiState withLoading(boolean nextLoading) {
        return copy(nextLoading, title, members, myUserId, iAmAdmin, pendingRemove, confirmLeave,
                showRename, renameInput, showAddPicker, addableContacts, selectedAddIds, busy, error, groupAvatar);
    }

    public GroupDetailUiState withMembers(
            String nextTitle,
            List<GroupMember> nextMembers,
            @Nullable String nextMyUserId,
            boolean nextAdmin) {
        return copy(false, nextTitle, nextMembers, nextMyUserId, nextAdmin, pendingRemove, confirmLeave,
                showRename, renameInput, showAddPicker, addableContacts, selectedAddIds, busy, error, groupAvatar);
    }

    public GroupDetailUiState withLoadFailure(ApiError nextError) {
        return copy(false, title, members, myUserId, iAmAdmin, pendingRemove, confirmLeave,
                showRename, renameInput, showAddPicker, addableContacts, selectedAddIds, busy, nextError, groupAvatar);
    }

    public GroupDetailUiState withError(@Nullable ApiError nextError) {
        return copy(loading, title, members, myUserId, iAmAdmin, pendingRemove, confirmLeave,
                showRename, renameInput, showAddPicker, addableContacts, selectedAddIds, busy, nextError, groupAvatar);
    }

    public GroupDetailUiState withAddPickerOpen() {
        return copy(loading, title, members, myUserId, iAmAdmin, pendingRemove, confirmLeave,
                showRename, renameInput, true, Collections.emptyList(), Collections.emptySet(), busy, error, groupAvatar);
    }

    public GroupDetailUiState withAddableContacts(List<Contact> contacts) {
        return copy(loading, title, members, myUserId, iAmAdmin, pendingRemove, confirmLeave,
                showRename, renameInput, showAddPicker, contacts, selectedAddIds, busy, error, groupAvatar);
    }

    public GroupDetailUiState withAddPickerClosedOnError(ApiError nextError) {
        return copy(loading, title, members, myUserId, iAmAdmin, pendingRemove, confirmLeave,
                showRename, renameInput, false, addableContacts, selectedAddIds, busy, nextError, groupAvatar);
    }

    public GroupDetailUiState withAddPickerDismissed() {
        return copy(loading, title, members, myUserId, iAmAdmin, pendingRemove, confirmLeave,
                showRename, renameInput, false, Collections.emptyList(), Collections.emptySet(), busy, error, groupAvatar);
    }

    public GroupDetailUiState toggleAddCandidate(String userId) {
        Set<String> next = new LinkedHashSet<>(selectedAddIds);
        if (!next.add(userId)) {
            next.remove(userId);
        }
        return copy(loading, title, members, myUserId, iAmAdmin, pendingRemove, confirmLeave,
                showRename, renameInput, showAddPicker, addableContacts, next, busy, error, groupAvatar);
    }

    public GroupDetailUiState withBusy(boolean nextBusy) {
        return copy(loading, title, members, myUserId, iAmAdmin, pendingRemove, confirmLeave,
                showRename, renameInput, showAddPicker, addableContacts, selectedAddIds, nextBusy, error, groupAvatar);
    }

    public GroupDetailUiState withMembersAdded() {
        return copy(loading, title, members, myUserId, iAmAdmin, pendingRemove, confirmLeave,
                showRename, renameInput, false, Collections.emptyList(), Collections.emptySet(), false, error, groupAvatar);
    }

    public GroupDetailUiState withBusyAndError(boolean nextBusy, ApiError nextError) {
        return copy(loading, title, members, myUserId, iAmAdmin, pendingRemove, confirmLeave,
                showRename, renameInput, showAddPicker, addableContacts, selectedAddIds, nextBusy, nextError, groupAvatar);
    }

    public GroupDetailUiState withPendingRemove(@Nullable GroupMember member) {
        return copy(loading, title, members, myUserId, iAmAdmin, member, confirmLeave,
                showRename, renameInput, showAddPicker, addableContacts, selectedAddIds, busy, error, groupAvatar);
    }

    public GroupDetailUiState withRemoveInFlight() {
        return copy(loading, title, members, myUserId, iAmAdmin, null, confirmLeave,
                showRename, renameInput, showAddPicker, addableContacts, selectedAddIds, true, error, groupAvatar);
    }

    public GroupDetailUiState withRenameOpen() {
        return copy(loading, title, members, myUserId, iAmAdmin, pendingRemove, confirmLeave,
                true, title, showAddPicker, addableContacts, selectedAddIds, busy, error, groupAvatar);
    }

    public GroupDetailUiState withRenameDismissed() {
        return copy(loading, title, members, myUserId, iAmAdmin, pendingRemove, confirmLeave,
                false, "", showAddPicker, addableContacts, selectedAddIds, busy, error, groupAvatar);
    }

    public GroupDetailUiState withRenameInput(String value) {
        return copy(loading, title, members, myUserId, iAmAdmin, pendingRemove, confirmLeave,
                showRename, value, showAddPicker, addableContacts, selectedAddIds, busy, error, groupAvatar);
    }

    public GroupDetailUiState withRenameSucceeded() {
        return copy(loading, title, members, myUserId, iAmAdmin, pendingRemove, confirmLeave,
                false, "", showAddPicker, addableContacts, selectedAddIds, false, error, groupAvatar);
    }

    public GroupDetailUiState withRenameFailed(ApiError nextError) {
        return copy(loading, title, members, myUserId, iAmAdmin, pendingRemove, confirmLeave,
                true, renameInput, showAddPicker, addableContacts, selectedAddIds, false, nextError, groupAvatar);
    }

    public GroupDetailUiState withLeaveInFlight() {
        return copy(loading, title, members, myUserId, iAmAdmin, pendingRemove, false,
                showRename, renameInput, showAddPicker, addableContacts, selectedAddIds, true, error, groupAvatar);
    }

    public GroupDetailUiState withConfirmLeave(boolean next) {
        return copy(loading, title, members, myUserId, iAmAdmin, pendingRemove, next,
                showRename, renameInput, showAddPicker, addableContacts, selectedAddIds, busy, error, groupAvatar);
    }

    private static GroupDetailUiState copy(
            boolean loading,
            String title,
            List<GroupMember> members,
            @Nullable String myUserId,
            boolean iAmAdmin,
            @Nullable GroupMember pendingRemove,
            boolean confirmLeave,
            boolean showRename,
            String renameInput,
            boolean showAddPicker,
            List<Contact> addableContacts,
            Set<String> selectedAddIds,
            boolean busy,
            @Nullable ApiError error,
            @Nullable String groupAvatar) {
        return new GroupDetailUiState(
                loading, title, members, myUserId, iAmAdmin, pendingRemove, confirmLeave,
                showRename, renameInput, showAddPicker, addableContacts, selectedAddIds,
                busy, error, groupAvatar);
    }
}
