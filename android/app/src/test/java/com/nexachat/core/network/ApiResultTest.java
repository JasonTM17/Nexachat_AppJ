package com.nexachat.core.network;

import java.io.IOException;
import java.util.concurrent.CancellationException;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.jvm.functions.Function1;
import kotlin.jvm.functions.Function2;
import kotlinx.coroutines.BuildersKt;
import okhttp3.MediaType;
import okhttp3.ResponseBody;
import org.junit.Test;
import retrofit2.Response;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class ApiResultTest {

    @Test
    public void successfulResponseReturnsItsBody() throws InterruptedException {
        ApiResult<String> result = runApiCall(responseContinuation -> Response.success("payload"));

        assertEquals(new ApiResult.Success<>("payload"), result);
    }

    @Test
    public void unsuccessfulResponsePreservesStatusAndNestedErrorCode() throws InterruptedException {
        ResponseBody body = ResponseBody.create(
                MediaType.parse("application/json"),
                "{\"error\":{\"code\":\"RATE_LIMITED\"}}"
        );
        ApiResult<String> result = runApiCall(responseContinuation -> Response.error(429, body));

        assertEquals(new ApiResult.HttpError(429, "RATE_LIMITED"), result);
    }

    @Test
    public void ioFailureIsReturnedAsNetworkErrorWithOriginalCause() throws InterruptedException {
        IOException cause = new IOException("offline");
        ApiResult<String> result = runApiCall(responseContinuation -> sneakyThrow(cause));

        assertTrue(result instanceof ApiResult.NetworkError);
        assertSame(cause, ((ApiResult.NetworkError) result).getCause());
    }

    @Test
    public void cancellationIsPropagatedInsteadOfConvertedToNetworkError() throws InterruptedException {
        CancellationException cause = new CancellationException("cancelled");

        try {
            runApiCall(responseContinuation -> { throw cause; });
            fail("Cancellation must escape safeApiCall");
        } catch (CancellationException actual) {
            assertSame(cause, actual);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ApiResult<String> runApiCall(
            Function1<Continuation<? super Response<String>>, Object> apiCall
    ) throws InterruptedException {
        Function2 block = (scope, continuation) ->
                ApiResultKt.<String>safeApiCall(apiCall, (Continuation) continuation);
        return (ApiResult<String>) BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE, block);
    }

    @SuppressWarnings("unchecked")
    private static <T, E extends Throwable> T sneakyThrow(Throwable throwable) throws E {
        throw (E) throwable;
    }
}
