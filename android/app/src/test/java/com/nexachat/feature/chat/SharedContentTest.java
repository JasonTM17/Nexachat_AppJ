package com.nexachat.feature.chat;



import static org.junit.Assert.assertEquals;

import static org.junit.Assert.assertNull;

import static org.junit.Assert.assertTrue;



import org.junit.Test;



/**

 * Round trips and hostile-input handling for the shared-content wire formats.

 */

public final class SharedContentTest {



    @Test

    public void locationRoundTripsWithAndWithoutLabel() {

        String withLabel = SharedContent.encodeLocation(21.0278, 105.8342, "Home");

        assertEquals("nexachat:loc:v1 21.0278,105.8342 Home", withLabel);

        SharedContent.Shared parsed = SharedContent.parse(withLabel);

        assertTrue(parsed instanceof SharedContent.Location);

        SharedContent.Location location = (SharedContent.Location) parsed;

        assertEquals(21.0278, location.getLatitude(), 0.000001);

        assertEquals(105.8342, location.getLongitude(), 0.000001);

        assertEquals("Home", location.getLabel());



        String withoutLabel = SharedContent.encodeLocation(-33.8568, 151.2153, null);

        assertEquals("nexachat:loc:v1 -33.8568,151.2153", withoutLabel);

        SharedContent.Shared reparsed = SharedContent.parse(withoutLabel);

        assertTrue(reparsed instanceof SharedContent.Location);

        assertNull(((SharedContent.Location) reparsed).getLabel());

    }



    @Test

    public void locationPreservesLabelsWithSpacesAndUnicode() {

        String wire = SharedContent.encodeLocation(10.7769, 106.7009, "Quán cà phê Sài Gòn");

        SharedContent.Shared parsed = SharedContent.parse(wire);

        assertTrue(parsed instanceof SharedContent.Location);

        assertEquals("Quán cà phê Sài Gòn", ((SharedContent.Location) parsed).getLabel());

    }



    @Test

    public void locationRejectsMalformedCoordinates() {

        assertNull(SharedContent.parse("nexachat:loc:v1 91,0"));

        assertNull(SharedContent.parse("nexachat:loc:v1 -91,0"));

        assertNull(SharedContent.parse("nexachat:loc:v1 0,181"));

        assertNull(SharedContent.parse("nexachat:loc:v1 0,-181"));

        assertNull(SharedContent.parse("nexachat:loc:v1 NaN,0"));

        assertNull(SharedContent.parse("nexachat:loc:v1 0,Infinity"));

        assertNull(SharedContent.parse("nexachat:loc:v1 10,20,30"));

        assertNull(SharedContent.parse("nexachat:loc:v1 ,20"));

        assertNull(SharedContent.parse("nexachat:loc:v1 10,"));

        assertNull(SharedContent.parse("nexachat:loc:v1"));

        assertNull(SharedContent.parse("nexachat:loc:v1 "));

        assertNull(SharedContent.parse("nexachat:loc:v1 abc,def"));

    }



    @Test

    public void locationRejectsInjectedNewlines() {

        assertNull(SharedContent.parse("nexachat:loc:v1 10,20\nspoofed line"));

        assertNull(SharedContent.parse("nexachat:loc:v1 10,20 label\nnexachat:loc:v1 1,1"));

        assertNull(SharedContent.parse("nexachat:loc:v1 10,20\r11"));

    }



    @Test

    public void locationTrimsOverlongLabels() {

        String longLabel = "a".repeat(120);

        SharedContent.Shared parsed = SharedContent.parse(

                "nexachat:loc:v1 10,20 " + longLabel);

        assertTrue(parsed instanceof SharedContent.Location);

        assertEquals(SharedContent.MAX_TEXT_CHARS,

                ((SharedContent.Location) parsed).getLabel().length());

    }



    @Test

    public void locationLabelStripsControlsAndBidiOverrides() {

        SharedContent.Shared parsed = SharedContent.parse(

                "nexachat:loc:v1 10,20 \uFEFFevil\u2066label\u2069");

        assertTrue(parsed instanceof SharedContent.Location);

        assertEquals("evillabel", ((SharedContent.Location) parsed).getLabel());

    }



    @Test

    public void boundaryCoordinatesParse() {

        assertTrue(SharedContent.parse("nexachat:loc:v1 90,180")

                instanceof SharedContent.Location);

        assertTrue(SharedContent.parse("nexachat:loc:v1 -90,-180")

                instanceof SharedContent.Location);

        assertTrue(SharedContent.parse("nexachat:loc:v1 0,0")

                instanceof SharedContent.Location);

    }



    @Test

    public void coordinateFormattingCapsSixDecimals() {

        assertEquals("10.123457", SharedContent.formatCoordinate(10.1234567));

        assertEquals("0", SharedContent.formatCoordinate(0.0));

        assertEquals("0", SharedContent.formatCoordinate(-0.0));

        assertEquals("-33.8568", SharedContent.formatCoordinate(-33.8568));

        assertEquals("90", SharedContent.formatCoordinate(90.0));

    }



    @Test

    public void contactRoundTrips() {

        String wire = SharedContent.encodeContact("Nguyễn Văn An", "+84901234567");

        assertEquals("nexachat:contact:v1\nNguyễn Văn An\n+84901234567", wire);

        SharedContent.Shared parsed = SharedContent.parse(wire);

        assertTrue(parsed instanceof SharedContent.Contact);

        SharedContent.Contact contact = (SharedContent.Contact) parsed;

        assertEquals("Nguyễn Văn An", contact.getDisplayName());

        assertEquals("+84901234567", contact.getPhone());

    }



    @Test

    public void contactRejectsMalformedShapes() {

        assertNull(SharedContent.parse("nexachat:contact:v1"));

        assertNull(SharedContent.parse("nexachat:contact:v1\nAn"));

        assertNull(SharedContent.parse("nexachat:contact:v1\nAn\n+8490\nextra"));

        assertNull(SharedContent.parse("nexachat:contact:v1\n\n+84901234567"));

        assertNull(SharedContent.parse("nexachat:contact:v1\nAn\n84901234567"));

        assertNull(SharedContent.parse("nexachat:contact:v1\nAn\n+84 90 123"));

        assertNull(SharedContent.parse("nexachat:contact:v1\nAn\n+"));

        assertNull(SharedContent.parse("nexachat:contact:v1\nAn\n+8412345678901234"));

        assertNull(SharedContent.parse("nexachat:contact:v1 \nAn\n+84901234567"));

    }



    @Test

    public void contactTrimsOverlongNamesAndSanitizes() {

        String longName = "B".repeat(100);

        SharedContent.Shared parsed = SharedContent.parse(

                "nexachat:contact:v1\n" + longName + "\n+84901234567");

        assertTrue(parsed instanceof SharedContent.Contact);

        assertEquals(SharedContent.MAX_TEXT_CHARS,

                ((SharedContent.Contact) parsed).getDisplayName().length());



        SharedContent.Shared bidi = SharedContent.parse(

                "nexachat:contact:v1\n\u202AAn\u202C\n+84901234567");

        assertTrue(bidi instanceof SharedContent.Contact);

        assertEquals("An", ((SharedContent.Contact) bidi).getDisplayName());

    }



    @Test

    public void plainTextNeverParses() {

        assertNull(SharedContent.parse(null));

        assertNull(SharedContent.parse(""));

        assertNull(SharedContent.parse("nexachat:loc:v2 10,20"));

        assertNull(SharedContent.parse("hello nexachat:loc:v1 10,20"));

        assertNull(SharedContent.parse("nexachat:contact:v2\nAn\n+8490"));

    }



    @Test

    public void previewTextMapsSharedKinds() {

        assertEquals("Location", SharedContent.previewText(

                new SharedContent.Location(10.0, 20.0, null)));

        assertEquals("Home", SharedContent.previewText(

                new SharedContent.Location(10.0, 20.0, "Home")));

        assertEquals("Contact: An", SharedContent.previewText(

                new SharedContent.Contact("An", "+84901234567")));

    }



    @Test

    public void copyTextFallsBackToRawForPlainText() {

        assertEquals("10, 20", SharedContent.copyText("nexachat:loc:v1 10,20"));

        assertEquals("Home (10, 20)", SharedContent.copyText("nexachat:loc:v1 10,20 Home"));

        assertEquals("An, +84901234567",

                SharedContent.copyText("nexachat:contact:v1\nAn\n+84901234567"));

        assertEquals("plain text", SharedContent.copyText("plain text"));

        assertEquals("", SharedContent.copyText((String) null));

    }



    @Test

    public void isE164ValidatesShape() {

        assertTrue(SharedContent.isE164("+84901234567"));

        assertTrue(SharedContent.isE164("+1"));

        assertTrue(SharedContent.isE164("+841234567890123"));

        assertTrue(!SharedContent.isE164("+8412345678901234"));

        assertTrue(!SharedContent.isE164("8490"));

        assertTrue(!SharedContent.isE164("+84 90"));

        assertTrue(!SharedContent.isE164("+"));

        assertTrue(!SharedContent.isE164(""));

        assertTrue(!SharedContent.isE164(null));

    }

}
