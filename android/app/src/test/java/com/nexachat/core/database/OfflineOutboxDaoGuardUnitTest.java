package com.nexachat.core.database;

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

/**
 * The Completable finalizer pair must carry the same lease guard as the
 * Blocking pair beside it: only the worker that still holds the
 * {@code IN_PROGRESS} lease may finalize a row, so a stolen 60-second lease
 * cannot be finalized by the worker that lost it (audit C1-04).
 *
 * <p>Room's {@code @Query} retention is CLASS, so the guard is pinned against
 * the DAO source text here; Room validates the SQL itself at compile time and
 * the instrumented suite exercises the real database.
 */
public final class OfflineOutboxDaoGuardUnitTest {

    @Test public void markSentOnlyFinalizesLeasedRows() throws Exception {
        String sql = daoSourceSql("markSent");
        assertTrue("markSent must keep the IN_PROGRESS lease guard:\n" + sql,
                sql.contains("AND state = 'IN_PROGRESS'"));
    }

    @Test public void markFailedOnlyFinalizesLeasedRows() throws Exception {
        String sql = daoSourceSql("markFailed");
        assertTrue("markFailed must keep the IN_PROGRESS lease guard:\n" + sql,
                sql.contains("AND state = 'IN_PROGRESS'"));
    }

    private static String daoSourceSql(String method) throws Exception {
        Path source = Path.of("src/main/java/com/nexachat/core/database/OfflineOutboxDao.java");
        if (!Files.exists(source)) {
            source = Path.of("android/app").resolve(source);
        }
        String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
        int methodIndex = text.indexOf(method + "(String id, long now)");
        assertTrue("missing " + method + " in " + source, methodIndex >= 0);
        int from = Math.max(0, text.lastIndexOf("@Query", methodIndex));
        return text.substring(from, methodIndex);
    }
}
