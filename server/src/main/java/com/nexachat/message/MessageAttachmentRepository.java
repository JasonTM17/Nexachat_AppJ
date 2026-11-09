package com.nexachat.message;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MessageAttachmentRepository extends JpaRepository<MessageAttachment, UUID> {

    /** Batch fetch for a page of messages — avoids the per-message N+1. */
    List<MessageAttachment> findByMessage_IdIn(Collection<UUID> messageIds);

    /**
     * Lookup by the UNIQUE {@code storage_key} — the authZ join for the raw
     * {@code /api/v1/files/**} serving path: a hit means the key is a message
     * attachment and the caller must pass the owner/member rule; a miss means
     * a profile-public file (e.g. avatar) with no membership concept.
     */
    Optional<MessageAttachment> findByStorageKey(String storageKey);

    /**
     * Holds a key-share row lock while a group adopts a message-attachment URL.
     * The retention cleaner takes an UPDATE lock on the same row before it
     * removes the file, so one operation must observe the other's committed
     * result.
     */
    @Query(value = """
            SELECT storage_key
            FROM message_attachments
            WHERE storage_key = :storageKey
            FOR KEY SHARE
            """, nativeQuery = true)
    Optional<String> lockStorageKeyForReference(@Param("storageKey") String storageKey);
}
