package com.nexachat.archive;

import com.nexachat.auth.JwtProperties;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;

/**
 * Unit coverage for the signed opaque cursor: round-trip, tamper rejection,
 * cross-conversation/cross-tab rejection and strict payload validation.
 */
class ArchiveCursorCodecTest {

    private final ArchiveCursorCodec codec = new ArchiveCursorCodec(
            new JwtProperties("test-only-secret-0123456789abcdef-0123456789", 900, 14));
    private final UUID conversationId = UUID.randomUUID();

    @Test
    void blankCursorMeansFirstPage() {
        assertThat(codec.decode(null, conversationId, ArchiveKind.MEDIA)).isNull();
        assertThat(codec.decode("", conversationId, ArchiveKind.MEDIA)).isNull();
        assertThat(codec.decode("   ", conversationId, ArchiveKind.MEDIA)).isNull();
    }

    @Test
    void roundTripsAttachmentAndLinkPositions() {
        UUID attachmentId = UUID.randomUUID();
        String mediaCursor = codec.encode(conversationId, ArchiveKind.MEDIA, 41, attachmentId.toString());
        ArchiveCursor decoded = codec.decode(mediaCursor, conversationId, ArchiveKind.MEDIA);
        assertThat(decoded.conversationId()).isEqualTo(conversationId);
        assertThat(decoded.kind()).isEqualTo(ArchiveKind.MEDIA);
        assertThat(decoded.seq()).isEqualTo(41);
        assertThat(decoded.sub()).isEqualTo(attachmentId.toString());

        String linkCursor = codec.encode(conversationId, ArchiveKind.LINK, 77, "-1");
        ArchiveCursor link = codec.decode(linkCursor, conversationId, ArchiveKind.LINK);
        assertThat(link.seq()).isEqualTo(77);
        assertThat(link.sub()).isEqualTo("-1");
    }

    @Test
    void rejectsTamperedSignatureOrPayload() {
        String cursor = codec.encode(conversationId, ArchiveKind.FILE, 5, UUID.randomUUID().toString());
        String[] parts = cursor.split("\\.");

        assertInvalid(replaceFirstCharacter(parts[0]) + "." + parts[1]);
        assertInvalid(parts[0] + "." + replaceFirstCharacter(parts[1]));
    }

    @Test
    void rejectsCursorForAnotherConversationOrTab() {
        String cursor = codec.encode(conversationId, ArchiveKind.MEDIA, 5, UUID.randomUUID().toString());

        assertInvalidError(() -> codec.decode(cursor, UUID.randomUUID(), ArchiveKind.MEDIA));
        assertInvalidError(() -> codec.decode(cursor, conversationId, ArchiveKind.FILE));
        assertInvalidError(() -> codec.decode(cursor, conversationId, ArchiveKind.LINK));
    }

    @Test
    void rejectsMalformedCursors() {
        assertInvalid("abc");
        assertInvalid("a.b.c");
        assertInvalid("....");
        assertInvalid("!!!.???");
        assertInvalid("A".repeat(2000));
    }

    @Test
    void rejectsSubThatDoesNotBelongToTheKind() {
        String badAttachment = codec.encode(conversationId, ArchiveKind.MEDIA, 1, "not-a-uuid");
        assertInvalidError(() -> codec.decode(badAttachment, conversationId, ArchiveKind.MEDIA));

        String tooSmallOffset = codec.encode(conversationId, ArchiveKind.LINK, 1, "-2");
        assertInvalidError(() -> codec.decode(tooSmallOffset, conversationId, ArchiveKind.LINK));

        String nonNumericOffset = codec.encode(conversationId, ArchiveKind.LINK, 1, "not-a-number");
        assertInvalidError(() -> codec.decode(nonNumericOffset, conversationId, ArchiveKind.LINK));
    }

    @Test
    void requiresConfiguredSecret() {
        assertThat(catchThrowableOfType(
                () -> new ArchiveCursorCodec(new JwtProperties(null, 900, 14)),
                IllegalStateException.class)).isNotNull();
        assertThat(catchThrowableOfType(
                () -> new ArchiveCursorCodec(new JwtProperties("  ", 900, 14)),
                IllegalStateException.class)).isNotNull();
    }

    private void assertInvalid(String cursor) {
        assertInvalidError(() -> codec.decode(cursor, conversationId, ArchiveKind.MEDIA));
    }

    private static void assertInvalidError(ThrowingCallable action) {
        ApiException exception = catchThrowableOfType(action, ApiException.class);
        assertThat(exception).isNotNull();
        assertThat(exception.getCode()).isEqualTo(ApiErrorCode.VALIDATION_FAILED);
    }

    private static String replaceFirstCharacter(String value) {
        char first = value.charAt(0);
        return (first == 'A' ? 'B' : 'A') + value.substring(1);
    }
}
