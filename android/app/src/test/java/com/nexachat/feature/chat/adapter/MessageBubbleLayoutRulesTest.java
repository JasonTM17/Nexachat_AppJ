package com.nexachat.feature.chat.adapter;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class MessageBubbleLayoutRulesTest {

    @Test
    public void normalWidthUsesTheSmallerOf280AndEightyPercent() {
        assertEquals(256, MessageBubbleLayoutRules.maxBubbleWidthPx(320, 1f, 1f));
        assertEquals(280, MessageBubbleLayoutRules.maxBubbleWidthPx(360, 1f, 1f));
        assertEquals(280, MessageBubbleLayoutRules.maxBubbleWidthPx(1024, 1f, 1f));
        assertEquals(80, MessageBubbleLayoutRules.maxBubbleWidthPx(100, 1f, 1f));
    }

    @Test
    public void largeTypeUses480OrInnerWidthMinus32() {
        assertEquals(288, MessageBubbleLayoutRules.maxBubbleWidthPx(320, 2f, 1f));
        assertEquals(480, MessageBubbleLayoutRules.maxBubbleWidthPx(1200, 1.5f, 1f));
        assertEquals(0, MessageBubbleLayoutRules.maxBubbleWidthPx(20, 2f, 1f));
    }

    @Test
    public void zeroWidthBeforeMeasureStaysZeroAndScaleCanReturn() {
        assertEquals(0, MessageBubbleLayoutRules.maxBubbleWidthPx(0, 1f, 1f));
        assertEquals(0, MessageBubbleLayoutRules.maxBubbleWidthPx(360, 1f, 0f));
        assertEquals(280, MessageBubbleLayoutRules.maxBubbleWidthPx(360, 2f, 1f) > 0
                ? MessageBubbleLayoutRules.maxBubbleWidthPx(360, 1f, 1f)
                : -1);
        assertEquals(280, MessageBubbleLayoutRules.maxBubbleWidthPx(360, 1f, 1f));
    }

    @Test
    public void guidesPreserveIncomingAndOutgoingBias() {
        int inner = 360;
        int max = MessageBubbleLayoutRules.maxBubbleWidthPx(inner, 1f, 1f);
        float start = MessageBubbleLayoutRules.startGuidePercent(max, inner);
        float end = MessageBubbleLayoutRules.endGuidePercent(max, inner);
        assertEquals(1f - (280f / 360f), start, 0.001f);
        assertEquals(280f / 360f, end, 0.001f);
        assertTrue(start < end);
        assertEquals(0f, MessageBubbleLayoutRules.startGuidePercent(0, 0), 0.001f);
        assertEquals(1f, MessageBubbleLayoutRules.endGuidePercent(0, 0), 0.001f);
    }

    @Test
    public void densityScalesTheDpCaps() {
        assertEquals(560, MessageBubbleLayoutRules.maxBubbleWidthPx(2000, 1f, 2f));
        assertEquals(960, MessageBubbleLayoutRules.maxBubbleWidthPx(4000, 2f, 2f));
    }

    @Test
    public void anUnmeasuredRowKeepsItsLayoutDefaultsInsteadOfSpanningTheScreen() {
        assertEquals(null, MessageBubbleLayoutRules.guidesOrNull(0, 1f, 2.625f));
        assertEquals(null, MessageBubbleLayoutRules.guidesOrNull(-48, 1f, 2.625f));
        float[] phone = MessageBubbleLayoutRules.guidesOrNull(1040, 1f, 2.625f);
        assertEquals(1f - 735f / 1040f, phone[0], 0.001f);
        assertEquals(735f / 1040f, phone[1], 0.001f);
    }

    @Test
    public void equalGuidesAreNotReapplied() {
        assertTrue(MessageBubbleLayoutRules.differs(0f, 0.29f));
        assertTrue(!MessageBubbleLayoutRules.differs(0.2933f, 0.2932f));
    }
}
