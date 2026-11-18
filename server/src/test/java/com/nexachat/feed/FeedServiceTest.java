package com.nexachat.feed;

import com.nexachat.common.error.ApiException;
import com.nexachat.feed.dto.CreatePostRequest;
import com.nexachat.streak.StreakService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FeedServiceTest {

    private final FeedStore store = mock(FeedStore.class);
    private final FeedRateLimits rateLimits = mock(FeedRateLimits.class);
    private final ObjectProvider<StreakService> streakService = mock(ObjectProvider.class);
    private final FeedService service = new FeedService(store, rateLimits, streakService,
            Clock.fixed(Instant.parse("2026-11-18T12:00:00Z"), ZoneOffset.UTC));

    @Test
    void createFailsWhenTheImageWasClaimedByAnotherPostInBetween() {
        UUID authorId = UUID.randomUUID();
        UUID imageId = UUID.randomUUID();
        when(store.findImage(imageId)).thenReturn(Optional.of(new FeedStore.ImageRow(
                imageId, authorId, "key", "image/png", 4L, 16, 16, null)));
        when(store.insertPost(any(), any(), any(), any(), any(), any())).thenReturn(1);
        // A concurrent create bound the image after the bound-state check.
        when(store.bindImage(imageId, authorId)).thenReturn(0);

        assertThatThrownBy(() -> service.create(authorId,
                new CreatePostRequest("hi", imageId, UUID.randomUUID())))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("attached to a post");
    }
}
