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

public interface UserRepository extends JpaRepository<User, UUID> {

    /** Lookup by normalized E.164 phone — normalize BEFORE calling this. */
    Optional<User> findByPhone(String phone);

    boolean existsByPhone(String phone);

    /**
     * Access-token cutoff for the JWT filter's hot path, plus proof that the
     * account still exists. A projection, not the entity: the filter only
     * needs these two values and must not load (or lock) the whole user row.
     */
    @Query("SELECT new com.nexachat.user.UserAuthValidity(u.id, u.tokensValidAfter)"
            + " FROM User u WHERE u.id = :id")
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
              AND LOWER(u.displayName) LIKE LOWER(:prefix) || '%' ESCAPE '\\'
            ORDER BY u.displayName
            """)
    List<User> searchByDisplayNamePrefix(@Param("prefix") String prefix,
                                         @Param("excludeId") UUID excludeId,
                                         Pageable pageable);
}
