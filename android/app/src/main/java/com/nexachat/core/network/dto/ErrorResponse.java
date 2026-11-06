package com.nexachat.core.network.dto;

import androidx.annotation.Nullable;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;

/** Uniform error envelope: {@code {"error":{"code","message","details"}}}. */
public final class ErrorResponse {
    @Nullable private final Body error;
    private static volatile JsonAdapter<ErrorResponse> errorAdapter;

    public ErrorResponse() {
        this(null);
    }

    public ErrorResponse(@Nullable Body error) {
        this.error = error;
    }

    @Nullable
    public Body getError() {
        return error;
    }

    @Nullable
    public static String errorCodeOf(@Nullable String json) {
        if (json == null || JsonText.isBlank(json)) {
            return null;
        }
        try {
            ErrorResponse parsed = adapter().fromJson(json);
            if (parsed == null || parsed.error == null) {
                return null;
            }
            return parsed.error.code;
        } catch (IOException | RuntimeException ex) {
            return null;
        }
    }

    private static JsonAdapter<ErrorResponse> adapter() {
        JsonAdapter<ErrorResponse> local = errorAdapter;
        if (local == null) {
            local = new Moshi.Builder().build().adapter(ErrorResponse.class);
            errorAdapter = local;
        }
        return local;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof ErrorResponse that)) {
            return false;
        }
        return Objects.equals(error, that.error);
    }

    @Override
    public int hashCode() {
        return Objects.hash(error);
    }

    public static final class Body {
        @Nullable private final String code;
        @Nullable private final String message;
        @Nullable private final Map<String, Object> details;

        public Body() {
            this(null, null, null);
        }

        public Body(@Nullable String code, @Nullable String message, @Nullable Map<String, Object> details) {
            this.code = code;
            this.message = message;
            this.details = details;
        }

        @Nullable
        public String getCode() {
            return code;
        }

        @Nullable
        public String getMessage() {
            return message;
        }

        @Nullable
        public Map<String, Object> getDetails() {
            return details;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Body that)) {
                return false;
            }
            return Objects.equals(code, that.code)
                    && Objects.equals(message, that.message)
                    && Objects.equals(details, that.details);
        }

        @Override
        public int hashCode() {
            return Objects.hash(code, message, details);
        }
    }
}
