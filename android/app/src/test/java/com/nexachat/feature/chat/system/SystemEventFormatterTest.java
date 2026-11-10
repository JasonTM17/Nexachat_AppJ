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
    public void callStructuredEventUsesCallResources() {
        when(context.getString(eq(R.string.call_log_outgoing_completed), any()))
                .thenReturn("Outgoing call · 0:42");
        CharSequence text = SystemEventFormatter.format(context, "CALL|COMPLETED|42|id", true);
        assertEquals("Outgoing call · 0:42", text.toString());
    }
}
