package com.nexachat.notification;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserDeviceTokenRepository extends JpaRepository<UserDeviceToken, UUID> {

    @Query("SELECT t FROM UserDeviceToken t WHERE t.user.id = :userId AND t.token = :token")
    Optional<UserDeviceToken> findByUserIdAndToken(@Param("userId") UUID userId, @Param("token") String token);

    @Query("SELECT t FROM UserDeviceToken t WHERE t.user.id = :userId")
    List<UserDeviceToken> findByUserId(@Param("userId") UUID userId);

    @Query("SELECT t FROM UserDeviceToken t WHERE t.user.id IN :userIds")
    List<UserDeviceToken> findByUserIdIn(@Param("userIds") Collection<UUID> userIds);

    @Query("SELECT t FROM UserDeviceToken t WHERE t.token = :token")
    Optional<UserDeviceToken> findByToken(@Param("token") String token);

    @org.springframework.transaction.annotation.Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM UserDeviceToken t WHERE t.user.id = :userId AND t.token = :token")
    int deleteByUserIdAndToken(@Param("userId") UUID userId, @Param("token") String token);

    @org.springframework.transaction.annotation.Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM UserDeviceToken t WHERE t.token = :token")
    int deleteByToken(@Param("token") String token);

    @org.springframework.transaction.annotation.Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM UserDeviceToken t WHERE t.user.id = :userId")
    int deleteAllByUserId(@Param("userId") UUID userId);
}
