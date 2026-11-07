package com.nexachat.data;

import android.content.Context;
import android.net.Uri;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.nexachat.core.common.ApiError;
import com.nexachat.core.common.PhoneNormalizer;
import com.nexachat.core.common.Result;
import com.nexachat.core.network.ApiResult;
import com.nexachat.core.network.UserApi;
import com.nexachat.core.network.dto.AddContactRequest;
import com.nexachat.core.network.dto.ContactDto;
import com.nexachat.core.network.dto.DirectConversationRequest;
import com.nexachat.core.network.dto.UpdateContactRequest;
import com.nexachat.core.network.dto.UpdateProfileRequest;
import com.nexachat.core.network.dto.UserDto;
import dagger.hilt.android.qualifiers.ApplicationContext;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.processors.BehaviorProcessor;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import javax.inject.Inject;
import javax.inject.Singleton;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import retrofit2.Call;

/**
 * Contacts and user-discovery boundary: speaks Retrofit to {@code /users},
 * {@code /contacts}, and {@code /conversations/direct}, and translates the
 * server error envelope into {@link ApiError} values.
 *
 * <p>Phones are normalized to E.164 (region VN) best-effort before hitting the
 * wire. The server revalidates, so a parse failure still sends the raw input.
 */
@Singleton
public final class ContactsRepository {

    private static final int MAX_AVATAR_BYTES = 25 * 1024 * 1024;
    private static final String ERROR_UNEXPECTED_EMPTY_BODY = "UNEXPECTED_EMPTY_BODY";

    private final UserApi userApi;
    private final Context context;
    private final Object contactsLock = new Object();
    private final BehaviorProcessor<Optional<List<Contact>>> contacts =
            BehaviorProcessor.createDefault(Optional.empty());

    @Inject
    public ContactsRepository(
            @NonNull UserApi userApi,
            @ApplicationContext @NonNull Context context) {
        this.userApi = userApi;
        this.context = context;
    }

    /**
     * Shared, mutation-aware contact cache. {@link Optional#empty()} means
     * the first contacts fetch has not completed; a present empty list means
     * it completed and the account has no contacts.
     */
    @NonNull
    public Flowable<Optional<List<Contact>>> getContactsFlow() {
        return contacts.hide();
    }

    @NonNull
    /** Returns the current account profile. */
    public Single<Result<UserDto>> getMe() {
        return call(userApi.getMe());
    }

    /**
     * Avatar upload (FR-A5): streams the picked image as multipart {@code file},
     * then re-fetches the user record so callers see the persisted avatar URL.
     * Reads are capped at 25 MiB and run on IO.
     */
    @NonNull
    public Single<Result<UserDto>> uploadAvatar(@NonNull Uri uri) {
        return Single.fromCallable(() -> readBoundedAvatar(uri))
                .subscribeOn(Schedulers.io())
                .flatMap(bytes -> {
                    if (bytes == null) {
                        return Single.<Result<UserDto>>just(
                                Result.failure(new ApiError.Network("avatar read failed")));
                    }
                    String mime = "image/jpeg";
                    try {
                        String resolved = context.getContentResolver().getType(uri);
                        if (resolved != null) mime = resolved;
                    } catch (RuntimeException ignored) {
                        // Document providers are not required to report a MIME type.
                    }
                    String name = uri.getLastPathSegment() != null
                            ? uri.getLastPathSegment()
                            : "avatar";
                    MultipartBody.Part part = MultipartBody.Part.createFormData(
                            "file",
                            name,
                            RequestBody.create(okhttp3.MediaType.parse(mime), bytes));
                    return call(userApi.uploadAvatar(part)).flatMap(uploaded -> {
                        if (uploaded instanceof Result.Success<?>) return call(userApi.getMe());
                        return Single.just(Result.cast(uploaded));
                    });
                });
    }

    @NonNull
    public Single<Result<UserDto>> updateDisplayName(@NonNull String displayName) {
        return call(userApi.updateMe(new UpdateProfileRequest(displayName.trim())));
    }

    @NonNull
    public Single<Result<List<UserDto>>> searchByPhone(@NonNull String rawPhone) {
        return call(userApi.searchUsers(PhoneNormalizer.toE164(rawPhone), null));
    }

    @NonNull
    public Single<Result<List<UserDto>>> searchByName(@NonNull String prefix) {
        return call(userApi.searchUsers(null, prefix.trim()));
    }

    @NonNull
    /** Fetches the owner's contacts and updates the shared mutation-aware cache on success. */
    public Single<Result<List<Contact>>> contacts() {
        return call(userApi.getContacts()).map(result -> {
            if (result instanceof Result.Success<?> success) {
                @SuppressWarnings("unchecked")
                List<ContactDto> response = (List<ContactDto>) success.getValue();
                List<Contact> mapped = new ArrayList<>();
                if (response != null) {
                    for (ContactDto dto : response) {
                        Contact contact = toContact(dto);
                        if (contact != null) mapped.add(contact);
                    }
                }
                List<Contact> immutable = java.util.Collections.unmodifiableList(mapped);
                setContacts(immutable);
                return new Result.Success<List<Contact>>(immutable);
            }
            return Result.cast(result);
        });
    }

    /** Adds a contact; server duplicate codes are mapped to {@link ApiError.PhoneExists}. */
    @NonNull
    public Single<Result<Contact>> addContact(@NonNull String rawPhone) {
        return call(
                userApi.addContact(new AddContactRequest(PhoneNormalizer.toE164(rawPhone))),
                this::contactWriteError)
                .map(result -> {
                    if (result instanceof Result.Success<?> success) {
                        ContactDto dto = ((com.nexachat.core.network.dto.ContactEnvelopeDto)
                                success.getValue()).unwrap();
                        Contact contact = toContact(dto);
                        if (contact == null) {
                            return Result.<Contact>failure(new ApiError.Unknown("UNEXPECTED_SHAPE"));
                        }
                        updateContacts(list -> {
                            List<Contact> next = new ArrayList<>();
                            next.add(contact);
                            next.addAll(list);
                            return distinctByUserId(next);
                        }, true);
                        return new Result.Success<>(contact);
                    }
                    return Result.cast(result);
                });
    }

    @NonNull
    /** Saves the owner-chosen contact alias and patches a loaded shared cache. */
    public Single<Result<Contact>> setAlias(@NonNull String userId, @Nullable String aliasName) {
        return call(userApi.updateContact(userId, new UpdateContactRequest(aliasName)))
                .map(result -> {
                    if (result instanceof Result.Success<?> success) {
                        ContactDto dto = ((com.nexachat.core.network.dto.ContactEnvelopeDto)
                                success.getValue()).unwrap();
                        Contact updated = toContact(dto);
                        if (updated == null) {
                            return Result.<Contact>failure(new ApiError.Unknown("UNEXPECTED_SHAPE"));
                        }
                        updateContacts(list -> {
                            List<Contact> next = new ArrayList<>(list.size());
                            for (Contact item : list) {
                                next.add(item.getUserId().equals(updated.getUserId()) ? updated : item);
                            }
                            return next;
                        });
                        return new Result.Success<>(updated);
                    }
                    return Result.cast(result);
                });
    }

    /** Removes a contact and patches the shared cache only after server success. */
    @NonNull
    public Single<Result<Void>> removeContact(@NonNull String userId) {
        return callVoid(userApi.removeContact(userId)).map(result -> {
            if (result instanceof Result.Success<?>) {
                updateContacts(list -> {
                    List<Contact> next = new ArrayList<>();
                    for (Contact item : list) {
                        if (!item.getUserId().equals(userId)) next.add(item);
                    }
                    return next;
                });
                return successVoid();
            }
            return Result.cast(result);
        });
    }

    /**
     * Opens or creates a direct conversation. The server enforces idempotency
     * through {@code direct_key}, so repeats return the same conversation id.
     *
     * <p>The request is XOR: a known user id wins and the phone is omitted;
     * sending both is a server-side 400.
     */
    @NonNull
    public Single<Result<String>> openDirectConversation(
            @Nullable String userId,
            @Nullable String phone) {
        String normalizedPhone = userId == null && phone != null
                ? PhoneNormalizer.toE164(phone)
                : null;
        return call(userApi.openDirectConversation(
                new DirectConversationRequest(userId, normalizedPhone)))
                .map(result -> {
                    if (result instanceof Result.Success<?> success) {
                        com.nexachat.core.network.dto.DirectConversationResponse response =
                                (com.nexachat.core.network.dto.DirectConversationResponse)
                                        success.getValue();
                        return new Result.Success<>(response.getId());
                    }
                    return Result.cast(result);
                });
    }

    @Nullable
    private Contact toContact(@Nullable ContactDto dto) {
        if (dto == null || dto.getResolvedUserId() == null) return null;
        String phone = dto.getPhone() != null ? dto.getPhone() : "";
        String displayName = dto.getDisplayName() != null ? dto.getDisplayName() : phone;
        return new Contact(
                dto.getResolvedUserId(),
                phone,
                displayName,
                dto.getAliasName(),
                dto.getAvatarUrl(),
                dto.getCreatedAt());
    }

    /** Maps contact-write duplicate codes before the generic API error table. */
    private ApiError contactWriteError(int httpStatus, @Nullable String serverCode) {
        if ("ALREADY_CONTACT".equals(serverCode) || "PHONE_EXISTS".equals(serverCode)) {
            return ApiError.PhoneExists.INSTANCE;
        }
        return ApiError.from(httpStatus, serverCode);
    }

    private <T> Single<Result<T>> call(@NonNull Call<T> retrofitCall) {
        return call(retrofitCall, ApiError::from);
    }

    /**
     * Runs the shared Retrofit Call adapter and translates transport outcomes.
     * An erased empty-body cast is converted into a domain error rather than a crash.
     */
    private <T> Single<Result<T>> call(
            @NonNull Call<T> retrofitCall,
            @NonNull BiFunction<Integer, String, ApiError> mapError) {
        return ApiResult.safeApiCall(retrofitCall)
                .onErrorResumeNext(error -> {
                    if (error instanceof ClassCastException) {
                        return Single.just(new ApiResult.HttpError<T>(200, ERROR_UNEXPECTED_EMPTY_BODY));
                    }
                    return Single.error(error);
                })
                .subscribeOn(Schedulers.io())
                .map(result -> {
                    if (result instanceof ApiResult.Success<?> success) {
                        @SuppressWarnings("unchecked")
                        T body = (T) success.getBody();
                        if (body == null || body instanceof com.nexachat.core.common.Unit) {
                            return Result.<T>failure(
                                    new ApiError.Unknown(ERROR_UNEXPECTED_EMPTY_BODY));
                        }
                        return new Result.Success<>(body);
                    }
                    if (result instanceof ApiResult.HttpError<?> httpError) {
                        return Result.<T>failure(mapError.apply(
                                httpError.getStatus(), httpError.getErrorCode()));
                    }
                    ApiResult.NetworkError<?> networkError = (ApiResult.NetworkError<?>) result;
                    return Result.<T>failure(
                            new ApiError.Network(networkError.getCause().getMessage()));
                });
    }

    /** Empty successful response body is expected for DELETE contact. */
    private Single<Result<Void>> callVoid(@NonNull Call<?> retrofitCall) {
        return ApiResult.safeApiCall(retrofitCall)
                .subscribeOn(Schedulers.io())
                .map(result -> {
                    if (result instanceof ApiResult.Success<?>) return successVoid();
                    if (result instanceof ApiResult.HttpError<?> httpError) {
                        return Result.<Void>failure(ApiError.from(
                                httpError.getStatus(), httpError.getErrorCode()));
                    }
                    ApiResult.NetworkError<?> networkError = (ApiResult.NetworkError<?>) result;
                    return Result.<Void>failure(
                            new ApiError.Network(networkError.getCause().getMessage()));
                });
    }

    private byte[] readBoundedAvatar(@NonNull Uri uri) {
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) return null;
            byte[] buffer = new byte[MAX_AVATAR_BYTES + 1];
            int total = 0;
            int read;
            while (total <= MAX_AVATAR_BYTES
                    && (read = input.read(buffer, total, MAX_AVATAR_BYTES + 1 - total)) > 0) {
                total += read;
            }
            return total > MAX_AVATAR_BYTES ? null : java.util.Arrays.copyOf(buffer, total);
        } catch (IOException ignored) {
            return null;
        }
    }

    private void setContacts(@NonNull List<Contact> next) {
        synchronized (contactsLock) {
            contacts.onNext(Optional.of(java.util.Collections.unmodifiableList(new ArrayList<>(next))));
        }
    }

    private void updateContacts(@NonNull java.util.function.UnaryOperator<List<Contact>> update) {
        updateContacts(update, false);
    }

    private void updateContacts(
            @NonNull java.util.function.UnaryOperator<List<Contact>> update,
            boolean initializeIfAbsent) {
        synchronized (contactsLock) {
            Optional<List<Contact>> current = contacts.getValue();
            if (current == null || (current.isEmpty() && !initializeIfAbsent)) return;
            List<Contact> currentList = current.isPresent()
                    ? current.get()
                    : java.util.Collections.emptyList();
            List<Contact> next = update.apply(currentList);
            contacts.onNext(Optional.of(java.util.Collections.unmodifiableList(new ArrayList<>(next))));
        }
    }

    private List<Contact> distinctByUserId(@NonNull List<Contact> input) {
        java.util.LinkedHashMap<String, Contact> unique = new java.util.LinkedHashMap<>();
        for (Contact contact : input) unique.putIfAbsent(contact.getUserId(), contact);
        return new ArrayList<>(unique.values());
    }

    private static Result<Void> successVoid() {
        return new Result.Success<>(null);
    }
}
