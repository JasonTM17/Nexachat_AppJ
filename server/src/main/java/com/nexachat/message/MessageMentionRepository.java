package com.nexachat.message;

import com.nexachat.message.dto.MessageMentionResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** Batched persistence for the small, structured mention list attached to each message. */
@Repository
public class MessageMentionRepository {

    private final ObjectProvider<JdbcTemplate> jdbcProvider;

    public MessageMentionRepository(ObjectProvider<JdbcTemplate> jdbcProvider) {
        this.jdbcProvider = jdbcProvider;
    }

    public void replace(UUID messageId, UUID conversationId, List<MessageMentionResponse> mentions) {
        deleteByMessageId(messageId);
        if (mentions.isEmpty()) {
            return;
        }
        jdbcProvider.getObject().batchUpdate("""
                INSERT INTO message_mentions
                    (message_id, conversation_id, user_id, mention_offset, mention_length)
                VALUES (?, ?, ?, ?, ?)
                """, mentions, mentions.size(), (statement, mention) -> {
            statement.setObject(1, messageId);
            statement.setObject(2, conversationId);
            statement.setObject(3, mention.userId());
            statement.setInt(4, mention.offset());
            statement.setInt(5, mention.length());
        });
    }

    public List<MessageMentionResponse> findByMessageId(UUID messageId) {
        return findByMessageIds(List.of(messageId)).getOrDefault(messageId, List.of());
    }

    public Map<UUID, List<MessageMentionResponse>> findByMessageIds(Collection<UUID> messageIds) {
        if (messageIds.isEmpty()) {
            return Map.of();
        }
        List<UUID> ids = new ArrayList<>(messageIds);
        String placeholders = ids.stream().map(ignored -> "?").collect(Collectors.joining(", "));
        Map<UUID, List<MessageMentionResponse>> result = new LinkedHashMap<>();
        jdbcProvider.getObject().query("""
                        SELECT message_id, user_id, mention_offset, mention_length
                        FROM message_mentions
                        WHERE message_id IN (%s)
                        ORDER BY message_id, mention_offset, user_id
                        """.formatted(placeholders),
                statement -> {
                    for (int index = 0; index < ids.size(); index++) {
                        statement.setObject(index + 1, ids.get(index));
                    }
                }, rows -> {
                    while (rows.next()) {
                        UUID messageId = rows.getObject("message_id", UUID.class);
                        result.computeIfAbsent(messageId, ignored -> new ArrayList<>())
                                .add(new MessageMentionResponse(
                                        rows.getObject("user_id", UUID.class),
                                        rows.getInt("mention_offset"),
                                        rows.getInt("mention_length")));
                    }
                    return null;
                });
        return result;
    }

    public void deleteByMessageId(UUID messageId) {
        jdbcProvider.getObject().update("DELETE FROM message_mentions WHERE message_id = ?", messageId);
    }
}
