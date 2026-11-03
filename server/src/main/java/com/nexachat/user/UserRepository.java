package com.nexachat.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    /** Lookup by normalized E.164 phone — normalize BEFORE calling this. */
    Optional<User> findByPhone(String phone);

    boolean existsByPhone(String phone);
}
