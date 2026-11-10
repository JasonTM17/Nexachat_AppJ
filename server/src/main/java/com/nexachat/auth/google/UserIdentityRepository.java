package com.nexachat.auth.google;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserIdentityRepository extends JpaRepository<UserIdentity, UUID> {

    /** Global uniqueness lookup: one external account belongs to at most one NexaChat user. */
    Optional<UserIdentity> findByProviderAndSubject(String provider, String subject);

    Optional<UserIdentity> findByUser_IdAndProvider(UUID userId, String provider);

    List<UserIdentity> findAllByUser_IdOrderByLinkedAtAsc(UUID userId);
}
