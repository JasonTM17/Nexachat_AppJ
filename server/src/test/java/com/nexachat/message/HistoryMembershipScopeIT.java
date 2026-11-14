package com.nexachat.message;

import com.nexachat.conversation.ConcurrentDatabaseITSupport;
import com.nexachat.conversation.dto.AddMemberRequest;
import com.nexachat.message.dto.MessageResponse;
import com.nexachat.message.dto.SendMessageRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** History, forward resync and search obey the same admission boundary as /sync. */
class HistoryMembershipScopeIT extends ConcurrentDatabaseITSupport {
    @Autowired private MessageService messages;

    private record Scenario(UUID conversation, UUID owner, UUID reader,
                            MessageResponse before, MessageResponse hidden, MessageResponse visible) { }

    private Scenario rejoined() {
        var owner = user("Owner");
        var reader = user("Reader");
        UUID conversation = group(owner, reader);
        MessageResponse before = send(owner.getId(), conversation, "needle before removal");
        groups.removeMember(owner.getId(), conversation, reader.getId());
        MessageResponse hidden = send(owner.getId(), conversation, "needle while removed");
        groups.addMembers(owner.getId(), conversation, new AddMemberRequest(List.of(reader.getId())));
        MessageResponse visible = messages.send(owner.getId(), conversation, new SendMessageRequest(
                UUID.randomUUID(), "TEXT", "needle after rejoin", hidden.id(), List.of())).message();
        Instant joined = Instant.now().minusSeconds(10);
        jdbc.update("UPDATE messages SET created_at=? WHERE conversation_id=?",
                Timestamp.from(joined.minusSeconds(1)), conversation);
        jdbc.update("UPDATE conversation_members SET joined_at=? WHERE conversation_id=? AND user_id=?",
                Timestamp.from(joined), conversation, reader.getId());
        jdbc.update("UPDATE messages SET created_at=? WHERE id=?",
                Timestamp.from(joined.plusSeconds(1)), visible.id());
        return new Scenario(conversation, owner.getId(), reader.getId(), before, hidden, visible);
    }

    @Test
    void backwardHistoryExcludesTheAbsenceInterval() {
        Scenario s = rejoined();
        var page = messages.getHistory(s.reader(), s.conversation(), null, null, 50);
        assertThat(page.items()).extracting(MessageResponse::id)
                .contains(s.visible().id()).doesNotContain(s.before().id(), s.hidden().id());
    }

    @Test
    void forwardResyncCannotReadTheAbsenceIntervalEvenFromTheGapBoundary() {
        Scenario s = rejoined();
        var page = messages.getHistory(s.reader(), s.conversation(), null, s.hidden().seq() - 1, 1);
        assertThat(page.items()).extracting(MessageResponse::id).doesNotContain(s.hidden().id());
        var all = messages.getHistory(s.reader(), s.conversation(), null, 0L, 50);
        assertThat(all.items()).extracting(MessageResponse::id)
                .containsExactly(s.visible().id());
    }

    @Test
    void quoteOfAnInaccessibleMessageIsNotHydrated() {
        Scenario s = rejoined();
        var page = messages.getHistory(s.reader(), s.conversation(), null, null, 50);
        assertThat(page.items()).singleElement().satisfies(message -> {
            assertThat(message.id()).isEqualTo(s.visible().id());
            assertThat(message.replyTo()).isNull();
        });
    }

    @Test
    void searchExcludesTheAbsenceInterval() {
        Scenario s = rejoined();
        var page = messages.search(s.reader(), s.conversation(), "needle", 50, null);
        assertThat(page.items()).extracting(MessageResponse::id)
                .containsExactly(s.visible().id());
    }

    @Test
    void anUninterruptedMemberStillSeesEverything() {
        var owner = user("Owner");
        var reader = user("Reader");
        UUID conversation = group(owner, reader);
        MessageResponse first = send(owner.getId(), conversation, "needle one");
        MessageResponse second = send(owner.getId(), conversation, "needle two");
        assertThat(messages.getHistory(reader.getId(), conversation, null, null, 50).items())
                .extracting(MessageResponse::id).contains(first.id(), second.id());
        assertThat(messages.search(reader.getId(), conversation, "needle", 50, null).items())
                .extracting(MessageResponse::id).contains(first.id(), second.id());
    }

    private MessageResponse send(UUID user, UUID conversation, String content) {
        return messages.send(user, conversation,
                new SendMessageRequest(UUID.randomUUID(), "TEXT", content, null, List.of())).message();
    }
}