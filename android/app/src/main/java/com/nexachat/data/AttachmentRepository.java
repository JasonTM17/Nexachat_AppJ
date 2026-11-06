package com.nexachat.data;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.nexachat.core.common.Result;
import com.nexachat.core.network.AttachmentApi;
import com.nexachat.core.network.dto.AttachmentDto;
import dagger.hilt.android.qualifiers.ApplicationContext;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import javax.inject.Inject;
import javax.inject.Singleton;
import kotlin.coroutines.Continuation;
import kotlinx.coroutines.flow.Flow;

/**
 * Attachment transport boundary: multipart upload with live progress and
 * authenticated downloads into {@code cacheDir/attachments}.
 *
 * <p>The public coroutine API is declared by {@link AttachmentRepositoryApi};
 * {@link AttachmentRepositoryFlow} keeps suspend and Flow work structured in
 * Kotlin while this class owns the Android and Java transport operations.
 */
@Singleton
public final class AttachmentRepository implements AttachmentRepositoryApi {

    public static final long MAX_UPLOAD_BYTES = 25L * 1024L * 1024L;
    public static final String ERROR_TOO_LARGE = "ATTACHMENT_TOO_LARGE";
    /** The picked content URI can no longer be read (grant revoked/gone). */
    public static final String ERROR_UNREADABLE = "ATTACHMENT_UNREADABLE";
    public static final String PART_FILE = "file";
    public static final String CACHE_DIR_NAME = "attachments";

    private static final String DEFAULT_MIME = "application/octet-stream";
    private static final int READ_BUFFER = 64 * 1024;

    private final AttachmentApi attachmentApi;
    private final Context context;

    @Inject
    public AttachmentRepository(
            @NonNull AttachmentApi attachmentApi,
            @ApplicationContext @NonNull Context context) {
        this.attachmentApi = attachmentApi;
        this.context = context;
    }

    @Override
    public Object describe(
            @NonNull Uri uri,
            @NonNull Continuation<? super LocalAttachment> completion) {
        return AttachmentRepositoryFlow.describe(this, uri, completion);
    }

    @Override
    @NonNull
    public Flow<AttachmentUploadEvent> upload(
            @NonNull Uri uri,
            @Nullable String mimeHint) {
        return AttachmentRepositoryFlow.upload(this, uri, mimeHint);
    }

    @Override
    public Object downloadToCache(
            @NonNull String attachmentId,
            @Nullable String displayName,
            @NonNull Continuation<? super Result<File>> completion) {
        return AttachmentRepositoryFlow.downloadToCache(
                this, attachmentId, displayName, completion);
    }

    /** Java-side resolver work; called by the bridge on {@code Dispatchers.IO}. */
    @NonNull
    LocalAttachment describeOnIo(@NonNull Uri uri) {
        String name = null;
        long size = -1L;

        try (Cursor cursor = context.getContentResolver()
                .query(uri, null, null, null, null)) {
            if (cursor != null) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (cursor.moveToFirst()) {
                    if (nameIndex >= 0) {
                        name = cursor.getString(nameIndex);
                    }
                    if (sizeIndex >= 0) {
                        size = cursor.getLong(sizeIndex);
                    }
                }
            }
        } catch (Throwable ignored) {
            // Picker providers vary in which metadata calls they support.
        }

        if (size < 0L) {
            try (ParcelFileDescriptor descriptor =
                    context.getContentResolver().openFileDescriptor(uri, "r")) {
                if (descriptor != null) {
                    size = descriptor.getStatSize();
                }
            } catch (Throwable ignored) {
                // Best-effort fallback; the bounded stream read is authoritative.
            }
        }

        String mime = null;
        try {
            mime = context.getContentResolver().getType(uri);
        } catch (Throwable ignored) {
            // Some document providers cannot report a MIME type.
        }

        return new LocalAttachment(
                uri,
                name != null ? name : uri.getLastPathSegment(),
                mime,
                size,
                AttachmentDto.messageTypeForMime(mime));
    }

    /**
     * Reads the picker stream with a hard ceiling, counting bytes as they
     * arrive instead of trusting provider-reported size.
     */
    @NonNull
    byte[] readBounded(@NonNull Uri uri, long declaredSize) throws IOException {
        if (declaredSize > MAX_UPLOAD_BYTES) {
            throw new OversizeException();
        }

        InputStream input = context.getContentResolver().openInputStream(uri);
        if (input == null) {
            throw new IOException("cannot open " + uri);
        }

        try (InputStream stream = input) {
            int initialCapacity = declaredSize >= 1L && declaredSize <= MAX_UPLOAD_BYTES
                    ? (int) declaredSize
                    : READ_BUFFER;
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(initialCapacity);
            byte[] chunk = new byte[READ_BUFFER];
            while (true) {
                int read = stream.read(chunk);
                if (read < 0) {
                    break;
                }
                if ((long) buffer.size() + read > MAX_UPLOAD_BYTES) {
                    throw new OversizeException();
                }
                buffer.write(chunk, 0, read);
            }
            return buffer.toByteArray();
        }
    }

    @NonNull
    String resolveMime(@Nullable String resolverType, @Nullable String hint) {
        if (!isGenericMime(resolverType)) {
            return resolverType;
        }
        if (!isGenericMime(hint)) {
            return hint;
        }
        return DEFAULT_MIME;
    }

    private boolean isGenericMime(@Nullable String type) {
        return type == null
                || isBlank(type)
                || "*/*".equals(type)
                || DEFAULT_MIME.equalsIgnoreCase(type);
    }

    private boolean isBlank(@NonNull String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!Character.isWhitespace(character) && !Character.isSpaceChar(character)) {
                return false;
            }
        }
        return true;
    }

    @NonNull
    AttachmentApi attachmentApi() {
        return attachmentApi;
    }

    @NonNull
    File cacheDir() {
        return context.getCacheDir();
    }

    static final class OversizeException extends IOException {
        private static final long serialVersionUID = 1L;
    }
}
