package com.nexachat.conversation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ConversationRepository extends JpaRepository<Conversation, UUID> {

    /** Canonical DIRECT pair-key lookup — the idempotency anchor of open-direct. */
    Optional<Conversation> findByDirectKey(String directKey);
}
