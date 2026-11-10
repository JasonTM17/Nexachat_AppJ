package com.nexachat.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexachat.core.common.ApiError;
import com.nexachat.core.common.Result;
import com.nexachat.core.common.Unit;
import com.nexachat.core.network.UserApi;
import com.nexachat.core.network.dto.SetRecoveryEmailRequest;
import com.nexachat.core.network.dto.VerifyRecoveryEmailRequest;
import com.nexachat.core.network.dto.VerifyRecoveryEmailResponse;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import retrofit2.Call;
import retrofit2.Response;

public final class RecoveryEmailRepositoryTest {

    private final UserApi userApi = org.mockito.Mockito.mock(UserApi.class);

    @Test
    public void requestVerificationNormalizesEmail() {
        RecoveryEmailRepository repository = new RecoveryEmailRepository(userApi);
        when(userApi.requestRecoveryEmailVerification(any()))
                .thenReturn(successVoid(202));

        Result<Unit> result = repository.requestVerificationCode("  User@Example.COM ")
                .blockingGet();

        assertTrue(result instanceof Result.Success<?>);
        ArgumentCaptor<SetRecoveryEmailRequest> captor =
                ArgumentCaptor.forClass(SetRecoveryEmailRequest.class);
        verify(userApi).requestRecoveryEmailVerification(captor.capture());
        assertEquals("user@example.com", captor.getValue().getEmail());
    }

    @Test
    public void verifyMapsInvalidCodeToDomainError() {
        RecoveryEmailRepository repository = new RecoveryEmailRepository(userApi);
        when(userApi.verifyRecoveryEmail(any()))
                .thenReturn(errorCall(400, "{\"error\":{\"code\":\"INVALID_OR_EXPIRED_CODE\"}}"));

        Result<VerifyRecoveryEmailResponse> result =
                repository.verifyCode("123456").blockingGet();

        assertTrue(result instanceof Result.Failure<?>);
        assertEquals(
                ApiError.InvalidOrExpiredCode.INSTANCE,
                ((Result.Failure<?>) result).getError());
    }

    @Test
    public void requestMapsMailUnavailable() {
        RecoveryEmailRepository repository = new RecoveryEmailRepository(userApi);
        when(userApi.requestRecoveryEmailVerification(any()))
                .thenReturn(errorCall(503, "{\"error\":{\"code\":\"MAIL_UNAVAILABLE\"}}"));

        Result<Unit> result = repository.requestVerificationCode("a@b.c").blockingGet();

        assertTrue(result instanceof Result.Failure<?>);
        assertEquals(
                ApiError.MailUnavailable.INSTANCE,
                ((Result.Failure<?>) result).getError());
    }

    @Test
    public void removeEmailSucceedsOnNoContent() {
        RecoveryEmailRepository repository = new RecoveryEmailRepository(userApi);
        when(userApi.removeRecoveryEmail()).thenReturn(successVoid(204));

        Result<Void> result = repository.removeVerifiedEmail().blockingGet();

        assertTrue(result instanceof Result.Success<?>);
        verify(userApi).removeRecoveryEmail();
    }

    @Test
    public void verifyPostsTrimmedCode() {
        RecoveryEmailRepository repository = new RecoveryEmailRepository(userApi);
        when(userApi.verifyRecoveryEmail(any()))
                .thenReturn(success(
                        new VerifyRecoveryEmailResponse("user@b.c", true), 200));

        repository.verifyCode(" 654321 ").blockingGet();

        ArgumentCaptor<VerifyRecoveryEmailRequest> captor =
                ArgumentCaptor.forClass(VerifyRecoveryEmailRequest.class);
        verify(userApi).verifyRecoveryEmail(captor.capture());
        assertEquals("654321", captor.getValue().getCode());
    }

    private static Call<Void> successVoid(int code) {
        return immediate(Response.success(code, null));
    }

    private static <T> Call<T> success(T body, int code) {
        return immediate(Response.success(code, body));
    }

    private static <T> Call<T> errorCall(int code, String body) {
        return immediate(Response.error(
                code,
                okhttp3.ResponseBody.create(body, okhttp3.MediaType.get("application/json"))));
    }

    private static <T> Call<T> immediate(Response<T> response) {
        return new Call<>() {
            private boolean executed;

            @Override
            public Response<T> execute() {
                executed = true;
                return response;
            }

            @Override
            public void enqueue(retrofit2.Callback<T> callback) {
                executed = true;
                callback.onResponse(this, response);
            }

            @Override
            public boolean isExecuted() {
                return executed;
            }

            @Override
            public void cancel() {
            }

            @Override
            public boolean isCanceled() {
                return false;
            }

            @Override
            public Call<T> clone() {
                return this;
            }

            @Override
            public okhttp3.Request request() {
                return new okhttp3.Request.Builder().url("https://example.invalid/").build();
            }

            @Override
            public okio.Timeout timeout() {
                return okio.Timeout.NONE;
            }
        };
    }
}
