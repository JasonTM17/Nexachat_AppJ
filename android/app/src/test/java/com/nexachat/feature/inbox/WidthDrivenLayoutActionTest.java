package com.nexachat.feature.inbox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** Retry-until-measured semantics of {@link WidthDrivenLayoutAction}. */
public final class WidthDrivenLayoutActionTest {

    @Test
    public void aZeroWidthSkipsTheApplySoALaterLayoutRetryCanRun() {
        int[] width = {0};
        List<Integer> applied = new ArrayList<>();
        WidthDrivenLayoutAction action =
                new WidthDrivenLayoutAction(() -> width[0], applied::add);

        assertFalse(action.run());
        assertTrue(applied.isEmpty());
    }

    @Test
    public void theRetryAppliesOnceTheMeasuredWidthArrives() {
        int[] width = {0};
        List<Integer> applied = new ArrayList<>();
        WidthDrivenLayoutAction action =
                new WidthDrivenLayoutAction(() -> width[0], applied::add);

        action.run();
        action.run();
        assertTrue("no width yet means no apply", applied.isEmpty());
        width[0] = 396;
        assertTrue(action.run());
        assertEquals(List.of(396), applied);
    }

    @Test
    public void everyRealWidthRunsTheApplySoTheDecisionReevaluates() {
        int[] width = {396};
        List<Integer> applied = new ArrayList<>();
        WidthDrivenLayoutAction action =
                new WidthDrivenLayoutAction(() -> width[0], applied::add);

        action.run();
        width[0] = 612;
        action.run();
        assertEquals(List.of(396, 612), applied);
    }
}
