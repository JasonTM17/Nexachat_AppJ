package com.nexachat.feature.chat.system;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.nexachat.R;
import com.nexachat.call.ui.CallLogResourceResolver;

/** Maps parsed system events to localized user-visible chat row text. */
public final class SystemEventFormatter {

    private SystemEventFormatter() { }

    @NonNull
    public static CharSequence format(
            @NonNull Context context,
            @Nullable String content,
            boolean outgoingCallLog) {
        SystemEventPayload payload = SystemEventParser.parse(content);
        if (!payload.isLocalized()) {
            String fallback = payload.getFallbackText();
            return fallback == null ? "" : fallback;
        }
        if (payload.getType() == SystemEventType.CALL_LOG) {
            String raw = payload.getArguments()[0];
            CallLogResourceResolver.ResolvedCallLog resolved =
                    CallLogResourceResolver.fromSystemMessage(raw, outgoingCallLog);
            return context.getString(resolved.getResourceId(), resolved.getArguments());
        }
        int resId = stringResFor(payload.getType());
        Object[] args = payload.getArguments();
        if (args.length == 0) {
            return context.getString(resId);
        }
        return context.getString(resId, args);
    }

    private static int stringResFor(SystemEventType type) {
        return switch (type) {
            case GROUP_CREATED -> R.string.system_event_group_created;
            case GROUP_MEMBER_ADDED -> R.string.system_event_group_member_added;
            case GROUP_MEMBER_APPROVED -> R.string.system_event_group_member_approved;
            case GROUP_MEMBER_INVITE_JOINED -> R.string.system_event_group_invite_joined;
            case GROUP_MEMBER_LEFT -> R.string.system_event_group_member_left;
            case GROUP_MEMBER_REMOVED -> R.string.system_event_group_member_removed;
            case GROUP_RENAMED -> R.string.system_event_group_renamed;
            case GROUP_ROLE_CHANGED -> R.string.system_event_group_role_changed;
            default -> R.string.system_event_group_created;
        };
    }
}
