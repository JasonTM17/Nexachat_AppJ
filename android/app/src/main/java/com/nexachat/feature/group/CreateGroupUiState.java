package com.nexachat.feature.group;

import androidx.annotation.Nullable;
import com.nexachat.core.common.ApiError;
import com.nexachat.data.Contact;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Immutable create-group screen state. */
public final class CreateGroupUiState {

    private final String title;
    private final List<Contact> contacts;
    private final Set<String> selectedIds;
    private final boolean contactsLoaded;
    private final boolean loading;
    private final boolean creating;
    @Nullable private final ApiError error;

    public CreateGroupUiState() {
        this("", Collections.emptyList(), Collections.emptySet(), false, false, false, null);
    }

    public CreateGroupUiState(
            String title,
            List<Contact> contacts,
            Set<String> selectedIds,
            boolean contactsLoaded,
            boolean loading,
            boolean creating,
            @Nullable ApiError error) {
        this.title = Objects.requireNonNull(title, "title");
        this.contacts = List.copyOf(Objects.requireNonNull(contacts, "contacts"));
        this.selectedIds = Collections.unmodifiableSet(
                new LinkedHashSet<>(Objects.requireNonNull(selectedIds, "selectedIds")));
        this.contactsLoaded = contactsLoaded;
        this.loading = loading;
        this.creating = creating;
        this.error = error;
    }

    public String getTitle() {
        return title;
    }

    public List<Contact> getContacts() {
        return contacts;
    }

    public Set<String> getSelectedIds() {
        return selectedIds;
    }

    public boolean getContactsLoaded() {
        return contactsLoaded;
    }

    public boolean isContactsLoaded() {
        return contactsLoaded;
    }

    public boolean getLoading() {
        return loading;
    }

    public boolean isLoading() {
        return loading;
    }

    public boolean getCreating() {
        return creating;
    }

    public boolean isCreating() {
        return creating;
    }

    @Nullable
    public ApiError getError() {
        return error;
    }

    /** Create stays disabled until the name and at least one member exist. */
    public boolean getCanCreate() {
        return !title.isBlank() && !selectedIds.isEmpty() && !creating;
    }

    public boolean isCanCreate() {
        return getCanCreate();
    }

    public CreateGroupUiState withTitle(String nextTitle) {
        return new CreateGroupUiState(
                nextTitle, contacts, selectedIds, contactsLoaded, loading, creating, null);
    }

    public CreateGroupUiState withLoading(boolean nextLoading, @Nullable ApiError nextError) {
        return new CreateGroupUiState(
                title, contacts, selectedIds, contactsLoaded, nextLoading, creating, nextError);
    }

    public CreateGroupUiState withContacts(List<Contact> nextContacts, boolean loaded) {
        return new CreateGroupUiState(
                title, nextContacts, selectedIds, loaded, false, creating, error);
    }

    public CreateGroupUiState withContactsFailure(ApiError nextError) {
        return new CreateGroupUiState(
                title, contacts, selectedIds, true, false, creating, nextError);
    }

    public CreateGroupUiState withSelectedIds(Set<String> nextSelected) {
        return new CreateGroupUiState(
                title, contacts, nextSelected, contactsLoaded, loading, creating, error);
    }

    public CreateGroupUiState withCreating(boolean nextCreating, @Nullable ApiError nextError) {
        return new CreateGroupUiState(
                title, contacts, selectedIds, contactsLoaded, loading, nextCreating, nextError);
    }

    public CreateGroupUiState withError(@Nullable ApiError nextError) {
        return new CreateGroupUiState(
                title, contacts, selectedIds, contactsLoaded, loading, creating, nextError);
    }

    public CreateGroupUiState toggleMember(String userId) {
        Set<String> next = new LinkedHashSet<>(selectedIds);
        if (!next.add(userId)) {
            next.remove(userId);
        }
        return withSelectedIds(next);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof CreateGroupUiState that)) {
            return false;
        }
        return contactsLoaded == that.contactsLoaded
                && loading == that.loading
                && creating == that.creating
                && title.equals(that.title)
                && contacts.equals(that.contacts)
                && selectedIds.equals(that.selectedIds)
                && Objects.equals(error, that.error);
    }

    @Override
    public int hashCode() {
        return Objects.hash(title, contacts, selectedIds, contactsLoaded, loading, creating, error);
    }
}
