package com.nexachat.feature.group;

import androidx.annotation.Nullable;
import androidx.lifecycle.MutableLiveData;
import com.nexachat.data.Contact;
import com.nexachat.data.GroupMember;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Small pure helpers shared by the Java group ViewModels. */
final class GroupDetailViewModelSupport {

    static final long GROUP_PUSH_REFRESH_DEBOUNCE_MS = 200L;

    private GroupDetailViewModelSupport() { }

    static boolean isAdmin(List<GroupMember> members, @Nullable String myUserId) {
        if (myUserId == null) {
            return false;
        }
        for (GroupMember member : members) {
            if (myUserId.equals(member.getUserId()) && member.isAdmin()) {
                return true;
            }
        }
        return false;
    }

    static String titleOrCurrent(@Nullable String fetchedTitle, String currentTitle) {
        return fetchedTitle == null || fetchedTitle.isBlank() ? currentTitle : fetchedTitle;
    }

    static List<Contact> addableContacts(List<Contact> contacts, List<GroupMember> members) {
        Set<String> existingIds = new HashSet<>();
        for (GroupMember member : members) {
            existingIds.add(member.getUserId());
        }
        List<Contact> addable = new ArrayList<>();
        for (Contact contact : contacts) {
            if (!existingIds.contains(contact.getUserId())) {
                addable.add(contact);
            }
        }
        return List.copyOf(addable);
    }
}

/** LiveData event wrapper shared by the two group destinations. */
final class GroupSingleLiveEvent<T> extends MutableLiveData<GroupSingleLiveEvent.Event<T>> {
    private long sequence;

    void emit(T value) {
        setValue(new Event<>(++sequence, value));
    }

    static final class Event<T> {
        private final long sequence;
        private final T value;
        private final AtomicBoolean consumed = new AtomicBoolean();

        Event(long sequence, T value) {
            this.sequence = sequence;
            this.value = value;
        }

        long getSequence() {
            return sequence;
        }

        @Nullable
        T consume() {
            return consumed.compareAndSet(false, true) ? value : null;
        }
    }
}
