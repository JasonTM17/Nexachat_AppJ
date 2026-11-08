package com.nexachat.feature.ask;

import androidx.annotation.Nullable;
import com.nexachat.core.network.dto.ErrorResponse;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import java.io.IOException;
import java.util.Map;

/** Maps wire errors without exposing server messages or request content to the UI. */
public final class AskErrorMapper {
    private static final JsonAdapter<ErrorResponse> ERROR_ADAPTER =
            new Moshi.Builder().build().adapter(ErrorResponse.class);

    private AskErrorMapper() { }

    public static AskErrorMapping fromHttp(int status, @Nullable String errorJson) {
        ErrorResponse.Body body = parse(errorJson);
        String code = body == null ? null : body.getCode();
        AskError error = fromCode(code);
        if (error == AskError.UNKNOWN) {
            if (status == 400) {
                error = AskError.VALIDATION;
            } else if (status == 403 || status == 404) {
                error = AskError.CHAT_UNAVAILABLE;
            } else if (status == 429) {
                error = AskError.RATE_LIMITED;
            }
        }
        Long retryAfter = error == AskError.RATE_LIMITED ? retryAfter(body) : null;
        return new AskErrorMapping(error, retryAfter);
    }

    public static AskErrorMapping fromFailure(Throwable failure) {
        return new AskErrorMapping(
                failure instanceof IOException ? AskError.NETWORK : AskError.UNKNOWN,
                null);
    }

    private static AskError fromCode(@Nullable String code) {
        if (code == null) {
            return AskError.UNKNOWN;
        }
        switch (code) {
            case "VALIDATION_FAILED":
                return AskError.VALIDATION;
            case "NOT_MEMBER":
            case "CONVERSATION_NOT_FOUND":
                return AskError.CHAT_UNAVAILABLE;
            case "RATE_LIMITED":
                return AskError.RATE_LIMITED;
            case "CONSENT_REQUIRED":
            case "AI_CONSENT_REQUIRED":
            case "AI_SUMMARY_CONSENT_REQUIRED":
                return AskError.CONSENT_REQUIRED;
            default:
                return AskError.UNKNOWN;
        }
    }

    @Nullable
    private static ErrorResponse.Body parse(@Nullable String errorJson) {
        if (errorJson == null || errorJson.trim().isEmpty()) {
            return null;
        }
        try {
            ErrorResponse response = ERROR_ADAPTER.fromJson(errorJson);
            return response == null ? null : response.getError();
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    @Nullable
    private static Long retryAfter(@Nullable ErrorResponse.Body body) {
        if (body == null) {
            return null;
        }
        Map<String, Object> details = body.getDetails();
        Object value = details == null ? null : details.get("retryAfterSeconds");
        if (!(value instanceof Number)) {
            return null;
        }
        long seconds = ((Number) value).longValue();
        return seconds > 0L ? seconds : null;
    }
}
