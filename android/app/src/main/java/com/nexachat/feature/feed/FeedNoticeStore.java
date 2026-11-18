package com.nexachat.feature.feed;

/** Device-local flag for the one-time "posts here are public" notice. */
public interface FeedNoticeStore {

    /** True once the caller has seen the notice on this device. */
    boolean isNoticeSeen();

    void markNoticeSeen();
}
