package com.nexachat.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import com.nexachat.core.common.Result;
import com.nexachat.core.network.UserApi;
import com.nexachat.core.network.dto.ContactDto;
import io.reactivex.rxjava3.subscribers.TestSubscriber;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import retrofit2.Call;
import retrofit2.Response;

public final class ContactsRepositoryTest {

    private final UserApi userApi = mock(UserApi.class);
    private final Context context = mock(Context.class);

    @Test
    public void searchByPhoneNormalizesToE164BeforeCallingTheApi() {
        ContactsRepository repository = new ContactsRepository(userApi, context);
        when(userApi.searchUsers(eq("+84901234567"), eq(null)))
                .thenReturn(successCall(List.of()));

        Result<List<com.nexachat.core.network.dto.UserDto>> result =
                repository.searchByPhone("0901234567").blockingGet();

        assertTrue(result instanceof Result.Success<?>);
        verify(userApi, times(1)).searchUsers("+84901234567", null);
    }

    @Test
    public void contactsUpdatesSharedFlowOnSuccessfulFetch() {
        ContactsRepository repository = new ContactsRepository(userApi, context);
        ContactDto dto = new ContactDto(
                null, "u-2", "+84901111111", "Lan Nguyen", null, null, null);
        when(userApi.getContacts()).thenReturn(successCall(List.of(dto)));

        TestSubscriber<Optional<List<Contact>>> observer =
                repository.getContactsFlow().take(2).test();

        Result<List<Contact>> loaded = repository.contacts().blockingGet();
        assertTrue(loaded instanceof Result.Success<?>);
        assertEquals(1, ((Result.Success<List<Contact>>) loaded).getValue().size());
        assertEquals("u-2", ((Result.Success<List<Contact>>) loaded).getValue().get(0).getUserId());

        observer.awaitDone(5, TimeUnit.SECONDS);
        observer.assertValueAt(1, optional -> optional.isPresent() && optional.get().size() == 1);
    }

    private static <T> Call<T> successCall(T body) {
        return immediate(Response.success(body));
    }

    private static <T> Call<T> immediate(Response<T> response) {
        return new Call<>() {
            private boolean executed;

            @Override public Response<T> execute() {
                executed = true;
                return response;
            }

            @Override public void enqueue(retrofit2.Callback<T> callback) {
                executed = true;
                callback.onResponse(this, response);
            }

            @Override public boolean isExecuted() { return executed; }
            @Override public void cancel() { }
            @Override public boolean isCanceled() { return false; }
            @Override public Call<T> clone() { return this; }
            @Override public okhttp3.Request request() {
                return new okhttp3.Request.Builder().url("https://example.invalid/").build();
            }
            @Override public okio.Timeout timeout() { return okio.Timeout.NONE; }
        };
    }
}
