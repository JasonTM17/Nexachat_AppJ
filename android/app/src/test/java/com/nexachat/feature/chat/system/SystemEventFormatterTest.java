package com.nexachat.feature.chat.system;

import android.content.Context;
import com.nexachat.R;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

public class SystemEventFormatterTest {

    @Mock private Context context;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    public void localizedGroupEventUsesStringResource() {
        when(context.getString(eq(R.string.system_event_group_member_added), any(), any()))
                .thenReturn("An added Binh");
        CharSequence text = SystemEventFormatter.format(context, "An added Binh", false);
        assertEquals("An added Binh", text.toString());
    }

    @Test
    public void unknownEventKeepsServerText() {
        CharSequence text = SystemEventFormatter.format(context, "Pinned by An", false);
        assertEquals("Pinned by An", text.toString());
    }

    @Test
    public void e2eeToggleEventsUseTheirOwnResources() {
        when(context.getString(eq(R.string.system_event_e2ee_enabled), any()))
                .thenReturn("An turned on end-to-end encryption");
        when(context.getString(eq(R.string.system_event_e2ee_disabled), any()))
                .thenReturn("An turned off end-to-end encryption");
        assertEquals("An turned on end-to-end encryption",
                SystemEventFormatter.format(context, "An turned on end-to-end encryption", false)
                        .toString());
        assertEquals("An turned off end-to-end encryption",
                SystemEventFormatter.format(context, "An turned off end-to-end encryption", false)
                        .toString());
    }

    @Test
    public void adminPromotionUsesItsOwnResource() {
        when(context.getString(eq(R.string.system_event_group_admin_promoted), any(), any()))
                .thenReturn("An made Binh a group admin");
        assertEquals("An made Binh a group admin",
                SystemEventFormatter.format(context, "An made Binh a group admin", false)
                        .toString());
    }

    @Test
    public void callStructuredEventUsesCallResources() {
        when(context.getString(eq(R.string.call_log_outgoing_completed), any()))
                .thenReturn("Outgoing call · 0:42");
        CharSequence text = SystemEventFormatter.format(context, "CALL|COMPLETED|42|id", true);
        assertEquals("Outgoing call · 0:42", text.toString());
    }
}
