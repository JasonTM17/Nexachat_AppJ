package com.nexachat.streak;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.conversation.Conversation;
import com.nexachat.conversation.ConversationMemberRepository;
import com.nexachat.conversation.ConversationRepository;
import com.nexachat.streak.RecentDayCache.Key;
import com.nexachat.streak.StreakRules.Side;
import com.nexachat.streak.StreakRules.State;
import com.nexachat.streak.StreakRules.View;
import com.nexachat.streak.dto.StreakResponse;
import com.nexachat.streak.dto.StreakSummaryResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Streak use cases: the write hook the message and feed paths call, and the
 * read models behind the REST endpoints and the inbox.
 *
 * <p><b>Send path cost.</b> The message transaction already holds the
 * conversation row lock, so chain updates are serialized per conversation
 * without extra locking. The first message of a streak day for a (scope, user)
 * pair costs one primary-key select plus one write; every later one is
 * answered from {@link RecentDayCache}, which is filled only after the
 * surrounding transaction commits.
 *
 * <p><b>Failure isolation.</b> Bookkeeping runs inside a savepoint
 * ({@link StreakStore#guarded}); a failing statement is rolled back and
 * logged and never fails or poisons the send.
 */
@Service
@Profile("!test")
public class StreakService {

    private final StreakStore store;
    private final StreakProperties properties;
    private final Clock clock;
    private final ConversationMemberRepository memberRepository;
    private final ConversationRepository conversationRepository;
    private final RecentDayCache cache;

    public StreakService(StreakStore store,
                         StreakProperties properties,
                         Clock clock,
                         ConversationMemberRepository memberRepository,
                         ConversationRepository conversationRepository) {
        this.store = store;
        this.properties = properties;
        this.clock = clock;
        this.memberRepository = memberRepository;
        this.conversationRepository = conversationRepository;
        this.cache = new RecentDayCache(properties.getCacheMaxEntries());
    }

    // ------------------------------------------------------------------ write

    /**
     * Records a message that was just stored (and is not suppressed) for the
     * sender's streaks. {@code directKey} is the conversation's
     * {@code LEAST:GREATEST} key when it is a DIRECT chat and {@code null}
     * otherwise: group chats only feed the sender's check-in streak. Must be
     * called inside the send transaction.
     */
    public void recordMessageSent(UUID conversationId, String directKey, UUID senderId,
                                  Instant createdAt) {
        if (!properties.isEnabled()) {
            return;
        }
        LocalDate day = dayOf(createdAt);
        if (directKey != null) {
            recordChain(conversationId, directKey, senderId, day);
        }
        recordUserSent(senderId, createdAt);
    }

    /** Records that the user sent something (message, feed post or comment) at {@code at}. */
    public void recordUserSent(UUID userId, Instant at) {
        if (!properties.isEnabled()) {
            return;
        }
        LocalDate day = dayOf(at);
        Key key = new Key(null, userId);
        if (cache.recorded(key, day)) {
            return;
        }
        if (store.guarded("user sent", () -> store.applyUser(userId, Side.B, day))) {
            rememberAfterCommit(key, day);
        }
    }

    private void recordChain(UUID conversationId, String directKey, UUID senderId, LocalDate day) {
        Key key = new Key(conversationId, senderId);
        if (cache.recorded(key, day)) {
            return;
        }
        String[] pair = directKey.split(":", -1);
        UUID low;
        UUID high;
        try {
            if (pair.length != 2) {
                return;
            }
            low = UUID.fromString(pair[0]);
            high = UUID.fromString(pair[1]);
        } catch (IllegalArgumentException malformed) {
            return;
        }
        if (!senderId.equals(low) && !senderId.equals(high)) {
            return;
        }
        Side side = senderId.equals(low) ? Side.A : Side.B;
        if (store.guarded("conversation chain",
                () -> store.applyChain(conversationId, low, high, side, day))) {
            rememberAfterCommit(key, day);
        }
    }

    /** Records "the user opened the app today"; idempotent. */
    @Transactional
    public StreakSummaryResponse checkIn(UUID userId) {
        LocalDate today = today();
        if (properties.isEnabled()) {
            store.guarded("user check-in", () -> store.applyUser(userId, Side.A, today));
        }
        return summary(userId);
    }

    private void rememberAfterCommit(Key key, LocalDate day) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    cache.remember(key, day);
                }
            });
        } else {
            cache.remember(key, day);
        }
    }

    // ------------------------------------------------------------------- read

    @Transactional(readOnly = true)
    public StreakSummaryResponse summary(UUID userId) {
        LocalDate today = today();
        View view = StreakRules.view(store.findUser(userId), today);
        return new StreakSummaryResponse(
                StreakResponse.checkIn(view), store.reminderEnabled(userId));
    }

    /**
     * The caller's view of one conversation's chain. Members only; a
     * conversation that is not a DIRECT chat has no streak and reads as zero.
     */
    @Transactional(readOnly = true)
    public StreakResponse conversationStreak(UUID userId, UUID conversationId) {
        if (!memberRepository.existsByConversation_IdAndUser_Id(conversationId, userId)) {
            throw ApiException.of(ApiErrorCode.NOT_MEMBER, "Not a member of this conversation");
        }
        LocalDate today = today();
        boolean direct = conversationRepository.findTypeById(conversationId)
                .map(Conversation.Type.DIRECT::equals).orElse(false);
        if (!direct) {
            return StreakResponse.conversation(StreakRules.view(State.EMPTY, today), false, false);
        }
        return store.findChain(conversationId)
                .map(row -> {
                    View view = StreakRules.view(row.state(), today);
                    boolean mineIsA = row.sideOf(userId) == Side.A;
                    return StreakResponse.conversation(view,
                            mineIsA ? view.sideADoneToday() : view.sideBDoneToday(),
                            mineIsA ? view.sideBDoneToday() : view.sideADoneToday());
                })
                .orElseGet(() -> StreakResponse.conversation(
                        StreakRules.view(State.EMPTY, today), false, false));
    }

    /** Inbox projection: current count and at-risk flag per conversation, batch-read. */
    @Transactional(readOnly = true)
    public Map<UUID, InboxStreak> inboxStreaks(Collection<UUID> conversationIds) {
        LocalDate today = today();
        Map<UUID, InboxStreak> result = new HashMap<>();
        store.findChains(conversationIds).forEach((conversationId, row) -> {
            View view = StreakRules.view(row.state(), today);
            if (view.count() > 0) {
                result.put(conversationId, new InboxStreak(view.count(), view.atRisk()));
            }
        });
        return result;
    }

    /** Count and at-risk flag shown on a conversation row. */
    public record InboxStreak(int count, boolean atRisk) {
    }

    // --------------------------------------------------------------- settings

    @Transactional(readOnly = true)
    public boolean reminderEnabled(UUID userId) {
        return store.reminderEnabled(userId);
    }

    @Transactional
    public void setReminderEnabled(UUID userId, boolean enabled) {
        store.setReminderEnabled(userId, enabled);
    }

    // ---------------------------------------------------------------- helpers

    /** The streak day an instant falls on. */
    LocalDate dayOf(Instant instant) {
        return instant.atZone(properties.zoneId()).toLocalDate();
    }

    LocalDate today() {
        return dayOf(clock.instant());
    }

    /** Drops the in-memory "already recorded" memory (tests). */
    void clearCache() {
        cache.clear();
    }
}
