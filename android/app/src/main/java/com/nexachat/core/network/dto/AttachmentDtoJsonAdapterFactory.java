package com.nexachat.core.network.dto;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.nexachat.core.media.AttachmentBlurHashRegistry;
import com.nexachat.core.media.BlurHashDecoder;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonReader;
import com.squareup.moshi.JsonWriter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.Set;

/** Reads BlurHash from server DTOs but keeps the value out of Room's JSON attachment column. */
public final class AttachmentDtoJsonAdapterFactory implements JsonAdapter.Factory {

    @Nullable
    @Override
    public JsonAdapter<?> create(
            @NonNull Type type,
            @NonNull Set<? extends Annotation> annotations,
            @NonNull Moshi moshi) {
        if (Types.getRawType(type) != AttachmentDto.class || !annotations.isEmpty()) {
            return null;
        }
        JsonAdapter<AttachmentDto> delegate = moshi.nextAdapter(this, type, annotations);
        return new JsonAdapter<AttachmentDto>() {
            @Nullable
            @Override
            public AttachmentDto fromJson(@NonNull JsonReader reader) throws IOException {
                AttachmentDto attachment = delegate.fromJson(reader);
                if (attachment != null) {
                    String hash = attachment.getBlurHash();
                    if (hash != null
                            && (hash.length() < 6
                                    || hash.length() > 64
                                    || !BlurHashDecoder.isValid(hash))) {
                        attachment = attachment.withBlurHash(null);
                    }
                }
                AttachmentBlurHashRegistry.remember(attachment);
                return attachment;
            }

            @Override
            public void toJson(@NonNull JsonWriter writer, @Nullable AttachmentDto value)
                    throws IOException {
                delegate.toJson(writer, value == null ? null : value.withoutBlurHash());
            }
        }.nullSafe();
    }
}
