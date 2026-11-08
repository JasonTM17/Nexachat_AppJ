package com.nexachat.core.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.nexachat.core.network.dto.AttachmentDto;
import org.junit.Test;

public final class AttachmentBlurHashRegistryTest {

    private static final String HASH = "LEHV6nWB2yk8pyo0adR*.7kCMdnj";

    @Test
    public void remembersBlurHashFromAttachmentDtoAndDirectValues() {
        String dtoId = "registry-dto-" + System.nanoTime();
        String directId = "registry-direct-" + System.nanoTime();
        AttachmentDto attachment = new AttachmentDto(
                dtoId, null, "photo.jpg", "image/jpeg", null, null, null, null, null, HASH);

        AttachmentBlurHashRegistry.remember(attachment);
        AttachmentBlurHashRegistry.remember(directId, "000000");

        assertEquals(HASH, AttachmentBlurHashRegistry.get(dtoId));
        assertEquals("000000", AttachmentBlurHashRegistry.get(directId));
    }

    @Test
    public void ignoresMissingIdsAndMalformedOrOutOfRangeHashes() {
        String tooLongId = "registry-long-" + System.nanoTime();
        String malformedId = "registry-invalid-" + System.nanoTime();
        String shortId = "registry-short-" + System.nanoTime();

        AttachmentBlurHashRegistry.remember((AttachmentDto) null);
        AttachmentBlurHashRegistry.remember((String) null, HASH);
        AttachmentBlurHashRegistry.remember(" ", HASH);
        AttachmentBlurHashRegistry.remember(malformedId, "!!!!!!");
        AttachmentBlurHashRegistry.remember(shortId, "abc");
        AttachmentBlurHashRegistry.remember(tooLongId, "A".repeat(65));

        assertNull(AttachmentBlurHashRegistry.get(malformedId));
        assertNull(AttachmentBlurHashRegistry.get(shortId));
        assertNull(AttachmentBlurHashRegistry.get(tooLongId));
        assertNull(AttachmentBlurHashRegistry.get(" "));
    }

    @Test
    public void registryKeysRemainCaseSensitive() {
        String id = "registry-case-" + System.nanoTime();
        AttachmentBlurHashRegistry.remember(id, HASH);

        assertEquals(HASH, AttachmentBlurHashRegistry.get(id));
        assertNull(AttachmentBlurHashRegistry.get(id.toUpperCase(java.util.Locale.ROOT)));
    }

    @Test
    public void registryEvictsOldEntriesAtItsConfiguredCapacity() {
        String prefix = "registry-evict-" + System.nanoTime() + "-";
        for (int i = 0; i <= AttachmentBlurHashRegistry.MAX_ENTRIES; i++) {
            AttachmentBlurHashRegistry.remember(prefix + i, HASH);
        }

        assertNull(AttachmentBlurHashRegistry.get(prefix + "0"));
        assertEquals(HASH, AttachmentBlurHashRegistry.get(
                prefix + AttachmentBlurHashRegistry.MAX_ENTRIES));
        assertTrue(AttachmentBlurHashRegistry.size() <= AttachmentBlurHashRegistry.MAX_ENTRIES);
    }
}
