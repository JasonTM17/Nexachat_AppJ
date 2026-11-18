package com.nexachat.streak;

import com.nexachat.conversation.ConversationMemberRepository;
import com.nexachat.conversation.ConversationRepository;
import com.nexachat.streak.dto.StreakSummaryResponse;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StreakServiceTest {

    private final StreakStore store = mock(StreakStore.class);
    private final ConversationMemberRepository memberRepository =
            mock(ConversationMemberRepository.class);
    private final ConversationRepository conversationRepository =
            mock(ConversationRepository.class);
    private final StreakService service = new StreakService(store, new StreakProperties(),
            Clock.fixed(Instant.parse("2026-11-18T12:00:00Z"), ZoneOffset.UTC),
            memberRepository, conversationRepository);

    @Test
    void checkInStillReturnsTheCurrentViewWhenTheWriteFails() {
        doThrow(new RuntimeException("boom")).when(store)
                .applyUser(any(), any(), any());
        // Mirror StreakStore.guarded's savepoint contract for the unit test:
        // run the work, swallow a runtime failure, report false.
        when(store.guarded(anyString(), any())).thenAnswer(invocation -> {
            try {
                invocation.<Runnable>getArgument(1).run();
                return true;
            } catch (RuntimeException failure) {
                return false;
            }
        });
        when(store.findUser(any())).thenReturn(StreakRules.State.EMPTY);
        when(store.reminderEnabled(any())).thenReturn(true);

        StreakSummaryResponse response = service.checkIn(UUID.randomUUID());

        assertThat(response.checkIn().count()).isZero();
        assertThat(response.reminderEnabled()).isTrue();
    }
}
