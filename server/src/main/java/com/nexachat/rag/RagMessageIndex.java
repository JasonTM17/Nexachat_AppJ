package com.nexachat.rag;

import java.time.Instant;
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

    /**
     * Recent eligible messages created at or after {@code admittedFrom}; null means no bound.
     * Recent messages are newest first, so filtering after the limit keeps every admitted one.
     */
    default List<RagMessage> recentMessages(UUID conversationId, int limit, Instant admittedFrom) {
        return admittedOnly(recentMessages(conversationId, limit), admittedFrom);
    }

    /** Lexical candidates created at or after {@code admittedFrom}; null means no bound. */
    default List<RagMessage> lexicalCandidates(
            UUID conversationId, List<String> terms, int limit, Instant admittedFrom) {
        return admittedOnly(lexicalCandidates(conversationId, terms, limit), admittedFrom);
    }

    /** At most two distinct senders with the requested name and eligible, admitted text. */
    List<UUID> findEligibleSpeakerIds(UUID conversationId, Instant admittedFrom, String displayName);

    List<RagMessage> recentMessagesBySpeaker(UUID conversationId, UUID senderId, int limit, Instant admittedFrom);

    List<RagMessage> lexicalCandidatesBySpeaker(
            UUID conversationId, UUID senderId, List<String> terms, int limit, Instant admittedFrom);

    /**
     * Eligible messages in the bounded sequence neighborhood around {@code centerSeq},
     * oldest first. Used when ranked lexical evidence lies outside the recent window.
     * Implementations must apply the same deletion, expiry, encryption and opt-out
     * eligibility rules as retrieval and must honor {@code admittedFrom}.
     */
    List<RagMessage> messagesAroundSeq(
            UUID conversationId, long centerSeq, int radius, Instant admittedFrom);

    /** Whether an opted-out author has eligible admitted messages matching any lexical term. */
    boolean hasOptedOutLexicalCandidates(UUID conversationId, List<String> terms, Instant admittedFrom);

    /** Whether an opted-out author has any eligible admitted text message in the conversation. */
    boolean hasOptedOutMessages(UUID conversationId, Instant admittedFrom);

    /**
     * Whether the conversation holds any message created before {@code instant}. A member who
     * joined after such a message must not be served answers computed for earlier members.
     */
    boolean hasMessagesBefore(UUID conversationId, Instant instant);

    /** Keeps messages a member admitted at {@code admittedFrom} may read; null keeps all. */
    static List<RagMessage> admittedOnly(List<RagMessage> messages, Instant admittedFrom) {
        if (admittedFrom == null) {
            return messages;
        }
        return messages.stream().filter(message -> !message.createdAt().isBefore(admittedFrom)).toList();
    }
    /** Look up a single message by id, scoped to a conversation. */
    RagMessage findMessage(UUID conversationId, UUID messageId);

    /** Removes stored vectors for one message so backfill can re-embed edited text. */
    void deleteEmbeddingsForMessage(UUID messageId);
}
