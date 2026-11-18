package com.nexachat.feed;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * The feed side of report submission: tells {@code ReportService} whether the
 * reporter can see the post or comment they are reporting, and hands back the
 * body to keep as evidence.
 *
 * <p>Kept as a separate component so the report slice does not depend on the
 * feed's storage or service layer — only on the visibility predicate, which is
 * the same one every feed read uses.</p>
 */
@Component
public class FeedReportTargets {

    private final FeedStore store;

    FeedReportTargets(FeedStore store) {
        this.store = store;
    }

    /**
     * The post the reporter can see; empty when it is gone, hidden or blocked.
     * Its body is optional because an image-only post carries no text.
     */
    public Optional<PostBody> visiblePostBody(UUID viewerId, UUID postId) {
        return store.findVisiblePost(viewerId, postId).map(row -> new PostBody(row.body()));
    }

    /** Evidence text for a post report; {@code value} is null on an image-only post. */
    public record PostBody(String value) {
    }

    /** Body of a comment the reporter can see, including its post's visibility. */
    public Optional<String> visibleCommentBody(UUID viewerId, UUID commentId) {
        return store.visibleCommentBody(viewerId, commentId);
    }
}
