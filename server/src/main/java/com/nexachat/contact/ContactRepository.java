package com.nexachat.contact;

import com.nexachat.contact.dto.ContactResponse;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ContactRepository extends JpaRepository<Contact, ContactId> {

    /**
     * Which of {@code contactUserIds} the owner already has in their address book.
     * Used to decide whether display-name search may disclose phone numbers.
     */
    @Query("""
            SELECT c.contactUser.id FROM Contact c
            WHERE c.owner.id = :ownerId AND c.contactUser.id IN :contactUserIds
            """)
    List<UUID> findContactUserIdsAmong(@Param("ownerId") UUID ownerId,
                                       @Param("contactUserIds") Collection<UUID> contactUserIds);

    /**
     * Contact-list read model: one JOIN to {@code users} per row, returning the
     * response projection directly (plan §9.3.7 — projection, not entity graph,
     * for list reads). Ordered by display name for a stable client experience.
     */
    @Query("""
            SELECT new com.nexachat.contact.dto.ContactResponse(
                       u.id, u.phone, u.displayName, c.aliasName, u.avatarUrl, c.createdAt)
            FROM Contact c JOIN c.contactUser u
            WHERE c.owner.id = :ownerId
            ORDER BY u.displayName
            """)
    List<ContactResponse> findAllByOwnerId(@Param("ownerId") UUID ownerId, Pageable pageable);
}
