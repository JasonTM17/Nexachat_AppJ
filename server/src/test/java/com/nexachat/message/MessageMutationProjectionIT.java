package com.nexachat.message;

import com.nexachat.archive.ArchiveService;
import com.nexachat.conversation.ConcurrentDatabaseITSupport;
import com.nexachat.message.dto.EditMessageRequest;
import com.nexachat.message.dto.MessageResponse;
import com.nexachat.message.dto.SendMessageRequest;
import com.nexachat.message.dto.SyncRequest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MessageMutationProjectionIT extends ConcurrentDatabaseITSupport {
    @Autowired private MessageService messages;
    @Autowired private ArchiveService archive;
    enum Mutation { EDIT, AUTHOR_RECALL, ADMIN_RECALL }

    @ParameterizedTest
    @EnumSource(Mutation.class)
    void mutationAndRetryAgreeAcrossHistoryLegacyDeltaChangeFeedSearchAndArchive(Mutation mutation) {
        var admin = user("Admin");
        var author = user("Author");
        UUID conversation = group(admin, author);
        MessageResponse original = messages.send(author.getId(), conversation, new SendMessageRequest(
                UUID.randomUUID(), "TEXT", "Original needle https://example.com/original", null, List.of())).message();
        Instant since = Instant.now().minusSeconds(1);
        // The admin recall case uses an expired author window and a membership older than the message.
        jdbc.update("UPDATE conversation_members SET joined_at=? WHERE conversation_id=?",
                Timestamp.from(since.minusSeconds(4 * 86400L)), conversation);
        if (mutation == Mutation.ADMIN_RECALL) {
            jdbc.update("UPDATE messages SET created_at=? WHERE id=?", Timestamp.from(since.minusSeconds(2 * 86400L)), original.id());
        }
        assertThat(archive.browse(admin.getId(), conversation, "LINK", null, null, null, 10, null).items())
                .extracting(com.nexachat.archive.dto.ArchiveItem::messageId).contains(original.id());
        if (mutation == Mutation.EDIT) {
            messages.edit(author.getId(), conversation, original.id(), new EditMessageRequest("Replacement needle https://example.com/replacement"));
        } else {
            messages.delete(mutation == Mutation.ADMIN_RECALL ? admin.getId() : author.getId(), conversation, original.id());
        }
        var persistedAfterFirst = jdbc.queryForMap("SELECT content,edited_at,deleted_at,change_version FROM messages WHERE id=?", original.id());
        if (mutation == Mutation.EDIT) {
            messages.edit(author.getId(), conversation, original.id(), new EditMessageRequest("Replacement needle https://example.com/replacement"));
        } else {
            messages.delete(mutation == Mutation.ADMIN_RECALL ? admin.getId() : author.getId(), conversation, original.id());
        }
        assertThat(jdbc.queryForMap("SELECT content,edited_at,deleted_at,change_version FROM messages WHERE id=?", original.id()))
                .isEqualTo(persistedAfterFirst);
        var history = messages.getHistory(admin.getId(), conversation, null, null, 100).items().stream()
                .filter(message -> message.id().equals(original.id())).findFirst().orElseThrow();
        var legacy = messages.syncDelta(admin.getId(), new SyncRequest(List.of(
                new SyncRequest.ConversationWatermark(conversation, original.seq())), 10, since)).deltas().getFirst();
        var changes = messages.syncDelta(admin.getId(), new SyncRequest(List.of(
                new SyncRequest.ConversationWatermark(conversation, 0L, null, original.changeVersion(), original.generation())),
                10, since, 2)).deltas().getFirst().changes();
        var changed = changes.stream().filter(message -> message.id().equals(original.id())).findFirst().orElseThrow();
        assertThat(changed).isEqualTo(history);
        assertThat(messages.search(admin.getId(), conversation, "Original needle", 10, null).items()).isEmpty();
        if (mutation == Mutation.EDIT) {
            assertThat(history.content()).isEqualTo("Replacement needle https://example.com/replacement");
            assertThat(legacy.editedMessages()).containsExactly(history);
            assertThat(messages.search(admin.getId(), conversation, "Replacement needle", 10, null).items()).containsExactly(history);
            assertThat(archive.browse(admin.getId(), conversation, "LINK", null, null, null, 10, null).items())
                    .singleElement().satisfies(item -> assertThat(item.link().url()).isEqualTo("https://example.com/replacement"));
        } else {
            assertThat(history.content()).isNull();
            assertThat(history.deletedAt()).isNotNull();
            assertThat(legacy.deletedMessageIds()).containsExactly(original.id());
            assertThat(legacy.editedMessages()).isEmpty();
            assertThat(messages.search(admin.getId(), conversation, "needle", 10, null).items()).isEmpty();
            assertThat(archive.browse(admin.getId(), conversation, "LINK", null, null, null, 10, null).items()).isEmpty();
        }
    }
}
