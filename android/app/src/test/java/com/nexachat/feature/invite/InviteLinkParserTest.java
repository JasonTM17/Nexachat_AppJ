package com.nexachat.feature.invite;

import org.junit.Test;
import java.util.Optional;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class InviteLinkParserTest {
    private static final String TOKEN = "A".repeat(43);
    private static final String PREVIOUS_UNOWNED_HOST = "nexachat" + ".app";

    @Test
    public void defaultLinksUseTheCustomSchemeAndRejectHttps() {
        Optional<InviteLinkParser.ParsedInvite> parsed = InviteLinkParser.parse(
                "nexachat://join/" + TOKEN);

        assertTrue(parsed.isPresent());
        assertEquals(TOKEN, parsed.get().getToken());
        assertEquals("nexachat://join/" + TOKEN,
                InviteLinkParser.shareUrl(TOKEN).orElseThrow());
        assertRejected("https://" + PREVIOUS_UNOWNED_HOST + "/join/" + TOKEN);
        assertRejected("https://chat.test/join/" + TOKEN);
    }

    @Test
    public void releaseOwnedDomainProducesHttpsShareLinksAndRejectsLookAlikes() {
        String host = "nexachat.io.vn";
        Optional<InviteLinkParser.ParsedInvite> parsed = InviteLinkParser.parse(
                "https://" + host + "/join/" + TOKEN, host);

        assertTrue(parsed.isPresent());
        assertEquals(TOKEN, parsed.get().getToken());
        assertEquals("https://" + host + "/join/" + TOKEN,
                InviteLinkParser.shareUrl(TOKEN, host).orElseThrow());
        assertTrue(InviteLinkParser.parse(
                "HTTPS://NEXACHAT.IO.VN/join/" + TOKEN, host).isPresent());

        // Custom scheme must still be supported when HTTPS host is configured
        Optional<InviteLinkParser.ParsedInvite> customParsed = InviteLinkParser.parse(
                "nexachat://join/" + TOKEN, host);
        assertTrue(customParsed.isPresent());
        assertEquals(TOKEN, customParsed.get().getToken());

        // Look-alikes and malformed variants must be rejected
        assertFalse(InviteLinkParser.parse(
                "https://" + host + ".evil.example/join/" + TOKEN, host).isPresent());
        assertFalse(InviteLinkParser.parse(
                "https://" + host + ".evil.test/join/" + TOKEN, host).isPresent());
        assertFalse(InviteLinkParser.parse(
                "https://evil." + host + "/join/" + TOKEN, host).isPresent());
        assertFalse(InviteLinkParser.parse(
                "https://" + host + ".attacker.com/join/" + TOKEN, host).isPresent());
        assertFalse(InviteLinkParser.parse(
                "https://other.domain.com/join/" + TOKEN, host).isPresent());
        assertFalse(InviteLinkParser.parse(
                "http://" + host + "/join/" + TOKEN, host).isPresent());
        assertFalse(InviteLinkParser.parse(
                "https://" + host + ":443/join/" + TOKEN, host).isPresent());
        assertFalse(InviteLinkParser.parse(
                "https://" + host + ":444/join/" + TOKEN, host).isPresent());
        assertFalse(InviteLinkParser.parse(
                "https://user@" + host + "/join/" + TOKEN, host).isPresent());
        assertFalse(InviteLinkParser.parse(
                "https://user:pass@" + host + "/join/" + TOKEN, host).isPresent());
        assertFalse(InviteLinkParser.parse(
                "https://" + host + "/join/" + TOKEN + "?next=https://evil.example", host)
                .isPresent());
        assertFalse(InviteLinkParser.parse(
                "https://" + host + "/join/" + TOKEN + "#fragment", host).isPresent());
        assertFalse(InviteLinkParser.parse(
                "https://" + host + "/join/%41" + "A".repeat(42), host).isPresent());
        assertFalse(InviteLinkParser.parse(
                "https://" + host + "/join/" + TOKEN + "/extra", host).isPresent());
        assertFalse(InviteLinkParser.parse(
                "https://" + host + "/join/" + TOKEN + "/extra/segment", host).isPresent());
        assertFalse(InviteLinkParser.parse(
                "https://" + host + "/JOIN/" + TOKEN, host).isPresent());
        assertFalse(InviteLinkParser.parse(
                "https://" + host + "/invite/" + TOKEN, host).isPresent());
    }

    @Test
    public void debugEmptyHostConfigurationUsesCustomSchemeAndRejectsHttps() {
        String emptyHost = "";
        Optional<InviteLinkParser.ParsedInvite> customParsed = InviteLinkParser.parse(
                "nexachat://join/" + TOKEN, emptyHost);
        assertTrue(customParsed.isPresent());
        assertEquals(TOKEN, customParsed.get().getToken());
        assertEquals("nexachat://join/" + TOKEN,
                InviteLinkParser.shareUrl(TOKEN, emptyHost).orElseThrow());

        // Any https link is rejected when no host is configured
        assertFalse(InviteLinkParser.parse(
                "https://nexachat.io.vn/join/" + TOKEN, emptyHost).isPresent());
        assertFalse(InviteLinkParser.parse(
                "https://example.com/join/" + TOKEN, emptyHost).isPresent());
    }

    @Test
    public void configuredHttpsHostIsExactAndProducesHttpsShareLinks() {
        String host = "invite.example.test";
        Optional<InviteLinkParser.ParsedInvite> parsed = InviteLinkParser.parse(
                "https://" + host + "/join/" + TOKEN, host);

        assertTrue(parsed.isPresent());
        assertEquals(TOKEN, parsed.get().getToken());
        assertEquals("https://" + host + "/join/" + TOKEN,
                InviteLinkParser.shareUrl(TOKEN, host).orElseThrow());
        assertTrue(InviteLinkParser.parse(
                "HTTPS://INVITE.EXAMPLE.TEST/join/" + TOKEN, host).isPresent());
        assertFalse(InviteLinkParser.parse(
                "https://" + host + ".evil.test/join/" + TOKEN, host).isPresent());
        assertFalse(InviteLinkParser.parse(
                "https://evil." + host + "/join/" + TOKEN, host).isPresent());
        assertFalse(InviteLinkParser.parse(
                "https://other.example.test/join/" + TOKEN, host).isPresent());
        assertFalse(InviteLinkParser.parse(
                "https://" + host + ":443/join/" + TOKEN, host).isPresent());
        assertFalse(InviteLinkParser.parse(
                "https://" + host + ":444/join/" + TOKEN, host).isPresent());
        assertFalse(InviteLinkParser.parse(
                "https://user@" + host + "/join/" + TOKEN, host).isPresent());
        assertFalse(InviteLinkParser.parse(
                "https://" + host + "/join/" + TOKEN + "?next=https://evil.test", host)
                .isPresent());
        assertFalse(InviteLinkParser.parse(
                "https://" + host + "/join/" + TOKEN + "#fragment", host).isPresent());
        assertFalse(InviteLinkParser.parse(
                "https://" + host + "/join/%41" + "A".repeat(42), host).isPresent());
        assertFalse(InviteLinkParser.parse(
                "https://" + host + "/join/" + TOKEN + "/extra", host).isPresent());
        assertFalse(InviteLinkParser.parse(
                "https://" + host + "/JOIN/" + TOKEN, host).isPresent());
    }

    @Test
    public void rejectsMalformedWrongHostAndUnexpectedUrlParts() {
        assertRejected(null);
        assertRejected("not a URL");
        assertRejected("http://" + PREVIOUS_UNOWNED_HOST + "/join/" + TOKEN);
        assertRejected("https://evil.example/join/" + TOKEN);
        assertRejected("https://" + PREVIOUS_UNOWNED_HOST + ".evil.example/join/" + TOKEN);
        assertRejected("https://user@" + PREVIOUS_UNOWNED_HOST + "/join/" + TOKEN);
        assertRejected("https://" + PREVIOUS_UNOWNED_HOST + ":444/join/" + TOKEN);
        assertRejected("https://" + PREVIOUS_UNOWNED_HOST + ":443/join/" + TOKEN);
        assertRejected("https://" + PREVIOUS_UNOWNED_HOST + "/join/" + TOKEN
                + "?next=https://evil.example");
        assertRejected("https://" + PREVIOUS_UNOWNED_HOST + "/join/" + TOKEN + "#fragment");
        assertRejected("https://" + PREVIOUS_UNOWNED_HOST + "/join/" + TOKEN + "/extra");
        assertRejected("https://" + PREVIOUS_UNOWNED_HOST + "/other/" + TOKEN);
        assertRejected("nexachat://other/" + TOKEN);
        assertRejected("nexachat://user@join/" + TOKEN);
        assertRejected("nexachat://join:443/" + TOKEN);
        assertRejected("nexachat://join/" + TOKEN + "?x=1");
        assertRejected("nexachat://join/" + TOKEN + "#fragment");
        assertRejected("nexachat://join/" + TOKEN + "/extra");
    }

    @Test
    public void rejectsHugeUnicodeAndEncodedTokenInputs() {
        assertRejected("nexachat://join/" + "A".repeat(4096));
        assertRejected("nexachat://join/é" + TOKEN);
        assertRejected("nexachat://join/%41" + "A".repeat(42));
        assertRejected("nexachat://join/" + "A".repeat(42) + "é");
        assertRejected("https://chat.test/join/" + TOKEN);
        assertFalse(InviteLinkParser.shareUrl("short-token").isPresent());
    }

    @Test
    public void customSchemeAndHostKeepTheirCaseInsensitiveUrlRules() {
        assertTrue(InviteLinkParser.parse("NEXACHAT://JOIN/" + TOKEN).isPresent());
        assertRejected("nexachat://join/" + TOKEN + "/extra");
    }

    @Test
    public void parserDiagnosticStringDoesNotRevealToken() {
        InviteLinkParser.ParsedInvite parsed = InviteLinkParser.parse(
                "nexachat://join/" + TOKEN).orElseThrow();

        assertFalse(parsed.toString().contains(TOKEN));
    }

    private static void assertRejected(String value) {
        assertFalse(InviteLinkParser.parse(value).isPresent());
    }
}
