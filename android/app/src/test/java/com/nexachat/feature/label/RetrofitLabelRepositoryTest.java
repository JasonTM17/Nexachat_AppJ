package com.nexachat.feature.label;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nexachat.core.common.ApiError;
import com.nexachat.core.common.Result;
import com.nexachat.core.database.ConversationDao;
import com.nexachat.data.ConversationRepository;
import io.reactivex.rxjava3.core.Single;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public final class RetrofitLabelRepositoryTest {
    @Test
    public void inboxFilterPreservesRateLimitErrorCategory() {
        ConversationRepository conversations = mock(ConversationRepository.class);
        when(conversations.refreshInboxByLabel("work"))
                .thenReturn(Single.just(Result.failure(ApiError.RateLimited.INSTANCE)));
        RetrofitLabelRepository repository = new RetrofitLabelRepository(
                mock(LabelApi.class), mock(ConversationDao.class), conversations);

        AtomicReference<Throwable> failure = new AtomicReference<>();
        repository.filterConversationIds("work").subscribe(
                ignored -> { }, failure::set);

        LabelApiException mapped = (LabelApiException) failure.get();
        assertEquals(429, mapped.getStatus());
        assertEquals("RATE_LIMITED", mapped.getErrorCode());
    }
}
