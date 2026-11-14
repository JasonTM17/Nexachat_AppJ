package com.nexachat.core.crypto;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.nexachat.core.network.dto.MessageDto;
import com.nexachat.core.network.dto.ReplyRefDto;
import java.util.Optional;

/**
 * Maps the wire E2EE fields of a {@link MessageDto} onto the local content and
 * flags persisted in Room. Shared by every ingest point (REST history, STOMP
 * events, delta sync) so a corrupt or unreadable row degrades identically:
 * {@code decryptFailed=1} with a null content — the UI renders the localized
 * placeholder and never the raw ciphertext.
 */
public final class E2eeMessageMapping {

    private E2eeMessageMapping() {
    }

    /** Resolved body + flags for one incoming message row. */
    public record Resolved(@Nullable String content, boolean encrypted,
            boolean decryptFailed) {
    }

    /**
     * Resolves the displayable content for {@code dto}. Plaintext rows pass
     * through untouched; {@code E2EE_V1} rows are decrypted for this device and
     * failures degrade to {@code decryptFailed} rather than leaking ciphertext
     * into views, previews, or logs.
     */
    @NonNull
    public static Resolved resolve(@NonNull MessageDto dto, @NonNull String conversationId,
            @Nullable String fallbackSenderId, @Nullable E2eeSession session) {
        boolean encrypted = "E2EE_V1".equals(dto.getEncryption())
                || E2eeCrypto.isEncryptedWireContent(dto.getContent());
        if (!encrypted) {
            return new Resolved(dto.getContent(), false, false);
        }
        String senderId = dto.getResolvedSenderId() != null
                ? dto.getResolvedSenderId() : fallbackSenderId;
        String clientMessageId = dto.getClientMessageId() != null
                ? dto.getClientMessageId() : "";
        if (session == null || senderId == null) {
            return new Resolved(null, true, true);
        }
        Optional<String> plain = session.decryptNow(conversationId, senderId,
                clientMessageId, dto.getContent(), dto.getE2eeEnvelopes());
        return plain.<Resolved>map(text -> new Resolved(text, true, false))
                .orElseGet(() -> new Resolved(null, true, true));
    }

    /** True when the quoted message was encrypted; the UI shows a neutral tag. */
    public static boolean replyTargetEncrypted(@Nullable ReplyRefDto replyTo) {
        return replyTo != null && Boolean.TRUE.equals(replyTo.getEncrypted());
    }
}
