package com.nexachat.res;

import android.content.res.Resources;

import com.nexachat.R;

/**
 * Centralizes {@link Resources#getQuantityString(int, int, Object...)} for count-with-noun copy.
 */
public final class QuantityStrings {

    private QuantityStrings() {
    }

    public static String inviteMemberCount(Resources resources, int count) {
        return resources.getQuantityString(R.plurals.invite_member_count, count, count);
    }

    /** Member count for a group, shown where a group needs its size at a glance. */
    public static String memberCount(Resources resources, int count) {
        return resources.getQuantityString(R.plurals.invite_member_count, count, count);
    }

    public static String inviteUsageUnlimited(Resources resources, int useCount) {
        return resources.getQuantityString(R.plurals.invite_usage_unlimited, useCount, useCount);
    }

    public static String inviteUsageLimited(Resources resources, int useCount, int maxUses) {
        return resources.getQuantityString(
                R.plurals.invite_usage_limited, maxUses, useCount, maxUses);
    }

    public static String pollVoterCount(Resources resources, int count) {
        return resources.getQuantityString(R.plurals.poll_voter_count, count, count);
    }

    public static String pollOptionVoteCount(Resources resources, int count) {
        return resources.getQuantityString(R.plurals.poll_option_count, count, count);
    }

    public static String unreadBanner(Resources resources, int unreadCount) {
        return resources.getQuantityString(R.plurals.unread_banner_format, unreadCount, unreadCount);
    }

    public static String chatUnreadDivider(Resources resources, int unreadCount) {
        return resources.getQuantityString(R.plurals.chat_unread_divider, unreadCount, unreadCount);
    }

    public static String askRateLimitedRetry(Resources resources, int retryAfterSeconds) {
        return resources.getQuantityString(
                R.plurals.ask_rate_limited_retry, retryAfterSeconds, retryAfterSeconds);
    }
}
