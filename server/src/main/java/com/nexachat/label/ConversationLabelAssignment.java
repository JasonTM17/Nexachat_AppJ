package com.nexachat.label;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/** Minimal JPA mapping used by the inbox JPQL label filter. */
@Entity
@Table(name = "conversation_label_assignments")
public class ConversationLabelAssignment {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "label_id", nullable = false)
    private UUID labelId;

    protected ConversationLabelAssignment() {
    }
}
