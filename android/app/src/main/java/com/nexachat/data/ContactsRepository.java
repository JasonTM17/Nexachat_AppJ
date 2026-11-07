package com.nexachat.data;

import android.content.Context;
import android.net.Uri;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.nexachat.core.common.Result;
import com.nexachat.core.network.UserApi;
import com.nexachat.core.network.dto.UserDto;
import dagger.hilt.android.qualifiers.ApplicationContext;
import java.util.List;
import java.util.Objects;
import javax.inject.Inject;
import javax.inject.Singleton;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlinx.coroutines.flow.MutableStateFlow;
import kotlinx.coroutines.flow.StateFlow;

/**
 * Contacts and user-discovery boundary. Phones are normalized to E.164 before
 * the wire call. The shared contact cache is the single source for every screen.
 */
@Singleton
public final class ContactsRepository implements ContactsRepositoryApi {

    private final UserApi userApi;
    private final Context context;
    private final MutableStateFlow<List<Contact>> contactsCache;
    private final StateFlow<List<Contact>> contactsFlow;

    @Inject
    public ContactsRepository(
            @NonNull UserApi userApi, @ApplicationContext @NonNull Context context) {
        this.userApi = Objects.requireNonNull(userApi, "userApi");
        this.context = Objects.requireNonNull(context, "context");
        this.contactsCache = ContactsRepositoryFlow.newContactsCache();
        this.contactsFlow = contactsCache;
    }

    @NonNull
    @Override
    public StateFlow<List<Contact>> getContactsFlow() {
        return contactsFlow;
    }

    @Override
    public Object getMe(@NonNull Continuation<? super Result<UserDto>> completion) {
        return ContactsRepositoryFlow.getMe(userApi, completion);
    }

    @Override
    public Object uploadAvatar(
            @NonNull Uri uri, @NonNull Continuation<? super Result<UserDto>> completion) {
        return ContactsRepositoryFlow.uploadAvatar(userApi, context, uri, completion);
    }

    @Override
    public Object updateDisplayName(
            @NonNull String displayName,
            @NonNull Continuation<? super Result<UserDto>> completion) {
        return ContactsRepositoryFlow.updateDisplayName(userApi, displayName, completion);
    }

    @Override
    public Object searchByPhone(
            @NonNull String rawPhone,
            @NonNull Continuation<? super Result<List<UserDto>>> completion) {
        return ContactsRepositoryFlow.searchByPhone(userApi, rawPhone, completion);
    }

    @Override
    public Object searchByName(
            @NonNull String prefix,
            @NonNull Continuation<? super Result<List<UserDto>>> completion) {
        return ContactsRepositoryFlow.searchByName(userApi, prefix, completion);
    }

    @Override
    public Object contacts(
            @NonNull Continuation<? super Result<List<Contact>>> completion) {
        return ContactsRepositoryFlow.contacts(userApi, contactsCache, completion);
    }

    @Override
    public Object addContact(
            @NonNull String rawPhone, @NonNull Continuation<? super Result<Contact>> completion) {
        return ContactsRepositoryFlow.addContact(userApi, contactsCache, rawPhone, completion);
    }

    @Override
    public Object setAlias(
            @NonNull String userId,
            @Nullable String aliasName,
            @NonNull Continuation<? super Result<Contact>> completion) {
        return ContactsRepositoryFlow.setAlias(
                userApi, contactsCache, userId, aliasName, completion);
    }

    @Override
    public Object removeContact(
            @NonNull String userId, @NonNull Continuation<? super Result<Unit>> completion) {
        return ContactsRepositoryFlow.removeContact(userApi, contactsCache, userId, completion);
    }

    @Override
    public Object openDirectConversation(
            @Nullable String userId,
            @Nullable String phone,
            @NonNull Continuation<? super Result<String>> completion) {
        return ContactsRepositoryFlow.openDirectConversation(userApi, userId, phone, completion);
    }
}
