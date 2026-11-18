package com.nexachat.feed;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Applies the feed image rule to the raw {@code /api/v1/files/**} path.
 *
 * <p>Feed images are stored under the shared storage root, so without this
 * guard a caller who learned a storage key could read an image the feed would
 * refuse. A key that belongs to a feed image is therefore readable only when
 * the caller owns it or it is attached to a post they can see; every other key
 * (avatars, message attachments) is left to its own rule.
 */
@Component
public class FeedImageAccess {

    private final FeedStore store;

    FeedImageAccess(FeedStore store) {
        this.store = store;
    }

    /**
     * Refuses a feed image key the caller may not read.
     *
     * @throws ApiException {@code FILE_NOT_FOUND} when the key is a feed image
     *                      the caller may not read
     */
    public void assertStorageKeyReadable(String storageKey, UUID viewerId) {
        store.findImageByStorageKey(storageKey).ifPresent(image -> {
            if (!image.ownerId().equals(viewerId) && !store.isImageVisibleTo(image.id(), viewerId)) {
                throw ApiException.of(ApiErrorCode.FILE_NOT_FOUND);
            }
        });
    }
}
