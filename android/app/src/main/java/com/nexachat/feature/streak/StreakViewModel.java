package com.nexachat.feature.streak;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import com.nexachat.core.network.ApiCalls;
import dagger.hilt.android.lifecycle.HiltViewModel;
import java.util.Objects;
import javax.inject.Inject;

/**
 * Streak state for the screen that owns it: one direct conversation, or the check-in streak
 * for Inbox and Profile. Milestones found in a fetched streak go through
 * {@link MilestoneTracker}, which dedupes them and keeps two sheets from showing at once.
 */
@HiltViewModel
public final class StreakViewModel extends ViewModel {

    private final StreakRepository repository;
    private final MilestoneTracker tracker;
    private final MutableLiveData<StreakDto> conversationStreak = new MutableLiveData<>();
    private final MutableLiveData<StreakDto> checkInStreak = new MutableLiveData<>();
    private final MutableLiveData<MilestoneTracker.Celebration> celebration = new MutableLiveData<>();

    @Nullable private String conversationId;
    @Nullable private ApiCalls.Cancellable conversationCall;
    @Nullable private ApiCalls.Cancellable mineCall;
    private boolean celebrateConversation;

    @Inject
    public StreakViewModel(@NonNull StreakRepository repository, @NonNull MilestoneTracker tracker) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.tracker = Objects.requireNonNull(tracker, "tracker");
    }

    /** The latest conversation streak; null until the first successful fetch. */
    public LiveData<StreakDto> getConversationStreak() {
        return conversationStreak;
    }

    /** The latest check-in streak; null until the first successful fetch. */
    public LiveData<StreakDto> getCheckInStreak() {
        return checkInStreak;
    }

    /** A celebration to show now; consumed through {@link #onCelebrationShown()}. */
    public LiveData<MilestoneTracker.Celebration> getCelebration() {
        return celebration;
    }

    /**
     * Fetches the streak of the open direct chat. {@code celebrate} lets the chat screen show
     * milestones for it; pass false while the chat is not on screen.
     */
    public void loadConversation(@NonNull String id, boolean celebrate) {
        if (!id.equals(conversationId)) {
            conversationId = id;
            conversationStreak.setValue(null);
        }
        celebrateConversation = celebrate;
        cancel(conversationCall);
        conversationCall = repository.loadConversation(id, new StreakRepository.Callback<>() {
            @Override
            public void onSuccess(@NonNull StreakDto value) {
                if (!id.equals(conversationId)) {
                    return;
                }
                conversationStreak.setValue(value);
                if (celebrateConversation) {
                    tracker.offer(id, value);
                    publishCelebration();
                }
            }

            @Override
            public void onFailure() { }
        });
    }

    /** A chat message was sent or received: refreshes only while today's outcome is still open. */
    public void onChatActivity() {
        String id = conversationId;
        if (id != null && StreakRules.needsRefreshAfterActivity(conversationStreak.getValue())) {
            loadConversation(id, celebrateConversation);
        }
    }

    /** Fetches the check-in streak; its milestone shows on the screen that asked. */
    public void loadMine() {
        cancel(mineCall);
        mineCall = repository.loadMine(new StreakRepository.Callback<>() {
            @Override
            public void onSuccess(@NonNull StreakDto value) {
                StreakDto view = value.checkInView();
                checkInStreak.setValue(value);
                tracker.offer(MilestoneTracker.SCOPE_CHECK_IN, view);
                publishCelebration();
            }

            @Override
            public void onFailure() { }
        });
    }

    /** The screen put the sheet up; clears the one-shot event. */
    public void onCelebrationShown() {
        celebration.setValue(null);
    }

    /** The sheet closed; the next queued celebration may be handed out. */
    public void onCelebrationDismissed() {
        tracker.dismissed();
        publishCelebration();
    }

    /** The screen is no longer visible, so its queued milestones must not show later. */
    public void onScreenHidden() {
        if (conversationId != null) {
            tracker.discardPending(conversationId);
        }
        tracker.discardPending(MilestoneTracker.SCOPE_CHECK_IN);
    }

    private void publishCelebration() {
        if (celebration.getValue() != null) {
            return;
        }
        MilestoneTracker.Celebration next = tracker.next();
        if (next != null) {
            celebration.setValue(next);
        }
    }

    private static void cancel(@Nullable ApiCalls.Cancellable call) {
        if (call != null) {
            call.cancel();
        }
    }

    @Override
    protected void onCleared() {
        cancel(conversationCall);
        cancel(mineCall);
        onScreenHidden();
        super.onCleared();
    }
}
