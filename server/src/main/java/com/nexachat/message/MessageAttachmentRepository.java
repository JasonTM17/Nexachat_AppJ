package com.nexachat.message;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface MessageAttachmentRepository extends JpaRepository<MessageAttachment, UUID> {

    /** Batch fetch for a page of messages — avoids the per-message N+1. */
    List<MessageAttachment> findByMessage_IdIn(Collection<UUID> messageIds);
}
