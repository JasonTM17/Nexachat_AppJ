package com.nexachat.core.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import org.junit.Test;

public final class BoundedInputStreamTest {

    @Test
    public void bodyUnderTheCapReadsThroughToEof() throws Exception {
        byte[] body = new byte[100];
        for (int i = 0; i < body.length; i++) {
            body[i] = (byte) i;
        }
        try (InputStream in = new BoundedInputStream(new ByteArrayInputStream(body), 100)) {
            byte[] out = in.readAllBytes();
            assertEquals(100, out.length);
        }
    }

    @Test
    public void bodyExactlyAtTheCapIsNotOversize() throws Exception {
        try (InputStream in = new BoundedInputStream(
                new ByteArrayInputStream(new byte[64]), 64)) {
            assertEquals(64, in.readAllBytes().length);
        }
    }

    @Test
    public void bodyOverTheCapAbortsWithIoException() throws Exception {
        try (InputStream in = new BoundedInputStream(
                new ByteArrayInputStream(new byte[65]), 64)) {
            in.readAllBytes();
            fail("expected oversize abort");
        } catch (IOException expected) {
            // counted stream must abort past the ceiling
        }
    }

    @Test
    public void skipPastTheCapAlsoAborts() throws Exception {
        try (InputStream in = new BoundedInputStream(
                new ByteArrayInputStream(new byte[100]), 10)) {
            in.skip(50);
            fail("expected oversize abort");
        } catch (IOException expected) {
            // skip() is counted too — a hostile stream cannot dodge the cap
        }
    }
}
