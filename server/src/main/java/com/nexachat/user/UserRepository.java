package com.nexachat.user;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
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
