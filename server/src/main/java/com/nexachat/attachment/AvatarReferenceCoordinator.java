package com.nexachat.attachment;

import com.nexachat.ops.ScheduledAdvisoryLock;
import com.nexachat.ops.ScheduledLockIds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.PreparedStatement;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Serializes avatar reference changes and file removal with the orphan sweep. */
@Component
@Profile("!test")
public class AvatarReferenceCoordinator {

    private static final Logger log = LoggerFactory.getLogger(AvatarReferenceCoordinator.class);

    private final JdbcTemplate jdbcTemplate;
    private final ScheduledAdvisoryLock advisoryLock;
    private final StorageService storageService;

    public AvatarReferenceCoordinator(
            JdbcTemplate jdbcTemplate,
            ScheduledAdvisoryLock advisoryLock,
            StorageService storageService) {
        this.jdbcTemplate = jdbcTemplate;
        this.advisoryLock = advisoryLock;
        this.storageService = storageService;
    }

    /** Must be called inside the transaction that changes a user/group avatar reference. */
    public void lockForTransaction() {
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            try (PreparedStatement statement = connection.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
                statement.setLong(1, ScheduledLockIds.AVATAR_ORPHAN_SWEEP);
                statement.execute();
            }
            return null;
        });
    }

    public Set<String> referencedAvatarKeys() {
        List<String> urls = jdbcTemplate.queryForList("""
                SELECT avatar_url FROM users
                WHERE avatar_url LIKE '%/api/v1/files/%'
                UNION
                SELECT avatar_url FROM conversations
                WHERE avatar_url LIKE '%/api/v1/files/%'
                """, String.class);
        Set<String> keys = new HashSet<>();
        for (String url : urls) {
            String key = AvatarUrlPolicy.storageKeyFromUrl(url);
            if (key != null) {
                keys.add(key);
            }
        }
        return keys;
    }

    /** Deletes a superseded key only when no committed row references it. */
    public void deleteIfUnreferenced(String storageKey) {
        if (storageKey == null) {
            return;
        }
        try {
            advisoryLock.withLock(ScheduledLockIds.AVATAR_ORPHAN_SWEEP, () -> {
                if (!referencedAvatarKeys().contains(storageKey)) {
                    storageService.deleteIfExists(storageKey);
                }
                return Boolean.TRUE;
            });
        } catch (RuntimeException failure) {
            log.warn("Avatar cleanup deferred ({})", failure.getClass().getSimpleName());
        }
    }

    public void deleteIfUnreferencedAfterCommit(String storageKey) {
        if (storageKey == null) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            deleteIfUnreferenced(storageKey);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                deleteIfUnreferenced(storageKey);
            }
        });
    }
}
