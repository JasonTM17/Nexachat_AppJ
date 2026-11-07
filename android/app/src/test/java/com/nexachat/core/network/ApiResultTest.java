package com.nexachat.core.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.concurrent.CancellationException;
import okhttp3.MediaType;
import okhttp3.ResponseBody;
import org.junit.Test;
import retrofit2.Call;
import retrofit2.Response;

public final class ApiResultTest {

    @Test
    public void successfulResponseReturnsItsBody() throws Exception {
        ApiResult<String> result = run(response -> Response.success("payload"));
        assertEquals(new ApiResult.Success<>("payload"), result);
    }

    @Test
    public void successfulEmptyResponseReturnsNullSuccess() throws Exception {
        ApiResult<Void> result = run(response -> Response.success(null));
        assertEquals(new ApiResult.Success<Void>(null), result);
    }

    @Test
    public void unsuccessfulResponsePreservesStatusAndNestedErrorCode() throws Exception {
        ResponseBody body = ResponseBody.create(
                MediaType.parse("application/json"),
                "{\"error\":{\"code\":\"RATE_LIMITED\"}}");
        ApiResult<String> result = run(response -> Response.error(429, body));
        assertEquals(new ApiResult.HttpError<>(429, "RATE_LIMITED"), result);
    }

    @Test
    public void ioFailureBecomesNetworkErrorWithOriginalCause() throws Exception {
        IOException cause = new IOException("offline");
        ApiResult<String> result = run(response -> { throw cause; });
        assertTrue(result instanceof ApiResult.NetworkError);
        assertSame(cause, ((ApiResult.NetworkError<String>) result).getCause());
    }

    @Test
    public void cancellationIsPropagated() throws Exception {
        CancellationException cause = new CancellationException("cancelled");
        try {
            run(response -> { throw cause; });
            fail("Cancellation must escape safeApiCall");
        } catch (CancellationException actual) {
            assertSame(cause, actual);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> ApiResult<T> run(Executor<T> executor) throws Exception {
        Call<T> call = mock(Call.class);
        when(call.clone()).thenReturn(call);
        when(call.execute()).thenAnswer(invocation -> executor.execute(call));
        return ApiResult.safeApiCall(call).blockingGet();
    }

    @FunctionalInterface
    private interface Executor<T> {
        Response<T> execute(Call<T> call) throws Exception;
    }
}
