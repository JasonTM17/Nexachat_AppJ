package com.nexachat.streak;

import com.nexachat.streak.StreakRules.Side;
import com.nexachat.streak.StreakRules.State;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Types;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * SQL for {@code conversation_streaks} and {@code user_streaks}. Writes read
 * the row {@code FOR UPDATE}, run the pure {@link StreakRules} and write back
 * only when the state changed; a missing row is created with a single
 * {@code INSERT ... ON CONFLICT DO NOTHING} that already carries the computed
 * state, so a concurrent creator simply sends the loser down the locked path.
 */
@Component
@Profile("!test")
class StreakStore {

    private static final Logger log = LoggerFactory.getLogger(StreakStore.class);

    /** A conversation chain together with which member is which side. */
    record ChainRow(UUID lowUserId, UUID highUserId, State state) {

        /** The side the given member is on (the low-ordered member is A). */
        Side sideOf(UUID userId) {
            return lowUserId.equals(userId) ? Side.A : Side.B;
        }
    }

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    StreakStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
    }

    /**
     * Runs {@code work} inside a savepoint of the caller's transaction so a
     * failing statement cannot poison it. Returns {@code false} after rolling
     * back to the savepoint and logging; outside a transaction the work simply
     * runs in autocommit.
     */
    boolean guarded(String what, Runnable work) {
        Boolean ok = jdbc.execute((ConnectionCallback<Boolean>) connection -> {
            Savepoint savepoint = connection.getAutoCommit() ? null : connection.setSavepoint();
            try {
                work.run();
                if (savepoint != null) {
                    connection.releaseSavepoint(savepoint);
                }
                return true;
            } catch (RuntimeException failure) {
                log.warn("Streak bookkeeping skipped ({}): {}", what, failure.getClass().getSimpleName());
                if (savepoint != null) {
                    try {
                        connection.rollback(savepoint);
                    } catch (SQLException rollbackFailure) {
                        // The transaction itself is no longer usable; let the caller see it.
                        throw rollbackFailure;
                    }
                }
                return false;
            }
        });
        return Boolean.TRUE.equals(ok);
    }

    // ------------------------------------------------------------ conversation

    void applyChain(UUID conversationId, UUID lowUserId, UUID highUserId, Side side, LocalDate day) {
        State current = lockChain(conversationId);
        if (current == null) {
            State created = StreakRules.record(State.EMPTY, side, day);
            if (insertChain(conversationId, lowUserId, highUserId, created) == 1) {
                return;
            }
            current = lockChain(conversationId);
            if (current == null) {
                throw new IllegalStateException("conversation streak row vanished");
            }
        }
        State next = StreakRules.record(current, side, day);
        if (!next.equals(current)) {
            jdbc.update("""
                    UPDATE conversation_streaks
                    SET low_day = ?, high_day = ?, count = ?, best_count = ?, last_day = ?,
                        started_on = ?, freeze_used_on = ?, low_cont = ?, high_cont = ?,
                        updated_at = now()
                    WHERE conversation_id = ?
                    """, ps -> {
                bindState(ps, 1, next);
                ps.setObject(10, conversationId);
            });
        }
    }

    private State lockChain(UUID conversationId) {
        return jdbc.query("""
                SELECT low_day, high_day, count, best_count, last_day, started_on, freeze_used_on,
                       low_cont, high_cont
                FROM conversation_streaks WHERE conversation_id = ? FOR UPDATE
                """, rs -> rs.next() ? readState(rs) : null, conversationId);
    }

    private int insertChain(UUID conversationId, UUID low, UUID high, State state) {
        return jdbc.update("""
                INSERT INTO conversation_streaks
                    (low_day, high_day, count, best_count, last_day, started_on, freeze_used_on,
                     low_cont, high_cont, conversation_id, user_low_id, user_high_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (conversation_id) DO NOTHING
                """, ps -> {
            bindState(ps, 1, state);
            ps.setObject(10, conversationId);
            ps.setObject(11, low);
            ps.setObject(12, high);
        });
    }

    Optional<ChainRow> findChain(UUID conversationId) {
        return Optional.ofNullable(jdbc.query("""
                SELECT user_low_id, user_high_id, low_day, high_day, count, best_count, last_day,
                       started_on, freeze_used_on, low_cont, high_cont
                FROM conversation_streaks WHERE conversation_id = ?
                """, rs -> rs.next() ? readChain(rs) : null, conversationId));
    }

    /** One query for every conversation on an inbox page. */
    Map<UUID, ChainRow> findChains(Collection<UUID> conversationIds) {
        Map<UUID, ChainRow> result = new HashMap<>();
        if (conversationIds.isEmpty()) {
            return result;
        }
        named.query("""
                SELECT conversation_id, user_low_id, user_high_id, low_day, high_day, count,
                       best_count, last_day, started_on, freeze_used_on, low_cont, high_cont
                FROM conversation_streaks WHERE conversation_id IN (:ids)
                """, new MapSqlParameterSource("ids", conversationIds), rs -> {
            result.put(rs.getObject("conversation_id", UUID.class), readChain(rs));
        });
        return result;
    }

    // -------------------------------------------------------------------- user

    void applyUser(UUID userId, Side side, LocalDate day) {
        State current = lockUser(userId);
        if (current == null) {
            State created = StreakRules.record(State.EMPTY, side, day);
            int inserted = jdbc.update("""
                    INSERT INTO user_streaks
                        (open_day, sent_day, count, best_count, last_day, started_on,
                         freeze_used_on, open_cont, sent_cont, user_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (user_id) DO NOTHING
                    """, ps -> {
                bindState(ps, 1, created);
                ps.setObject(10, userId);
            });
            if (inserted == 1) {
                return;
            }
            current = lockUser(userId);
            if (current == null) {
                throw new IllegalStateException("user streak row vanished");
            }
        }
        State next = StreakRules.record(current, side, day);
        if (!next.equals(current)) {
            jdbc.update("""
                    UPDATE user_streaks
                    SET open_day = ?, sent_day = ?, count = ?, best_count = ?, last_day = ?,
                        started_on = ?, freeze_used_on = ?, open_cont = ?, sent_cont = ?,
                        updated_at = now()
                    WHERE user_id = ?
                    """, ps -> {
                bindState(ps, 1, next);
                ps.setObject(10, userId);
            });
        }
    }

    private State lockUser(UUID userId) {
        return jdbc.query("""
                SELECT open_day AS low_day, sent_day AS high_day, count, best_count, last_day,
                       started_on, freeze_used_on, open_cont AS low_cont, sent_cont AS high_cont
                FROM user_streaks WHERE user_id = ? FOR UPDATE
                """, rs -> rs.next() ? readState(rs) : null, userId);
    }

    /** The user's check-in state; side A is "opened the app", side B "sent something". */
    State findUser(UUID userId) {
        State state = jdbc.query("""
                SELECT open_day AS low_day, sent_day AS high_day, count, best_count, last_day,
                       started_on, freeze_used_on, open_cont AS low_cont, sent_cont AS high_cont
                FROM user_streaks WHERE user_id = ?
                """, rs -> rs.next() ? readState(rs) : null, userId);
        return state == null ? State.EMPTY : state;
    }

    boolean reminderEnabled(UUID userId) {
        Boolean enabled = jdbc.query(
                "SELECT reminder_enabled FROM user_streaks WHERE user_id = ?",
                rs -> rs.next() ? rs.getBoolean(1) : Boolean.FALSE, userId);
        return Boolean.TRUE.equals(enabled);
    }

    void setReminderEnabled(UUID userId, boolean enabled) {
        jdbc.update("""
                INSERT INTO user_streaks (user_id, reminder_enabled) VALUES (?, ?)
                ON CONFLICT (user_id) DO UPDATE
                SET reminder_enabled = EXCLUDED.reminder_enabled, updated_at = now()
                """, userId, enabled);
    }

    // ----------------------------------------------------------------- mapping

    private static ChainRow readChain(ResultSet rs) throws SQLException {
        return new ChainRow(rs.getObject("user_low_id", UUID.class),
                rs.getObject("user_high_id", UUID.class), readState(rs));
    }

    private static State readState(ResultSet rs) throws SQLException {
        return new State(date(rs, "low_day"), date(rs, "high_day"), rs.getInt("count"),
                rs.getInt("best_count"), date(rs, "last_day"), date(rs, "started_on"),
                date(rs, "freeze_used_on"), date(rs, "low_cont"), date(rs, "high_cont"));
    }

    private static LocalDate date(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, LocalDate.class);
    }

    /** Binds the nine state columns starting at {@code index}. */
    private static void bindState(PreparedStatement ps, int index, State state) throws SQLException {
        setDate(ps, index, state.dayA());
        setDate(ps, index + 1, state.dayB());
        ps.setInt(index + 2, state.count());
        ps.setInt(index + 3, state.bestCount());
        setDate(ps, index + 4, state.lastDay());
        setDate(ps, index + 5, state.startedOn());
        setDate(ps, index + 6, state.freezeUsedOn());
        setDate(ps, index + 7, state.contA());
        setDate(ps, index + 8, state.contB());
    }

    private static void setDate(PreparedStatement ps, int index, LocalDate value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.DATE);
        } else {
            ps.setObject(index, value, Types.DATE);
        }
    }
}
