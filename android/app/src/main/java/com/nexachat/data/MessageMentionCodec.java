package com.nexachat.data;

import com.nexachat.core.network.dto.MessageMention;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import java.io.IOException;
import java.util.Collections;
import java.util.List;

/** Safe Room encoding for structured message mention ranges. */
public final class MessageMentionCodec {
    private static final JsonAdapter<List<MessageMention>> ADAPTER = new Moshi.Builder().build()
            .adapter(Types.newParameterizedType(List.class, MessageMention.class));

    private MessageMentionCodec() {}

    public static String encode(List<MessageMention> mentions) {
        try {
            return ADAPTER.toJson(mentions == null ? Collections.emptyList() : mentions);
        } catch (RuntimeException ignored) {
            return "[]";
        }
    }

    public static List<MessageMention> decode(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            List<MessageMention> decoded = ADAPTER.fromJson(json);
            return decoded == null ? Collections.emptyList() : List.copyOf(decoded);
        } catch (IOException | RuntimeException ignored) {
            return Collections.emptyList();
        }
    }
}
