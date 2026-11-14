package com.nexachat.feature.chat.system;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Interprets server {@code SYSTEM} message bodies. Structured {@code CALL|…} and
 * {@code EVT|…} tokens are preferred; legacy English lines from {@code GroupService}
 * are recognized for localization until the server emits structured events.
 */
public final class SystemEventParser {

    private static final String PREFIX_CALL = "CALL|";
    private static final String PREFIX_EVT = "EVT|";

    private static final Pattern CREATED = Pattern.compile("^(.+) created the group$");
    private static final Pattern ADDED = Pattern.compile("^(.+) added (.+)$");
    private static final Pattern APPROVED = Pattern.compile("^(.+) approved (.+) to join the group$");
    private static final Pattern INVITE_JOINED =
            Pattern.compile("^(.+) joined the group via invite link$");
    private static final Pattern LEFT = Pattern.compile("^(.+) left the group$");
    private static final Pattern REMOVED = Pattern.compile("^(.+) removed (.+)$");
    private static final Pattern RENAMED =
            Pattern.compile("^(.+) renamed the group to \"(.+)\"$");
    private static final Pattern ROLE_CHANGED = Pattern.compile("^(.+) changed (.+)'s role$");
    private static final Pattern ADMIN_PROMOTED =
            Pattern.compile("^(.+) made (.+) a group admin$");
    private static final Pattern E2EE_ON =
            Pattern.compile("^(.+) turned on end-to-end encryption$");
    private static final Pattern E2EE_OFF =
            Pattern.compile("^(.+) turned off end-to-end encryption$");

    private SystemEventParser() { }

    @NonNull
    public static SystemEventPayload parse(@Nullable String content) {
        String raw = content == null ? "" : content.trim();
        if (raw.isEmpty()) {
            return SystemEventPayload.unknown("");
        }
        if (raw.startsWith(PREFIX_CALL)) {
            return SystemEventPayload.localized(SystemEventType.CALL_LOG, raw);
        }
        if (raw.startsWith(PREFIX_EVT)) {
            return parseStructuredEvent(raw);
        }
        SystemEventPayload legacy = parseLegacyEnglish(raw);
        if (legacy.isLocalized()) {
            return legacy;
        }
        return SystemEventPayload.unknown(raw);
    }

    private static SystemEventPayload parseStructuredEvent(String raw) {
        String[] parts = raw.split("\\|", -1);
        if (parts.length < 2) {
            return SystemEventPayload.unknown(raw);
        }
        String typeKey = parts[1];
        return switch (typeKey) {
            case "GROUP_CREATED" -> argsRequired(raw, SystemEventType.GROUP_CREATED, parts, 3, 1);
            case "GROUP_MEMBER_ADDED" -> argsRequired(raw, SystemEventType.GROUP_MEMBER_ADDED, parts, 4, 2);
            case "GROUP_MEMBER_APPROVED" -> argsRequired(raw, SystemEventType.GROUP_MEMBER_APPROVED, parts, 4, 2);
            case "GROUP_MEMBER_INVITE_JOINED" ->
                    argsRequired(raw, SystemEventType.GROUP_MEMBER_INVITE_JOINED, parts, 3, 1);
            case "GROUP_MEMBER_LEFT" -> argsRequired(raw, SystemEventType.GROUP_MEMBER_LEFT, parts, 3, 1);
            case "GROUP_MEMBER_REMOVED" ->
                    argsRequired(raw, SystemEventType.GROUP_MEMBER_REMOVED, parts, 4, 2);
            case "GROUP_RENAMED" -> argsRequired(raw, SystemEventType.GROUP_RENAMED, parts, 4, 2);
            case "GROUP_ROLE_CHANGED" -> argsRequired(raw, SystemEventType.GROUP_ROLE_CHANGED, parts, 4, 2);
            case "GROUP_ADMIN_PROMOTED" ->
                    argsRequired(raw, SystemEventType.GROUP_ADMIN_PROMOTED, parts, 4, 2);
            case "E2EE_ENABLED" -> argsRequired(raw, SystemEventType.E2EE_ENABLED, parts, 3, 1);
            case "E2EE_DISABLED" -> argsRequired(raw, SystemEventType.E2EE_DISABLED, parts, 3, 1);
            default -> SystemEventPayload.unknown(raw);
        };
    }

    private static SystemEventPayload argsRequired(
            String raw,
            SystemEventType type,
            String[] parts,
            int minLength,
            int argCount) {
        if (parts.length < minLength) {
            return SystemEventPayload.unknown(raw);
        }
        String[] args = new String[argCount];
        boolean blank = false;
        for (int i = 0; i < argCount; i++) {
            args[i] = parts[2 + i];
            if (args[i].isBlank()) {
                blank = true;
            }
        }
        if (blank) {
            return SystemEventPayload.unknown(raw);
        }
        return SystemEventPayload.localized(type, args);
    }

    private static SystemEventPayload parseLegacyEnglish(String raw) {
        Matcher matcher;
        // Specific templates run before the generic ADDED/REMOVED catch-alls.
        if ((matcher = E2EE_ON.matcher(raw)).matches()) {
            return SystemEventPayload.localized(SystemEventType.E2EE_ENABLED, matcher.group(1));
        }
        if ((matcher = E2EE_OFF.matcher(raw)).matches()) {
            return SystemEventPayload.localized(SystemEventType.E2EE_DISABLED, matcher.group(1));
        }
        if ((matcher = ADMIN_PROMOTED.matcher(raw)).matches()) {
            return SystemEventPayload.localized(
                    SystemEventType.GROUP_ADMIN_PROMOTED, matcher.group(1), matcher.group(2));
        }
        if ((matcher = APPROVED.matcher(raw)).matches()) {
            return SystemEventPayload.localized(
                    SystemEventType.GROUP_MEMBER_APPROVED,
                    matcher.group(1), matcher.group(2));
        }
        if ((matcher = INVITE_JOINED.matcher(raw)).matches()) {
            return SystemEventPayload.localized(
                    SystemEventType.GROUP_MEMBER_INVITE_JOINED, matcher.group(1));
        }
        if ((matcher = RENAMED.matcher(raw)).matches()) {
            return SystemEventPayload.localized(
                    SystemEventType.GROUP_RENAMED, matcher.group(1), matcher.group(2));
        }
        if ((matcher = ROLE_CHANGED.matcher(raw)).matches()) {
            return SystemEventPayload.localized(
                    SystemEventType.GROUP_ROLE_CHANGED, matcher.group(1), matcher.group(2));
        }
        if ((matcher = CREATED.matcher(raw)).matches()) {
            return SystemEventPayload.localized(SystemEventType.GROUP_CREATED, matcher.group(1));
        }
        if ((matcher = LEFT.matcher(raw)).matches()) {
            return SystemEventPayload.localized(SystemEventType.GROUP_MEMBER_LEFT, matcher.group(1));
        }
        if ((matcher = REMOVED.matcher(raw)).matches()) {
            return SystemEventPayload.localized(
                    SystemEventType.GROUP_MEMBER_REMOVED, matcher.group(1), matcher.group(2));
        }
        if ((matcher = ADDED.matcher(raw)).matches()) {
            return SystemEventPayload.localized(
                    SystemEventType.GROUP_MEMBER_ADDED, matcher.group(1), matcher.group(2));
        }
        return SystemEventPayload.unknown(raw);
    }
}
