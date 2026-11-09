package com.nexachat.feature.invite;

import org.junit.Test;
import java.util.Optional;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class InviteLinkParserTest {
    private static final String TOKEN = "A".repeat(43);

    @Test
    public void acceptsOnlySupportedHttpsInviteShape() {
        Optional<InviteLinkParser.ParsedInvite> parsed = InviteLinkParser.parse(
                "https://nexachat.app/join/" + TOKEN);

        assertTrue(parsed.isPresent());
        assertEquals(TOKEN, parsed.get().getToken());
        assertEquals("https://nexachat.app/join/" + TOKEN,
                InviteLinkParser.shareUrl(TOKEN).orElseThrow());
    }

    @Test
    public void rejectsMalformedWrongHostAndUnexpectedUrlParts() {
        assertRejected(null);
        assertRejected("not a URL");
        assertRejected("http://nexachat.app/join/" + TOKEN);
        assertRejected("https://evil.example/join/" + TOKEN);
        assertRejected("https://nexachat.app.evil.example/join/" + TOKEN);
        assertRejected("https://user@nexachat.app/join/" + TOKEN);
        assertRejected("https://nexachat.app:444/join/" + TOKEN);
        assertRejected("https://nexachat.app/join/" + TOKEN + "?next=https://evil.example");
        assertRejected("https://nexachat.app/join/" + TOKEN + "#fragment");
        assertRejected("https://nexachat.app/join/" + TOKEN + "/extra");
        assertRejected("https://nexachat.app/other/" + TOKEN);
    }

    @Test
    public void rejectsHugeUnicodeAndEncodedTokenInputs() {
        assertRejected("https://nexachat.app/join/" + "A".repeat(4096));
        assertRejected("https://nexachat.app/join/é" + TOKEN);
        assertRejected("https://nexachat.app/join/%41" + "A".repeat(42));
        assertRejected("https://nexachat.app/join/" + "A".repeat(42) + "é");
        assertRejected("https://xn--nexachat-9za.app/join/" + TOKEN);
        assertFalse(InviteLinkParser.shareUrl("short-token").isPresent());
    }

    @Test
    public void parserDiagnosticStringDoesNotRevealToken() {
        InviteLinkParser.ParsedInvite parsed = InviteLinkParser.parse(
                "https://nexachat.app/join/" + TOKEN).orElseThrow();

        assertFalse(parsed.toString().contains(TOKEN));
    }

    private static void assertRejected(String value) {
        assertFalse(InviteLinkParser.parse(value).isPresent());
    }
}
