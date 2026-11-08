package com.nexachat.feature.ask;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import okhttp3.Request;
import okhttp3.ResponseBody;
import okio.Timeout;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/** In-memory API and call double for ask tests. */
final class FakeAskApi implements AskApi {
    final List<FakeCall> calls = new ArrayList<>();
    String conversationId;
    AskRequest request;

    @Override
    public Call<AskResponse> ask(String id, AskRequest body) {
        conversationId = id;
        request = body;
        FakeCall call = new FakeCall();
        calls.add(call);
        return call;
    }

    FakeCall lastCall() {
        return calls.get(calls.size() - 1);
    }

    static final class FakeCall implements Call<AskResponse> {
        private Callback<AskResponse> callback;
        private boolean executed;
        private boolean canceled;

        @Override
        public void enqueue(Callback<AskResponse> value) {
            executed = true;
            callback = value;
        }

        void succeed(AskResponse body) {
            callback.onResponse(this, Response.success(body));
        }

        void fail(int status, String errorJson) {
            callback.onResponse(this, Response.error(
                    status, ResponseBody.create(null, errorJson)));
        }

        void fail(IOException failure) {
            callback.onFailure(this, failure);
        }

        @Override public Response<AskResponse> execute() { throw new UnsupportedOperationException(); }
        @Override public boolean isExecuted() { return executed; }
        @Override public void cancel() { canceled = true; }
        @Override public boolean isCanceled() { return canceled; }
        @Override public Call<AskResponse> clone() { return new FakeCall(); }
        @Override public Request request() {
            return new Request.Builder().url("http://localhost/").build();
        }
        @Override public Timeout timeout() { return Timeout.NONE; }
    }
}
