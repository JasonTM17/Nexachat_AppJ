package com.nexachat.auth;

import com.nexachat.auth.dto.AuthResponse;
import com.nexachat.auth.dto.ChangePasswordRequest;
import com.nexachat.auth.dto.RefreshRequest;
import com.nexachat.common.error.ApiException;
import com.nexachat.conversation.ConcurrentDatabaseITSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Characterizes serialized refresh replay against each session authority change in both orders. */
@Import(RefreshRotationReplayIT.ReplayTestConfiguration.class)
class RefreshAuthorityInterleavingIT extends ConcurrentDatabaseITSupport {
    @Autowired private AuthService auth;
    @Autowired private TokenService tokens;
    @Autowired private AccessTokenRevocationService revocation;
    @Autowired private RefreshReplayProperties replayProperties;
    @Autowired private RefreshRotationReplayIT.ReplayBoundaryProbe probe;
    @Autowired private RefreshRotationReplayIT.ReplayTestClock clock;
    @Autowired private PasswordEncoder passwords;
    @Autowired private PlatformTransactionManager transactions;

    enum Authority { LOGOUT_ALL, CUTOFF, PASSWORD }
    private record RefreshOutcome(AuthResponse response, String error) { }

    @BeforeEach
    void configureReplay() {
        probe.clear();
        replayProperties.setKey(Base64.getEncoder().encodeToString(new byte[32]));
        clock.set(Instant.now());
    }

    @AfterEach
    void clearReplay() {
        probe.clear();
        replayProperties.setKey("");
    }

    @ParameterizedTest
    @CsvSource({"LOGOUT_ALL,true", "LOGOUT_ALL,false", "CUTOFF,true", "CUTOFF,false", "PASSWORD,true", "PASSWORD,false"})
    void replayAndAuthorityChangeCannotForkOrResurrectARefreshLineage(Authority authority, boolean replayFirst)
            throws Exception {
        var owner = user("Session owner");
        jdbc.update("UPDATE users SET password_hash=? WHERE id=?", passwords.encode("original-password"), owner.getId());
        String predecessor = tokens.issue(owner, "current-device");
        UUID key = UUID.randomUUID();
        AuthResponse committed = auth.refresh(new RefreshRequest(predecessor), "current-device", key);
        if (authority == Authority.PASSWORD) tokens.issue(owner, "other-device");
        CountDownLatch ownsLock = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger blocker = new AtomicInteger();
        if (replayFirst) {
            probe.armOnce(RefreshReplayConfiguration.Boundary.AFTER_USER_LOCK,
                    RefreshReplayConfiguration.Operation.ROTATE, event -> {
                        blocker.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                        ownsLock.countDown();
                        await(release);
                    });
        }

        try (Workers workers = new Workers()) {
            if (replayFirst) {
                var replay = workers.submit(() -> refresh(predecessor, key));
                try {
                    await(ownsLock);
                    var change = workers.submit(() -> {
                        change(authority, owner.getId(), predecessor);
                        return true;
                    });
                    awaitBlocked(blocker.get(), 1);
                    release.countDown();
                    assertThat(result(replay).response).isEqualTo(committed);
                    assertThat(result(change)).isTrue();
                } finally {
                    release.countDown();
                }
            } else {
                var change = workers.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                    change(authority, owner.getId(), predecessor);
                    jdbc.queryForObject("SELECT 1", Integer.class);
                    blocker.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                    ownsLock.countDown();
                    await(release);
                    return true;
                }));
                try {
                    await(ownsLock);
                    var replay = workers.submit(() -> refresh(predecessor, key));
                    awaitBlocked(blocker.get(), 1);
                    release.countDown();
                    assertThat(result(change)).isTrue();
                    var outcome = result(replay);
                    if (authority == Authority.CUTOFF) {
                        assertThat(outcome.response).isEqualTo(committed);
                    } else {
                        assertThat(outcome.error).isEqualTo("REFRESH_RECOVERY_UNAVAILABLE");
                    }
                } finally {
                    release.countDown();
                }
            }
        }

        long expectedLive = authority == Authority.LOGOUT_ALL ? 0 : 1;
        assertThat(count("SELECT count(*) FROM refresh_tokens WHERE user_id=? AND revoked_at IS NULL", owner.getId()))
                .isEqualTo(expectedLive);
        // A recovery replays stored ciphertext; it never inserts another successor.
        assertThat(count("SELECT count(*) FROM refresh_tokens WHERE user_id=?", owner.getId()))
                .isEqualTo(authority == Authority.PASSWORD ? 3 : 2);
        assertThat(count("SELECT count(*) FROM users WHERE id=? AND tokens_valid_after IS NOT NULL", owner.getId())).isEqualTo(1);
        if (authority == Authority.LOGOUT_ALL) {
            assertThat(refresh(committed.refreshToken(), UUID.randomUUID()).response).isNull();
        } else {
            var next = refresh(committed.refreshToken(), UUID.randomUUID());
            assertThat(next.error).isNull();
            assertThat(next.response.refreshToken()).isNotEqualTo(committed.refreshToken());
            assertThat(count("SELECT count(*) FROM refresh_tokens WHERE user_id=? AND revoked_at IS NULL", owner.getId())).isEqualTo(1);
        }
    }

    private void change(Authority authority, UUID user, String predecessor) {
        switch (authority) {
            case LOGOUT_ALL -> auth.logoutAll(user);
            case CUTOFF -> revocation.revoke(user);
            case PASSWORD -> auth.changePassword(user,
                    new ChangePasswordRequest("original-password", "replacement-password", predecessor));
        }
    }

    private RefreshOutcome refresh(String token, UUID key) {
        try {
            return new RefreshOutcome(auth.refresh(new RefreshRequest(token), "current-device", key), null);
        } catch (ApiException denial) {
            return new RefreshOutcome(null, denial.getCode().name());
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(15, TimeUnit.SECONDS)).as("planned owner-lock boundary reached").isTrue();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }
}
