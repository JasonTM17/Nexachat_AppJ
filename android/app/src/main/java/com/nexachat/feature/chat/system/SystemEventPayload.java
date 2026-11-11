package com.nexachat.feature.chat.system;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Parsed system-message body: either a known type with arguments or unknown free text. */
public final class SystemEventPayload {

    @NonNull private final SystemEventType type;
    @NonNull private final String[] arguments;
    @Nullable private final String fallbackText;

  private SystemEventPayload(
            @NonNull SystemEventType type,
            @NonNull String[] arguments,
            @Nullable String fallbackText) {
        this.type = type;
        this.arguments = arguments.clone();
        this.fallbackText = fallbackText;
    }

    public static SystemEventPayload localized(@NonNull SystemEventType type, String... arguments) {
        return new SystemEventPayload(type, arguments, null);
    }

    public static SystemEventPayload unknown(@Nullable String raw) {
        return new SystemEventPayload(SystemEventType.UNKNOWN, new String[0], raw);
    }

    @NonNull
    public SystemEventType getType() {
        return type;
    }

    @NonNull
    public String[] getArguments() {
        return arguments.clone();
    }

    @Nullable
    public String getFallbackText() {
        return fallbackText;
    }

    public boolean isLocalized() {
        return type != SystemEventType.UNKNOWN;
    }
}
