package com.nexachat.message;

import com.nexachat.conversation.ConcurrentDatabaseITSupport;
import com.nexachat.conversation.dto.AddMemberRequest;
import com.nexachat.message.dto.EditMessageRequest;
import com.nexachat.message.dto.MessageResponse;
import com.nexachat.message.dto.SendMessageRequest;
import com.nexachat.message.dto.SyncRequest;
import com.nexachat.message.dto.SyncResponse;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SyncMembershipCursorIT extends ConcurrentDatabaseITSupport {
    @Autowired private MessageService messages;
    @Autowired private ReactionService reactions;

    @ParameterizedTest
    @CsvSource({"1,1", "1,2", "2,1", "2,2"})
    void removalRejectsOldCursorsAndRejoinCannotReadTheAbsenceInterval(int version, int pageSize) {
        var owner = user("Owner");
        var reader = user("Reader");
        UUID conversation = group(owner, reader);
        MessageResponse before = send(owner.getId(), conversation, "Before removal");
        SyncResponse.ConversationDelta oldPage = sync(reader.getId(), conversation, version, pageSize,
                0, 0, null).deltas().getFirst();
        long oldCursor = version == 2 ? oldPage.changeCursor() : oldPage.newMessages().getLast().seq();
        Long generation = version == 2 ? oldPage.generation() : null;

        groups.removeMember(owner.getId(), conversation, reader.getId());
        MessageResponse hidden = send(owner.getId(), conversation, "Private while removed");
        MessageResponse deleted = send(owner.getId(), conversation, "Private recalled message");
        messages.delete(owner.getId(), conversation, deleted.id());
        assertThat(sync(reader.getId(), conversation, version, pageSize, oldCursor, oldCursor, generation).deltas())
                .as("an old cursor cannot authorize a removed member").isEmpty();

        groups.addMembers(owner.getId(), conversation, new AddMemberRequest(List.of(reader.getId())));
        MessageResponse visible = send(owner.getId(), conversation, "Visible after rejoin");
        // Explicit logical instants make the membership boundary independent of clock granularity.
        Instant joined = Instant.parse("2026-01-02T00:00:00Z");
        jdbc.update("UPDATE messages SET created_at=? WHERE conversation_id=?", Timestamp.from(joined.minusSeconds(1)), conversation);
        jdbc.update("UPDATE conversation_members SET joined_at=? WHERE conversation_id=? AND user_id=?",
                Timestamp.from(joined), conversation, reader.getId());
        jdbc.update("UPDATE messages SET created_at=? WHERE id=?", Timestamp.from(joined.plusSeconds(1)), visible.id());
        // An edit after rejoin must not turn an absence-interval message into authorized data.
        messages.edit(owner.getId(), conversation, hidden.id(), new EditMessageRequest("Private edited after rejoin"));

        List<MessageResponse> received = new ArrayList<>();
        List<UUID> deletedIds = new ArrayList<>();
        long cursor = oldCursor;
        boolean complete = false;
        for (int page = 0; page < 20; page++) {
            var delta = sync(reader.getId(), conversation, version, pageSize, cursor, cursor, generation).deltas().getFirst();
            assertThat(delta.reset()).isNotEqualTo(Boolean.TRUE);
            List<MessageResponse> rows = version == 2 ? delta.changes() : delta.newMessages();
            received.addAll(rows);
            received.addAll(delta.editedMessages());
            deletedIds.addAll(delta.deletedMessageIds());
            if (!delta.hasMore()) {
                complete = true;
                break;
            }
            assertThat(rows).as("an authorized page must advance even across filtered rows").isNotEmpty();
            long next = version == 2 ? delta.changeCursor() : rows.getLast().seq();
            assertThat(next).isGreaterThan(cursor);
            cursor = next;
        }
        assertThat(complete).as("bounded authorized walk completes").isTrue();
        assertThat(received).extracting(MessageResponse::id).contains(visible.id())
                .doesNotContain(before.id(), hidden.id(), deleted.id());
        assertThat(deletedIds).doesNotContain(deleted.id());
    }

    @ParameterizedTest
    @CsvSource({"1,1", "2,1"})
    void zeroAndAdversarialCursorsCannotRecoverEarlierMembershipHistory(int version, int pageSize) {
        var owner = user("Owner");
        var reader = user("Reader");
        UUID conversation = group(owner, reader);
        MessageResponse previousEpoch = send(owner.getId(), conversation, "Previous membership");
        groups.removeMember(owner.getId(), conversation, reader.getId());
        groups.addMembers(owner.getId(), conversation, new AddMemberRequest(List.of(reader.getId())));
        MessageResponse current = send(owner.getId(), conversation, "Current membership");
        Instant joined = Instant.parse("2026-01-02T00:00:00Z");
        jdbc.update("UPDATE messages SET created_at=? WHERE conversation_id=?", Timestamp.from(joined.minusSeconds(1)), conversation);
        jdbc.update("UPDATE conversation_members SET joined_at=? WHERE conversation_id=? AND user_id=?",
                Timestamp.from(joined), conversation, reader.getId());
        jdbc.update("UPDATE messages SET created_at=? WHERE id=?", Timestamp.from(joined.plusSeconds(1)), current.id());
        var delta = sync(reader.getId(), conversation, version, pageSize, 0, 0, null).deltas().getFirst();
        assertThat(version == 2 ? delta.changes() : delta.newMessages()).extracting(MessageResponse::id)
                .containsExactly(current.id()).doesNotContain(previousEpoch.id());
        assertThat(delta.hasMore()).isFalse();
    }

    private SyncResponse sync(UUID user, UUID conversation, int version, int limit, long sequence, long cursor, Long generation) {
        return messages.syncDelta(user, new SyncRequest(List.of(new SyncRequest.ConversationWatermark(
                conversation, sequence, null, version == 2 ? cursor : null, generation)),
                limit, Instant.EPOCH, version));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void oldReactionCursorAndNestedReplyCannotExposeAnEarlierMembership(int version) {
        var owner = user("Owner");
        var reader = user("Reader");
        UUID conversation = group(owner, reader);
        MessageResponse hidden = send(owner.getId(), conversation, "Earlier confidential text");
        groups.removeMember(owner.getId(), conversation, reader.getId());
        reactions.add(owner.getId(), hidden.id(), "👍");
        long oldReaction = count("SELECT min(id) FROM reaction_events WHERE conversation_id=?", conversation);
        groups.addMembers(owner.getId(), conversation, new AddMemberRequest(List.of(reader.getId())));
        var visible = messages.send(owner.getId(), conversation, new SendMessageRequest(UUID.randomUUID(),
                "TEXT", "Current reply", hidden.id(), List.of())).message();
        Instant joined = Instant.now().minusSeconds(10);
        jdbc.update("UPDATE messages SET created_at=? WHERE conversation_id=?", Timestamp.from(joined.minusSeconds(1)), conversation);
        jdbc.update("UPDATE conversation_members SET joined_at=? WHERE conversation_id=? AND user_id=?",
                Timestamp.from(joined), conversation, reader.getId());
        jdbc.update("UPDATE messages SET created_at=? WHERE id=?", Timestamp.from(joined.plusSeconds(1)), visible.id());
        jdbc.update("UPDATE reaction_events SET created_at=? WHERE conversation_id=?", Timestamp.from(joined.minusSeconds(1)), conversation);
        // Even a NEW reaction to an inaccessible old target must not leak that message's identity/state.
        reactions.add(owner.getId(), hidden.id(), "❤️");
        reactions.add(owner.getId(), visible.id(), "👍");
        var delta = messages.syncDelta(reader.getId(), new SyncRequest(List.of(new SyncRequest.ConversationWatermark(
                conversation, 0L, oldReaction - 1, version == 2 ? 0L : null, null)),
                1, joined.minusSeconds(60), version)).deltas().getFirst();
        assertThat(delta.reactionUpdates()).extracting(com.nexachat.message.dto.ReactionUpdatedPayload::messageId)
                .containsExactly(visible.id());
        var rows = version == 2 ? delta.changes() : delta.newMessages();
        assertThat(rows).singleElement().satisfies(message -> {
            assertThat(message.id()).isEqualTo(visible.id());
            assertThat(message.replyTo()).as("sync quote hydration must obey the same membership boundary").isNull();
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void removalWhileSyncWaitsAfterTheMembershipProbeDoesNotLeakAPage(int version) throws Exception {
        var owner = user("Owner");
        var reader = user("Reader");
        UUID conversation = group(owner, reader);
        send(owner.getId(), conversation, "Only while a member");
        try (Workers workers = new Workers(); Gate gate = reactionGate(conversation)) {
            var sync = workers.submit(() -> messages.syncDelta(reader.getId(), new SyncRequest(List.of(
                    new SyncRequest.ConversationWatermark(conversation, 0L, null, version == 2 ? 0L : null, null)),
                    1, Instant.now().minusSeconds(60), version)));
            awaitBlocked(gate.pid, 1);
            groups.removeMember(owner.getId(), conversation, reader.getId());
            gate.commit();
            assertThat(result(sync).deltas()).allSatisfy(delta -> {
                assertThat(delta.newMessages()).isEmpty();
                assertThat(delta.editedMessages()).isEmpty();
                assertThat(delta.deletedMessageIds()).isEmpty();
                assertThat(delta.reactionUpdates()).isEmpty();
                if (delta.changes() != null) assertThat(delta.changes()).isEmpty();
            });
        }
    }

    private MessageResponse send(UUID user, UUID conversation, String content) {
        return messages.send(user, conversation,
                new SendMessageRequest(UUID.randomUUID(), "TEXT", content, null, List.of())).message();
    }
}
