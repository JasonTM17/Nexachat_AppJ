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
    @Autowired private PinService pins;
    @Autowired private com.nexachat.archive.ArchiveService archive;
    @Autowired private com.nexachat.summary.SummaryWindowService summaryWindows;
    @Autowired private com.nexachat.rag.RagMessageIndex ragIndex;
    @Autowired private com.nexachat.rag.RagRetrievalService retrieval;

    private record Scenario(UUID conversation, UUID owner, UUID reader,
                            MessageResponse before, MessageResponse hidden, MessageResponse visible) { }

    private Scenario rejoined() {
        var owner = user("Owner");
        var reader = user("Reader");
        UUID conversation = group(owner, reader);
        MessageResponse before = send(owner.getId(), conversation, "needle before removal");
        groups.removeMember(owner.getId(), conversation, reader.getId());
        MessageResponse hidden = send(owner.getId(), conversation, "needle while removed https://example.test/private-gap");
        groups.addMembers(owner.getId(), conversation, new AddMemberRequest(List.of(reader.getId())));
        MessageResponse visible = messages.send(owner.getId(), conversation, new SendMessageRequest(
                UUID.randomUUID(), "TEXT", "needle after rejoin https://example.test/visible", hidden.id(), List.of())).message();
        Instant joined = Instant.now().minusSeconds(10);
        jdbc.update("UPDATE messages SET created_at=? WHERE conversation_id=?",
                Timestamp.from(joined.minusSeconds(1)), conversation);
        jdbc.update("UPDATE conversation_members SET joined_at=? WHERE conversation_id=? AND user_id=?",
                Timestamp.from(joined), conversation, reader.getId());
        jdbc.update("UPDATE messages SET created_at=? WHERE id=?",
                Timestamp.from(joined.plusSeconds(1)), visible.id());
        jdbc.update("UPDATE conversation_members SET joined_at=? WHERE conversation_id=? AND user_id=?",
                Timestamp.from(joined.minusSeconds(60)), conversation, owner.getId());
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
    void pinnedGapMessageIsNotListedToTheRejoinedMember() {
        Scenario s = rejoined();
        pins.pin(s.owner(), s.conversation(), new com.nexachat.message.dto.PinRequest(s.hidden().id()));
        pins.pin(s.owner(), s.conversation(), new com.nexachat.message.dto.PinRequest(s.visible().id()));
        assertThat(pins.listPins(s.reader(), s.conversation())).extracting(p -> p.messageId())
                .containsExactly(s.visible().id());
        assertThat(pins.listPins(s.owner(), s.conversation())).extracting(p -> p.messageId())
                .contains(s.hidden().id(), s.visible().id());
    }

    @Test
    void archivedLinksFromTheAbsenceIntervalAreNotBrowsable() {
        Scenario s = rejoined();
        var forReader = archive.browse(s.reader(), s.conversation(), "LINK", null, null, null, 50, null);
        assertThat(forReader.items()).extracting(i -> i.messageId()).containsExactly(s.visible().id());
        var forOwner = archive.browse(s.owner(), s.conversation(), "LINK", null, null, null, 50, null);
        assertThat(forOwner.items()).extracting(i -> i.messageId()).contains(s.hidden().id(), s.visible().id());
    }

    @Test
    void summaryWindowExcludesTheAbsenceInterval() {
        Scenario s = rejoined();
        var forReader = summaryWindows.load(s.conversation(), s.reader(),
                new com.nexachat.summary.SummaryRequest(com.nexachat.summary.SummaryScope.LAST_N, 50, null, null));
        assertThat(forReader.window().messages()).extracting(m -> m.id())
                .containsExactly(s.visible().id());
        var forOwner = summaryWindows.load(s.conversation(), s.owner(),
                new com.nexachat.summary.SummaryRequest(com.nexachat.summary.SummaryScope.LAST_N, 50, null, null));
        assertThat(forOwner.window().messages()).extracting(m -> m.id())
                .contains(s.before().id(), s.hidden().id(), s.visible().id());
    }

    @Test
    void askRetrievalAndSharedAnswersExcludeTheAbsenceInterval() {
        Scenario s = rejoined();
        Instant readerJoined = jdbc.queryForObject(
                "SELECT joined_at FROM conversation_members WHERE conversation_id=? AND user_id=?",
                Timestamp.class, s.conversation(), s.reader()).toInstant();
        assertThat(ragIndex.lexicalCandidates(s.conversation(), List.of("needle"), 50, readerJoined))
                .extracting(m -> m.id()).containsExactly(s.visible().id());
        assertThat(ragIndex.recentMessages(s.conversation(), 50, readerJoined))
                .extracting(m -> m.id()).containsExactly(s.visible().id());
        assertThat(retrieval.hasContentBeforeAdmission(s.conversation(), readerJoined)).isTrue();
        Instant ownerJoined = jdbc.queryForObject(
                "SELECT joined_at FROM conversation_members WHERE conversation_id=? AND user_id=?",
                Timestamp.class, s.conversation(), s.owner()).toInstant();
        assertThat(retrieval.hasContentBeforeAdmission(s.conversation(), ownerJoined)).isFalse();
    }

    @Test
    void aRejoinedMemberCannotReplyToAMessageFromTheirAbsence() {
        Scenario s = rejoined();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> messages.send(s.reader(), s.conversation(),
                        new SendMessageRequest(UUID.randomUUID(), "TEXT", "quoting the gap", s.hidden().id(), List.of())))
                .isInstanceOfSatisfying(com.nexachat.common.error.ApiException.class, e ->
                        assertThat(e.getCode()).isEqualTo(com.nexachat.common.error.ApiErrorCode.MESSAGE_NOT_FOUND));
        MessageResponse reply = messages.send(s.reader(), s.conversation(),
                new SendMessageRequest(UUID.randomUUID(), "TEXT", "quoting what I can see", s.visible().id(), List.of()))
                .message();
        assertThat(reply.replyTo()).isNotNull();
        assertThat(reply.replyTo().id()).isEqualTo(s.visible().id());
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