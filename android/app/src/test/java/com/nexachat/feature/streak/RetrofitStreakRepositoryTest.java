package com.nexachat.feature.streak;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.squareup.moshi.Moshi;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public final class RetrofitStreakRepositoryTest {

    private static final ZoneId DEVICE_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    /** 2026-03-14T18:30Z is already 2026-03-15 on the device. */
    private static final Instant NOW = Instant.parse("2026-03-14T18:30:00Z");

    private StreakApi api;
    private FakeStore store;
    private List<String> badgeWrites;
    private RetrofitStreakRepository repository;

    @Before public void setUp() {
        api = mock(StreakApi.class);
        store = new FakeStore();
        badgeWrites = new ArrayList<>();
        repository = new RetrofitStreakRepository(
                api,
                store,
                (conversationId, count, atRisk) ->
                        badgeWrites.add(conversationId + ":" + count + ":" + atRisk),
                Clock.fixed(NOW, DEVICE_ZONE));
    }

    @Test public void checkInIsDueWithoutAStoredDay() {
        assertTrue(repository.isCheckInDue());
    }

    @Test public void checkInIsDueOnTheNextDeviceDay() {
        store.lastCheckInDay = "2026-03-14";
        assertTrue(repository.isCheckInDue());

        store.lastCheckInDay = "2026-03-15";
        assertFalse(repository.isCheckInDue());
    }

    @Test public void checkInStoresTheDeviceDayNotTheServerDay() {
        Call<StreakDto> call = call();
        when(api.checkIn()).thenReturn(call);
        respondWith(call, "{\"checkIn\":{\"count\":4,\"day\":\"2026-03-16\"}}");
        RecordingCallback callback = new RecordingCallback();

        repository.checkIn(callback);

        assertEquals("2026-03-15", store.lastCheckInDay);
        assertEquals(1, callback.successes.size());
        assertEquals(4, callback.successes.get(0).checkInView().getCount());
    }

    @Test public void aFailedCheckInStoresNothing() {
        Call<StreakDto> call = call();
        when(api.checkIn()).thenReturn(call);
        doAnswer(invocation -> {
            Callback<StreakDto> callback = invocation.getArgument(0);
            callback.onFailure(call, new IOException("offline"));
            return null;
        }).when(call).enqueue(any());
        RecordingCallback callback = new RecordingCallback();

        repository.checkIn(callback);

        assertNull(store.lastCheckInDay);
        assertEquals(1, callback.failures);
    }

    @Test public void anEmptyCheckInBodyFails() {
        Call<StreakDto> call = call();
        when(api.checkIn()).thenReturn(call);
        doAnswer(invocation -> {
            Callback<StreakDto> callback = invocation.getArgument(0);
            callback.onResponse(call, Response.success(204, null));
            return null;
        }).when(call).enqueue(any());
        RecordingCallback callback = new RecordingCallback();

        repository.checkIn(callback);

        assertNull(store.lastCheckInDay);
        assertEquals(1, callback.failures);
    }

    @Test public void loadConversationCachesTheBadge() {
        Call<StreakDto> call = call();
        when(api.getConversationStreak("conversation-1")).thenReturn(call);
        respondWith(call, "{\"count\":6,\"atRisk\":true}");

        repository.loadConversation("conversation-1", new RecordingCallback());

        assertEquals(List.of("conversation-1:6:true"), badgeWrites);
    }

    @Test public void loadMineDoesNotTouchTheBadgeCache() {
        Call<StreakDto> call = call();
        when(api.getMine()).thenReturn(call);
        respondWith(call, "{\"checkIn\":{\"count\":6,\"reminderEnabled\":true}}");

        repository.loadMine(new RecordingCallback());

        assertTrue(badgeWrites.isEmpty());
    }

    @Test public void aFailedConversationLoadLeavesTheBadgeAlone() {
        Call<StreakDto> call = call();
        when(api.getConversationStreak("conversation-1")).thenReturn(call);
        doAnswer(invocation -> {
            Callback<StreakDto> callback = invocation.getArgument(0);
            callback.onFailure(call, new IOException("offline"));
            return null;
        }).when(call).enqueue(any());

        repository.loadConversation("conversation-1", new RecordingCallback());

        assertTrue(badgeWrites.isEmpty());
    }

    @SuppressWarnings("unchecked")
    private static Call<StreakDto> call() {
        return mock(Call.class);
    }

    private static void respondWith(Call<StreakDto> call, String json) {
        StreakDto body = parse(json);
        doAnswer(invocation -> {
            Callback<StreakDto> callback = invocation.getArgument(0);
            callback.onResponse(call, Response.success(body));
            return null;
        }).when(call).enqueue(any());
    }

    private static StreakDto parse(String json) {
        try {
            return new Moshi.Builder().build().adapter(StreakDto.class).fromJson(json);
        } catch (IOException malformed) {
            throw new AssertionError(malformed);
        }
    }

    private static final class RecordingCallback implements StreakRepository.Callback<StreakDto> {
        private final List<StreakDto> successes = new ArrayList<>();
        private int failures;

        @Override public void onSuccess(StreakDto value) {
            successes.add(value);
        }

        @Override public void onFailure() {
            failures++;
        }
    }

    private static final class FakeStore implements StreakStore {
        private String lastCheckInDay;

        @Override public String lastCheckInDay() {
            return lastCheckInDay;
        }

        @Override public void saveLastCheckInDay(String day) {
            lastCheckInDay = day;
        }

        @Override public boolean isCelebrated(String key) {
            return false;
        }

        @Override public void markCelebrated(String key) { }

        @Override public void clear() {
            lastCheckInDay = null;
        }
    }
}
