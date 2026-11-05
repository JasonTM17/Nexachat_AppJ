package com.nexachat.call.core;

import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import retrofit2.Response;
import retrofit2.http.GET;
import retrofit2.http.Path;
import retrofit2.http.Query;

/** Authenticated REST access to ICE settings and server call history. */
public final class CallRepository {
    public interface Api {
        @GET("api/v1/calls/ice-config")
        retrofit2.Call<Map<String, Object>> getIceConfig();

        @GET("api/v1/conversations/{id}/calls")
        retrofit2.Call<Map<String, Object>> getCallHistory(
                @Path("id") String conversationId,
                @Query("limit") int limit,
                @Query("cursor") String cursor);
    }

    public static final class Result<T> {
        private final T value;
        private final CallError error;

        private Result(T value, CallError error) {
            this.value = value;
            this.error = error;
        }

        public static <T> Result<T> success(T value) {
            return new Result<>(value, null);
        }

        public static <T> Result<T> failure(CallError error) {
            return new Result<>(null, error == null ? new CallError(CallError.Code.UNKNOWN) : error);
        }

        public boolean isSuccess() { return error == null; }
        public T getValue() { return value; }
        public CallError getError() { return error; }
    }

    public static final class CallRecord {
        private final String callId;
        private final String callerId;
        private final String calleeId;
        private final String state;
        private final CallEndReason endReason;
        private final String startedAt;
        private final String answeredAt;
        private final String endedAt;
        private final long durationSeconds;

        CallRecord(
                String callId,
                String callerId,
                String calleeId,
                String state,
                CallEndReason endReason,
                String startedAt,
                String answeredAt,
                String endedAt,
                long durationSeconds) {
            this.callId = callId;
            this.callerId = callerId;
            this.calleeId = calleeId;
            this.state = state;
            this.endReason = endReason;
            this.startedAt = startedAt;
            this.answeredAt = answeredAt;
            this.endedAt = endedAt;
            this.durationSeconds = durationSeconds;
        }

        public String getCallId() { return callId; }
        public String getCallerId() { return callerId; }
        public String getCalleeId() { return calleeId; }
        public String getState() { return state; }
        public CallEndReason getEndReason() { return endReason; }
        public String getStartedAt() { return startedAt; }
        public String getAnsweredAt() { return answeredAt; }
        public String getEndedAt() { return endedAt; }
        public long getDurationSeconds() { return durationSeconds; }
    }

    public static final class CallHistoryPage {
        private final List<CallRecord> items;
        private final String nextCursor;

        CallHistoryPage(List<CallRecord> items, String nextCursor) {
            this.items = Collections.unmodifiableList(new ArrayList<>(items));
            this.nextCursor = nextCursor;
        }

        public List<CallRecord> getItems() { return items; }
        public String getNextCursor() { return nextCursor; }
    }

    private static final int DEFAULT_HISTORY_LIMIT = 20;
    private static final Type MAP_TYPE = Types.newParameterizedType(
            Map.class, String.class, Object.class);

    private final Api api;
    private final Moshi moshi;

    public CallRepository(Api api, Moshi moshi) {
        this.api = api;
        this.moshi = moshi;
    }

    public Result<IceConfig> getIceConfig() {
        try {
            Response<Map<String, Object>> response = api.getIceConfig().execute();
            if (!response.isSuccessful() || response.body() == null) {
                return Result.failure(errorFor(response));
            }
            return Result.success(parseIceConfig(response.body()));
        } catch (IOException | RuntimeException exception) {
            return Result.failure(new CallError(CallError.Code.NETWORK));
        }
    }

    public Result<CallHistoryPage> getHistory(String conversationId, int limit, String cursor) {
        if (conversationId == null || conversationId.trim().isEmpty()) {
            return Result.failure(new CallError(CallError.Code.VALIDATION_FAILED));
        }
        int safeLimit = limit <= 0 ? DEFAULT_HISTORY_LIMIT : Math.min(limit, 100);
        try {
            Response<Map<String, Object>> response =
                    api.getCallHistory(conversationId, safeLimit, cursor).execute();
            if (!response.isSuccessful() || response.body() == null) {
                return Result.failure(errorFor(response));
            }
            return Result.success(parseHistory(response.body()));
        } catch (IOException | RuntimeException exception) {
            return Result.failure(new CallError(CallError.Code.NETWORK));
        }
    }

    private CallError errorFor(Response<?> response) {
        String code = null;
        if (response.errorBody() != null) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> body = (Map<String, Object>) moshi
                        .adapter(MAP_TYPE)
                        .fromJson(response.errorBody().string());
                code = string(body == null ? null : body.get("code"));
                if (code == null && body != null && body.get("error") instanceof Map) {
                    code = string(((Map<?, ?>) body.get("error")).get("code"));
                }
            } catch (IOException | RuntimeException ignored) {
                // The status still provides a safe, typed fallback below.
            }
        }
        if (code != null) return CallError.fromServerCode(code);
        if (response.code() == 403) return new CallError(CallError.Code.NOT_MEMBER);
        if (response.code() == 404) return new CallError(CallError.Code.CALL_NOT_FOUND);
        if (response.code() == 429) return new CallError(CallError.Code.RATE_LIMITED);
        return new CallError(CallError.Code.NETWORK);
    }

    private static IceConfig parseIceConfig(Map<String, Object> json) {
        List<IceConfig.IceServer> servers = new ArrayList<>();
        Object rawServers = json.get("iceServers");
        if (rawServers instanceof List) {
            for (Object rawServer : (List<?>) rawServers) {
                if (!(rawServer instanceof Map)) continue;
                Map<?, ?> server = (Map<?, ?>) rawServer;
                List<String> urls = new ArrayList<>();
                Object rawUrls = server.get("urls");
                if (rawUrls instanceof String) {
                    urls.add((String) rawUrls);
                } else if (rawUrls instanceof List) {
                    for (Object rawUrl : (List<?>) rawUrls) {
                        if (rawUrl instanceof String) urls.add((String) rawUrl);
                    }
                }
                servers.add(new IceConfig.IceServer(
                        urls,
                        string(server.get("username")),
                        string(server.get("credential"))));
            }
        }
        return new IceConfig(servers, integer(json.get("ttlSeconds"), 0));
    }

    private static CallHistoryPage parseHistory(Map<String, Object> json) {
        List<CallRecord> records = new ArrayList<>();
        Object rawItems = json.get("items");
        if (rawItems instanceof List) {
            for (Object rawItem : (List<?>) rawItems) {
                if (!(rawItem instanceof Map)) continue;
                Map<?, ?> item = (Map<?, ?>) rawItem;
                records.add(new CallRecord(
                        string(item.get("callId")),
                        string(item.get("callerId")),
                        string(item.get("calleeId")),
                        string(item.get("state")),
                        endReason(string(item.get("endReason"))),
                        string(item.get("startedAt")),
                        string(item.get("answeredAt")),
                        string(item.get("endedAt")),
                        integer(item.get("durationSeconds"), 0)));
            }
        }
        return new CallHistoryPage(records, string(json.get("nextCursor")));
    }

    private static CallEndReason endReason(String value) {
        if (value == null) return null;
        try {
            return CallEndReason.valueOf(value.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return CallEndReason.FAILED;
        }
    }

    private static int integer(Object value, int fallback) {
        return value instanceof Number ? ((Number) value).intValue() : fallback;
    }

    private static String string(Object value) {
        return value instanceof String ? (String) value : null;
    }
}
