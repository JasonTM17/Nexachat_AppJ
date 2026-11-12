package com.nexachat.user;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;

public interface UserRepository extends JpaRepository<User, UUID> {

    /** Active (non-deleted) account with this phone, for login/register/search. */
    Optional<User> findByPhoneAndDeletedAtIsNull(String phone);

    /** Active user with a verified recovery email, for exact email search and add-by-email. */
    @Query("""
            SELECT u FROM User u
            WHERE u.email = :email
              AND u.emailVerifiedAt IS NOT NULL
              AND u.deletedAt IS NULL
            """)
    Optional<User> findActiveByVerifiedEmail(@Param("email") String email);

    boolean existsByPhoneAndDeletedAtIsNull(String phone);

    @Query("SELECT CASE WHEN COUNT(u) > 0 THEN true ELSE false END FROM User u"
            + " WHERE u.email = :email AND u.emailVerifiedAt IS NOT NULL"
            + " AND u.deletedAt IS NULL AND u.id <> :userId")
    boolean existsVerifiedEmailForOtherUser(@Param("email") String email, @Param("userId") UUID userId);

    /**
     * Access-token cutoff for the JWT filter's hot path, plus proof that the
     * account still exists. A projection, not the entity: the filter only
     * needs these two values and must not load (or lock) the whole user row.
     */
    @Query("SELECT new com.nexachat.user.UserAuthValidity(u.id, u.tokensValidAfter)"
            + " FROM User u WHERE u.id = :id AND u.deletedAt IS NULL")
    Optional<UserAuthValidity> findAuthValidityById(@Param("id") UUID id);

    /**
     * The read-receipt privacy flag for the outbound receipt filter and the
     * AFTER_COMMIT broadcast gate. A projection, not the entity: neither path
     * needs the whole row, and the outbound path runs per recipient session.
     */
    @Query("SELECT u.showSeen FROM User u WHERE u.id = :id")
    Optional<Boolean> findShowSeenById(@Param("id") UUID id);

    /**
     * {@code SELECT ... FOR UPDATE} on the user row — serializes the
     * per-user conversation pin ceiling so concurrent NEW pins cannot both
     * consume the same remaining slot.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") UUID id);

    /** Bounded UUID keyset page used by opt-in Firebase projection backfill. */
    @Query("SELECT u.id FROM User u WHERE (:afterId IS NULL OR u.id > :afterId) ORDER BY u.id")
    List<UUID> findIdsAfter(@Param("afterId") UUID afterId, Pageable pageable);

    /**
     * Locks a set of user rows in a stable order before creating relationships
     * that refer to them. Callers must pass a non-empty id list.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.id IN :ids AND u.deletedAt IS NULL ORDER BY u.id")
    List<User> findActiveUsersByIdsForUpdate(@Param("ids") List<UUID> ids);

    /**
     * Case-insensitive {@code displayName} prefix search for GET /users/search?q=.
     * The caller is always excluded. {@code prefix} must arrive LIKE-escaped
     * ({@code \\}, {@code %}, {@code _}) — see {@link UserService#search} — so a
     * literal {@code %} in the query cannot widen into a wildcard scan.
     * Case folding via {@code LOWER()} keeps this portable JPQL (no vendor ILIKE).
     * The row cap comes from the {@link Pageable}, keeping LIMIT out of the JPQL.
     */
    @Query("""
            SELECT u FROM User u
            WHERE u.id <> :excludeId
              AND u.deletedAt IS NULL
              AND LOWER(u.displayName) LIKE LOWER(:prefix) || '%' ESCAPE '\\'
            ORDER BY u.displayName
            """)
    List<User> searchByDisplayNamePrefix(@Param("prefix") String prefix,
                                         @Param("excludeId") UUID excludeId,
                                         Pageable pageable);
}
