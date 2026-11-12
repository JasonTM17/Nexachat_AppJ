package com.nexachat.feature.summary;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.nexachat.summary.SummaryActionItem;
import com.nexachat.summary.SummaryParticipant;
import java.util.List;
import org.junit.Test;

public final class SummaryOwnerLabelTest {

    @Test
    public void showsMappedDisplayName() {
        var participants = List.of(new SummaryParticipant("u-1", "Mai Anh", 3));
        assertEquals("Mai Anh", SummaryOwnerLabel.displayName("u-1", participants));
    }

    @Test
    public void omitsUnknownOrBlankOwners() {
        var participants = List.of(new SummaryParticipant("u-1", "Mai Anh", 3));
        assertEquals("", SummaryOwnerLabel.displayName("deleted-user", participants));
        assertEquals("", SummaryOwnerLabel.displayName("u-1", List.of(
                new SummaryParticipant("u-1", "   ", 1))));
        assertEquals("", SummaryOwnerLabel.displayName(null, participants));
    }

    @Test
    public void actionMetaNeverIncludesRawIds() {
        var action = new SummaryActionItem("Follow up", "abcdef12-3456", "tomorrow", List.of());
        var meta = SummaryOwnerLabel.actionMeta(action, List.of());
        assertEquals("tomorrow", meta);
        assertFalse(meta.contains("@"));
        assertFalse(meta.contains("abcdef"));
    }

    @Test
    public void actionMetaJoinsOwnerAndDueHint() {
        var participants = List.of(new SummaryParticipant("u-2", "Tien Son", 1));
        var action = new SummaryActionItem("Ship", "u-2", "Friday", List.of());
        assertEquals("Tien Son · Friday", SummaryOwnerLabel.actionMeta(action, participants));
    }
}
