package com.nexachat.core.network.dto;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import com.nexachat.core.media.AttachmentBlurHashRegistry;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import org.junit.Test;

public final class AttachmentDtoJsonAdapterTest {

    @Test
    public void readsBlurHashIntoMemoryButOmitsItFromRoomJson() throws Exception {
        JsonAdapter<AttachmentDto> adapter = new Moshi.Builder()
                .add(new AttachmentDtoJsonAdapterFactory())
                .build()
                .adapter(AttachmentDto.class);

        AttachmentDto serverDto = adapter.fromJson(
                "{\"id\":\"att-memory-only\",\"mimeType\":\"image/jpeg\","
                        + "\"blurHash\":\"000000\"}");

        assertEquals("000000", serverDto.getBlurHash());
        assertEquals("000000", AttachmentBlurHashRegistry.get("att-memory-only"));
        String roomJson = adapter.toJson(serverDto);
        assertFalse(roomJson.contains("blurHash"));
        assertNull(adapter.fromJson(roomJson).getBlurHash());
    }

    @Test
    public void absentOrInvalidBlurHashRemainsNullableAndIsNotRemembered() throws Exception {
        JsonAdapter<AttachmentDto> adapter = new Moshi.Builder()
                .add(new AttachmentDtoJsonAdapterFactory())
                .build()
                .adapter(AttachmentDto.class);

        AttachmentDto legacy = adapter.fromJson("{\"id\":\"att-legacy\"}");
        AttachmentDto invalid = adapter.fromJson(
                "{\"id\":\"att-invalid\",\"blurHash\":\"bad!\"}");

        assertNull(legacy.getBlurHash());
        assertNull(invalid.getBlurHash());
        assertNull(AttachmentBlurHashRegistry.get("att-invalid"));
    }
}
