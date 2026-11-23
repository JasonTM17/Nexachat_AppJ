package com.nexachat.feature.shortcut;

import android.content.Context;
import android.content.Intent;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.core.graphics.drawable.IconCompat;
import com.nexachat.R;
import com.nexachat.core.network.TokenRefresher;
import com.nexachat.data.ConversationSummary;
import com.nexachat.notification.NotificationOpenActivity;
import com.nexachat.notification.NotificationWorkRequest;
import dagger.hilt.android.qualifiers.ApplicationContext;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Scheduler;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Mirrors the top inbox rows into launcher shortcuts so a long-press on the
 * app icon jumps straight into a conversation. The payloads re-validate the
 * session epoch on open, so shortcuts left behind by an old login open the
 * app instead of a dead chat.
 */
@Singleton
public final class ConversationShortcuts {

    private static final String ID_PREFIX = "conv:";
    private static final int MAX_SHORTCUTS = 4;

    private final Context context;
    private final TokenRefresher tokenRefresher;
    /**
     * One serial lane for publish and clear: a publish queued before a logout
     * must never land after the clear and resurrect the old account's rows.
     */
    private final Scheduler lane = Schedulers.from(Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "conversation-shortcuts");
        thread.setDaemon(true);
        return thread;
    }));
    @Nullable private String lastSignature;

    @Inject
    public ConversationShortcuts(
            @ApplicationContext Context context,
            TokenRefresher tokenRefresher) {
        this.context = Objects.requireNonNull(context, "context");
        this.tokenRefresher = Objects.requireNonNull(tokenRefresher, "tokenRefresher");
    }

    /**
     * Top conversations for the launcher: pinned first (newest pin first),
     * then by latest activity; archived rows never qualify.
     */
    @NonNull
    public static List<ConversationSummary> selectTargets(
            @NonNull List<ConversationSummary> all, int max) {
        if (max <= 0) {
            return List.of();
        }
        List<ConversationSummary> eligible = new ArrayList<>(all.size());
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (ConversationSummary conversation : all) {
            if (conversation.isArchived() || !seen.add(conversation.getId())) {
                continue;
            }
            eligible.add(conversation);
        }
        eligible.sort(ConversationSummary.INBOX_COMPARATOR);
        return eligible.size() <= max ? eligible : eligible.subList(0, max);
    }

    /**
     * Republishes the launcher shortcuts from the current inbox snapshot.
     * Runs off the main thread; failures (rate limits, OEM quirks) are
     * swallowed — shortcuts are a convenience, never worth a crash.
     */
    public void publish(
            @Nullable String recipientId,
            @NonNull List<ConversationSummary> conversations) {
        List<ConversationSummary> snapshot = List.copyOf(conversations);
        Completable.fromAction(() -> {
            long epoch = tokenRefresher.sessionEpoch();
            // A closed session keeps a positive (bumped) epoch, so admission
            // must be checked explicitly, not just the epoch sign.
            if (recipientId == null || recipientId.isBlank() || epoch <= 0L
                    || !tokenRefresher.isSessionCurrent(epoch)) {
                clearInternal();
                return;
            }
            int max = Math.min(MAX_SHORTCUTS,
                    ShortcutManagerCompat.getMaxShortcutCountPerActivity(context));
            List<ConversationSummary> targets = selectTargets(snapshot, max);
            String signature = signature(recipientId, epoch, targets);
            synchronized (this) {
                if (signature.equals(lastSignature)) {
                    return;
                }
            }
            List<ShortcutInfoCompat> shortcuts = new ArrayList<>(targets.size());
            for (int rank = 0; rank < targets.size(); rank++) {
                ConversationSummary conversation = targets.get(rank);
                String shortcutId = ID_PREFIX + conversation.getId();
                String label = conversation.getDisplayName().isBlank()
                        ? context.getString(R.string.chat_title)
                        : conversation.getDisplayName();
                Intent intent = new Intent(context, NotificationOpenActivity.class)
                        .setAction(Intent.ACTION_VIEW);
                NotificationWorkRequest.shortcut(recipientId, epoch, conversation.getId())
                        .putInIntent(intent);
                shortcuts.add(new ShortcutInfoCompat.Builder(context, shortcutId)
                        .setShortLabel(label)
                        .setLongLabel(label)
                        .setIcon(IconCompat.createWithResource(context,
                                conversation.isGroup()
                                        ? R.drawable.ic_people
                                        : R.drawable.ic_person_outline))
                        .setIntent(intent)
                        .setRank(rank)
                        .build());
            }
            try {
                // A pinned copy may carry a disabled flag from a previous
                // logout; re-enable so it works again.
                ShortcutManagerCompat.enableShortcuts(context, shortcuts);
            } catch (IllegalArgumentException | IllegalStateException ignored) {
            }
            try {
                ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts);
                synchronized (this) {
                    lastSignature = signature;
                }
            } catch (IllegalArgumentException | IllegalStateException ignored) {
            }
        }).subscribeOn(lane).subscribe(() -> { }, ignored -> { });
    }

    /**
     * Removes dynamic shortcuts and marks any user-pinned copies as disabled.
     * Called on every session-ending event (logout, session close, reauth).
     */
    public void clear() {
        Completable.fromAction(this::clearInternal)
                .subscribeOn(lane)
                .subscribe(() -> { }, ignored -> { });
    }

    private void clearInternal() {
        try {
            ShortcutManagerCompat.removeAllDynamicShortcuts(context);
        } catch (IllegalArgumentException | IllegalStateException ignored) {
        }
        try {
            List<String> pinnedIds = new ArrayList<>();
            for (ShortcutInfoCompat shortcut : ShortcutManagerCompat.getShortcuts(
                    context, ShortcutManagerCompat.FLAG_MATCH_PINNED)) {
                if (shortcut.getId().startsWith(ID_PREFIX)) {
                    pinnedIds.add(shortcut.getId());
                }
            }
            if (!pinnedIds.isEmpty()) {
                ShortcutManagerCompat.disableShortcuts(context, pinnedIds,
                        context.getString(R.string.shortcut_disabled_signed_out));
            }
        } catch (IllegalArgumentException | IllegalStateException | SecurityException ignored) {
        }
        synchronized (this) {
            lastSignature = null;
        }
    }

    private static String signature(
            @NonNull String recipientId, long epoch,
            @NonNull List<ConversationSummary> targets) {
        StringBuilder builder = new StringBuilder(recipientId)
                .append('|').append(epoch);
        for (ConversationSummary conversation : targets) {
            builder.append('|').append(conversation.getId())
                    .append(':').append(conversation.getDisplayName())
                    .append(':').append(conversation.isGroup() ? 'g' : 'd');
        }
        return builder.toString();
    }
}
