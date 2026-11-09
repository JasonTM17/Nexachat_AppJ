package com.nexachat.feature.invite;

import java.io.IOException;
import java.util.Optional;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.ResponseBody;
import okio.Timeout;
import org.junit.Test;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.http.GET;
import retrofit2.http.Headers;
import retrofit2.http.POST;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class RetrofitInviteRepositoryTest {
    private static final String TOKEN = "A".repeat(43);

    @Test
    public void missingActiveLinkAndOwnRequestMapToEmptyResults() {
        TestApi api = new TestApi();
        api.activeLink = FakeCall.error(404);
        api.myRequest = FakeCall.error(404);
        RetrofitInviteRepository repository = new RetrofitInviteRepository(api);

        assertEquals(Optional.empty(), repository.getActiveLink("group-1").blockingGet());
        assertEquals(Optional.empty(), repository.getMyJoinRequest("group-1").blockingGet());
    }

    @Test
    public void previewNotFoundUsesUniformUnavailableFailure() {
        TestApi api = new TestApi();
        api.preview = FakeCall.error(404);
        RetrofitInviteRepository repository = new RetrofitInviteRepository(api);

        try {
            repository.loadPreview(TOKEN).blockingGet();
            fail("Expected unavailable invite failure");
        } catch (InviteApiException failure) {
            assertEquals(InviteFailure.UNAVAILABLE, failure.getFailure());
            assertFalse(failure.toString().contains(TOKEN));
        }
    }

    @Test
    public void rateLimitAndTransportFailuresAreTypedWithoutRawDetails() {
        TestApi api = new TestApi();
        RetrofitInviteRepository repository = new RetrofitInviteRepository(api);

        api.preview = FakeCall.error(429);
        assertFailure(repository, InviteFailure.RATE_LIMITED);

        api.preview = FakeCall.failed(new IOException("request failed"));
        assertFailure(repository, InviteFailure.OFFLINE);
    }

    @Test
    public void tokenEndpointsUseExpectedPathsAndDisableHttpCaching() throws Exception {
        GET previewPath = InviteApi.class.getMethod("loadPreview", String.class)
                .getAnnotation(GET.class);
        Headers noStore = InviteApi.class.getMethod("loadPreview", String.class)
                .getAnnotation(Headers.class);
        POST joinPath = InviteApi.class.getMethod("join", String.class)
                .getAnnotation(POST.class);
        GET myRequestPath = InviteApi.class.getMethod("getMyJoinRequest", String.class)
                .getAnnotation(GET.class);

        assertEquals("api/v1/invite-links/{token}", previewPath.value());
        assertTrue(noStore.value()[0].contains("no-store"));
        assertEquals("api/v1/invite-links/{token}/join", joinPath.value());
        assertEquals("api/v1/conversations/{conversationId}/join-requests/me",
                myRequestPath.value());
    }

    private static void assertFailure(RetrofitInviteRepository repository, InviteFailure expected) {
        try {
            repository.loadPreview(TOKEN).blockingGet();
            fail("Expected typed invite failure");
        } catch (InviteApiException failure) {
            assertEquals(expected, failure.getFailure());
        }
    }

    private static final class TestApi implements InviteApi {
        Call<InviteLinkResponse> activeLink = FakeCall.success(FakeInviteRepository.link(null, false));
        Call<InvitePreviewResponse> preview = FakeCall.success(FakeInviteRepository.preview(false, false));
        Call<JoinRequestResponse> myRequest = FakeCall.success(FakeInviteRepository.request("PENDING"));

        @Override public Call<InviteLinkResponse> createOrRotateLink(String id, InviteLinkRequest body) {
            return FakeCall.success(FakeInviteRepository.link(TOKEN, false));
        }
        @Override public Call<InviteLinkResponse> getActiveLink(String id) { return activeLink; }
        @Override public Call<InviteLinkResponse> updateApproval(String id, InviteApprovalRequest body) {
            return FakeCall.success(FakeInviteRepository.link(null, body.requiresApproval));
        }
        @Override public Call<Void> revokeLink(String id) { return FakeCall.success(null); }
        @Override public Call<java.util.List<JoinRequestResponse>> listJoinRequests(String id, String status) {
            return FakeCall.success(java.util.List.of());
        }
        @Override public Call<JoinRequestResponse> getMyJoinRequest(String id) { return myRequest; }
        @Override public Call<JoinRequestResponse> approveJoinRequest(String id, String requestId) {
            return FakeCall.success(FakeInviteRepository.request("APPROVED"));
        }
        @Override public Call<JoinRequestResponse> rejectJoinRequest(String id, String requestId) {
            return FakeCall.success(FakeInviteRepository.request("REJECTED"));
        }
        @Override public Call<InvitePreviewResponse> loadPreview(String token) { return preview; }
        @Override public Call<JoinResponse> join(String token) {
            return FakeCall.success(FakeInviteRepository.joinResult("JOINED", "group-1"));
        }
    }

    private static final class FakeCall<T> implements Call<T> {
        private final Response<T> response;
        private final IOException failure;
        private boolean canceled;

        private FakeCall(Response<T> response, IOException failure) {
            this.response = response;
            this.failure = failure;
        }

        static <T> FakeCall<T> success(T body) {
            return new FakeCall<>(Response.success(body), null);
        }

        static <T> FakeCall<T> error(int status) {
            return new FakeCall<>(Response.error(status,
                    ResponseBody.create(MediaType.get("text/plain"), "error")), null);
        }

        static <T> FakeCall<T> failed(IOException failure) {
            return new FakeCall<>(null, failure);
        }

        @Override public Response<T> execute() throws IOException {
            if (failure != null) throw failure;
            return response;
        }
        @Override public void enqueue(Callback<T> callback) {
            if (failure != null) callback.onFailure(this, failure);
            else callback.onResponse(this, response);
        }
        @Override public boolean isExecuted() { return true; }
        @Override public void cancel() { canceled = true; }
        @Override public boolean isCanceled() { return canceled; }
        @Override public Call<T> clone() { return new FakeCall<>(response, failure); }
        @Override public Request request() {
            return new Request.Builder().url("https://nexachat.app/").build();
        }
        @Override public Timeout timeout() { return Timeout.NONE; }
    }
}
