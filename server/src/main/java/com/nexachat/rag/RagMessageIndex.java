package com.nexachat.rag;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Persistence boundary for retrieval. Implementations must scope every query
 * by conversation id; the service layer never trusts a message id alone.
 */
public interface RagMessageIndex {

    /** Most recent eligible messages of one conversation, newest first. */
    List<RagMessage> recentMessages(UUID conversationId, int limit);

    /** Lexical candidate messages matching at least one folded question term. */
    List<RagMessage> lexicalCandidates(UUID conversationId, List<String> terms, int limit);

    /** Vectors for one embedding model. Absent ids are simply omitted. */
    Map<UUID, float[]> loadVectors(String model, Collection<UUID> messageIds);

    /** Stored content hash per message for one embedding model. */
    Map<UUID, String> loadContentHashes(String model, Collection<UUID> messageIds);

    void saveVectors(String model, int dim, List<RagMessage> messages,
                     Map<UUID, float[]> vectors, Map<UUID, String> contentHashes);

    /** Messages that have no vector for the given model yet, newest first. */
    List<RagMessage> findUnindexed(String model, int limit);

    /** Current message content hash for cache validation; absent when deleted or opted out. */
    Map<UUID, String> loadMessageContentHashes(Collection<UUID> messageIds);

    /** Ids whose author opted out in the message's own conversation. */
    Set<UUID> optedOutMessageIds(Collection<UUID> messageIds);

    /** Look up a single message by id, scoped to a conversation. */
    RagMessage findMessage(UUID conversationId, UUID messageId);

    /** Removes stored vectors for one message so backfill can re-embed edited text. */
    void deleteEmbeddingsForMessage(UUID messageId);
}
