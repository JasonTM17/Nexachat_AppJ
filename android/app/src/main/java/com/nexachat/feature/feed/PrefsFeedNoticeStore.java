package com.nexachat.feature.feed;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import dagger.hilt.android.qualifiers.ApplicationContext;
import javax.inject.Inject;
import javax.inject.Singleton;

/** SharedPreferences-backed {@link FeedNoticeStore}. */
@Singleton
public final class PrefsFeedNoticeStore implements FeedNoticeStore {
    private static final String PREFS = "nexachat_feed";
    private static final String KEY_NOTICE_SEEN = "public_notice_seen";

    private final SharedPreferences prefs;

    @Inject
    public PrefsFeedNoticeStore(@NonNull @ApplicationContext Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @Override
    public boolean isNoticeSeen() {
        return prefs.getBoolean(KEY_NOTICE_SEEN, false);
    }

    @Override
    public void markNoticeSeen() {
        prefs.edit().putBoolean(KEY_NOTICE_SEEN, true).apply();
    }
}
