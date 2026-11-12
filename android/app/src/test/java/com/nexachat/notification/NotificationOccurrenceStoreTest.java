package com.nexachat.notification;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class NotificationOccurrenceStoreTest {
    @Test
    public void persistedOccurrenceSurvivesStoreRecreationAndFailedPublishCanRetry() {
        InMemoryPreferences backing = new InMemoryPreferences();
        NotificationOccurrenceStore first = new NotificationOccurrenceStore(backing.context());
        AtomicInteger publishes = new AtomicInteger();

        assertFalse(first.deliverOnce("message-occurrence", () -> false));
        NotificationOccurrenceStore retry = new NotificationOccurrenceStore(backing.context());
        assertTrue(retry.deliverOnce("message-occurrence", () -> {
            publishes.incrementAndGet();
            return true;
        }));

        NotificationOccurrenceStore recreated = new NotificationOccurrenceStore(backing.context());
        assertTrue(recreated.deliverOnce("message-occurrence", () -> {
            publishes.incrementAndGet();
            return true;
        }));
        assertEquals(1, publishes.get());
    }

    private static final class InMemoryPreferences {
        private final Map<String, Object> values = new HashMap<>();
        private final SharedPreferences preferences = mock(SharedPreferences.class);

        private InMemoryPreferences() {
            when(preferences.getLong(anyString(), anyLong())).thenAnswer(invocation -> {
                Object value = values.get(invocation.getArgument(0));
                return value instanceof Long timestamp ? timestamp : invocation.getArgument(1);
            });
            when(preferences.getAll()).thenAnswer(ignored -> new HashMap<>(values));
            when(preferences.edit()).thenAnswer(ignored -> editor());
        }

        private Context context() {
            Context context = mock(Context.class);
            when(context.getApplicationContext()).thenReturn(context);
            when(context.getSharedPreferences("notification_occurrences", Context.MODE_PRIVATE))
                    .thenReturn(preferences);
            return context;
        }

        private SharedPreferences.Editor editor() {
            SharedPreferences.Editor editor = mock(SharedPreferences.Editor.class);
            Map<String, Object> writes = new HashMap<>();
            Set<String> removals = new HashSet<>();
            when(editor.putLong(anyString(), anyLong())).thenAnswer(invocation -> {
                String key = invocation.getArgument(0);
                writes.put(key, invocation.getArgument(1));
                removals.remove(key);
                return editor;
            });
            when(editor.remove(anyString())).thenAnswer(invocation -> {
                String key = invocation.getArgument(0);
                writes.remove(key);
                removals.add(key);
                return editor;
            });
            when(editor.commit()).thenAnswer(ignored -> {
                removals.forEach(values::remove);
                values.putAll(writes);
                return true;
            });
            return editor;
        }
    }
}
