package com.nexachat.feature.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nexachat.core.common.Result;
import com.nexachat.data.ConversationRepository;
import com.nexachat.data.GroupDetail;
import com.nexachat.data.GroupMember;
import io.reactivex.rxjava3.android.plugins.RxAndroidPlugins;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.subjects.SingleSubject;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public final class ConversationArchiveSenderSourceTest {

    @Before
    public void setUp() {
        RxAndroidPlugins.setInitMainThreadSchedulerHandler(ignored -> Schedulers.trampoline());
    }

    @After
    public void tearDown() {
        RxAndroidPlugins.reset();
    }

    @Test
    public void mapsGroupDetailMembersToArchiveSenders() {
        ConversationRepository conversations = mock(ConversationRepository.class);
        GroupDetail detail = new GroupDetail("Test Group", List.of(
                new GroupMember("user-1", "Alice", null, null, null),
                new GroupMember("user-2", "Bob", null, null, null)));
        when(conversations.groupDetail("conv-1"))
                .thenReturn(Single.just(new Result.Success<>(detail)));

        ConversationArchiveSenderSource source = new ConversationArchiveSenderSource(conversations);
        AtomicReference<List<ArchiveSender>> result = new AtomicReference<>();
        source.load("conv-1", result::set);

        assertEquals(2, result.get().size());
        assertEquals("user-1", result.get().get(0).getId());
        assertEquals("Alice", result.get().get(0).getDisplayName());
        assertEquals("user-2", result.get().get(1).getId());
        assertEquals("Bob", result.get().get(1).getDisplayName());
    }

    @Test
    public void returnsEmptyListOnFailure() {
        ConversationRepository conversations = mock(ConversationRepository.class);
        when(conversations.groupDetail("conv-1"))
                .thenReturn(Single.error(new RuntimeException("network error")));

        ConversationArchiveSenderSource source = new ConversationArchiveSenderSource(conversations);
        AtomicReference<List<ArchiveSender>> result = new AtomicReference<>();
        source.load("conv-1", result::set);

        assertTrue(result.get().isEmpty());
    }

    @Test
    public void cancellableDisposesSubscription() {
        ConversationRepository conversations = mock(ConversationRepository.class);
        SingleSubject<Result<GroupDetail>> subject = SingleSubject.create();
        when(conversations.groupDetail("conv-1")).thenReturn(subject);

        ConversationArchiveSenderSource source = new ConversationArchiveSenderSource(conversations);
        ArchiveSenderSource.Cancellable cancellable = source.load("conv-1", ignored -> { });
        assertTrue(subject.hasObservers());

        cancellable.cancel();
        assertTrue(!subject.hasObservers());
    }
}
