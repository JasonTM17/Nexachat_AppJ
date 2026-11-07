package com.nexachat.data;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.nexachat.core.common.ApiError;
import com.nexachat.core.common.Result;
import com.nexachat.core.network.ApiResult;
import com.nexachat.core.network.ApiResultKt;
import com.nexachat.core.network.AttachmentApi;
import com.nexachat.core.network.ProgressRequestBody;
import com.nexachat.core.network.dto.AttachmentDto;
import dagger.hilt.android.qualifiers.ApplicationContext;
import io.reactivex.rxjava3.core.BackpressureStrategy;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.FlowableEmitter;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import javax.inject.Inject;
import javax.inject.Singleton;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;

/**
 * Attachment transport boundary: multipart upload with live progress and
 * authenticated downloads into {@code cacheDir/attachments}.
 *
 * <p>Resolver access, file reads, and network operations run on RxJava's IO
 * scheduler. Each returned stream is cold and its subscriber owns disposal.
 */
@Singleton
public final class AttachmentRepository {

    public static final long MAX_UPLOAD_BYTES = 25L * 1024L * 1024L;
    public static final String ERROR_TOO_LARGE = "ATTACHMENT_TOO_LARGE";
    /** The picked content URI can no longer be read (grant revoked/gone). */
    public static final String ERROR_UNREADABLE = "ATTACHMENT_UNREADABLE";
    public static final String PART_FILE = "file";
    public static final String CACHE_DIR_NAME = "attachments";

    private static final String DEFAULT_MIME = "application/octet-stream";
    private static final int READ_BUFFER = 64 * 1024;
    private static final String DEFAULT_FILENAME = "attachment";
    private static final int MAX_NAME_LENGTH = 80;

    private final AttachmentApi attachmentApi;
    private final Context context;

    @Inject
    public AttachmentRepository(
            @NonNull AttachmentApi attachmentApi,
            @ApplicationContext @NonNull Context context) {
        this.attachmentApi = Objects.requireNonNull(attachmentApi, "attachmentApi");
        this.context = Objects.requireNonNull(context, "context");
    }

    @NonNull
    public Single<LocalAttachment> describe(@NonNull Uri uri) {
        return Single.fromCallable(() -> describeOnIo(uri))
                .subscribeOn(Schedulers.io());
    }

    @NonNull
    public Flowable<AttachmentUploadEvent> upload(
            @NonNull Uri uri,
            @Nullable String mimeHint) {
        return Flowable.create(rawEmitter -> {
            FlowableEmitter<AttachmentUploadEvent> emitter = rawEmitter.serialize();
            emitter.onNext(new AttachmentUploadEvent.Progress(0f));

            final LocalAttachment metadata;
            final byte[] bytes;
            try {
                metadata = describeOnIo(uri);
                bytes = readBounded(uri, metadata.getSizeBytes());
            } catch (OversizeException exception) {
                emitFailure(emitter, new ApiError.Unknown(ERROR_TOO_LARGE));
                return;
            } catch (IOException exception) {
                emitFailure(emitter, new ApiError.Network(exception.getMessage()));
                return;
            } catch (SecurityException exception) {
                // A picker grant may expire between selection and upload.
                emitFailure(emitter, new ApiError.Unknown(ERROR_UNREADABLE));
                return;
            }

            String mime = resolveMime(metadata.getMimeType(), mimeHint);
            MediaType mediaType = MediaType.parse(mime);
            RequestBody byteBody = RequestBody.create(bytes, mediaType);
            ProgressRequestBody requestBody = new ProgressRequestBody(
                    byteBody,
                    (written, total) -> {
                        if (total > 0L) {
                            float fraction = Math.max(0f, Math.min(1f,
                                    (float) written / (float) total));
                            emitter.onNext(new AttachmentUploadEvent.Progress(fraction));
                        }
                    });
            MultipartBody.Part part = MultipartBody.Part.createFormData(
                    PART_FILE,
                    metadata.getDisplayName() == null
                            ? DEFAULT_FILENAME
                            : metadata.getDisplayName(),
                    requestBody);

            DisposableCall.subscribe(
                    ApiResultKt.safeApiCall(attachmentApi.upload(part))
                            .subscribeOn(Schedulers.io()),
                    emitter);
        }, BackpressureStrategy.BUFFER).subscribeOn(Schedulers.io());
    }

    @NonNull
    public Single<Result<File>> downloadToCache(
            @NonNull String attachmentId,
            @Nullable String displayName) {
        return Single.defer(() -> ApiResultKt.safeApiCall(attachmentApi.content(attachmentId))
                    .map(result -> {
                        if (result instanceof ApiResult.Success<?> success) {
                            return saveBodyToCache((okhttp3.ResponseBody) success.getBody(),
                                    attachmentId, displayName);
                        }
                        return attachmentFailure(result);
                    }))
                .subscribeOn(Schedulers.io());
    }

    private void emitFailure(
            FlowableEmitter<AttachmentUploadEvent> emitter,
            ApiError error) {
        emitter.onNext(new AttachmentUploadEvent.Failed(error));
        emitter.onComplete();
    }

    @NonNull
    private Result<File> saveBodyToCache(
            @NonNull okhttp3.ResponseBody body,
            @NonNull String attachmentId,
            @Nullable String displayName) {
        File directory = new File(cacheDir(), CACHE_DIR_NAME);
        directory.mkdirs();
        File file = new File(directory, attachmentId + "-" + sanitize(displayName));
        try (InputStream input = body.byteStream();
                java.io.FileOutputStream output = new java.io.FileOutputStream(file)) {
            byte[] chunk = new byte[READ_BUFFER];
            int read;
            while ((read = input.read(chunk)) >= 0) {
                output.write(chunk, 0, read);
            }
            return new Result.Success<>(file);
        } catch (IOException exception) {
            // Never leave a truncated file that media viewers could open.
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            return Result.failure(new ApiError.Network(exception.getMessage()));
        }
    }

    /** Java-side resolver work; called from an Rx IO subscription. */
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
            throw new IOException("cannot open selected attachment");
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
    private File cacheDir() {
        return context.getCacheDir();
    }

    private String sanitize(@Nullable String name) {
        if (name == null) {
            return DEFAULT_FILENAME;
        }
        StringBuilder sanitized = new StringBuilder();
        for (int index = 0; index < name.length() && sanitized.length() < MAX_NAME_LENGTH; index++) {
            char character = name.charAt(index);
            boolean safe = (character >= 'A' && character <= 'Z')
                    || (character >= 'a' && character <= 'z')
                    || (character >= '0' && character <= '9')
                    || character == '.' || character == '_' || character == '-';
            sanitized.append(safe ? character : '_');
        }
        String result = sanitized.toString();
        return result.isBlank() ? DEFAULT_FILENAME : result;
    }

    @NonNull
    private static Result<File> attachmentFailure(@NonNull ApiResult<?> result) {
        if (result instanceof ApiResult.HttpError<?> httpError) {
            return Result.failure(ApiError.from(
                    httpError.getStatus(), httpError.getErrorCode()));
        }
        ApiResult.NetworkError<?> networkError = (ApiResult.NetworkError<?>) result;
        return Result.failure(new ApiError.Network(networkError.getCause().getMessage()));
    }

    private static final class DisposableCall {
        private DisposableCall() {}

        private static void subscribe(
                @NonNull Single<ApiResult<AttachmentDto>> call,
                @NonNull FlowableEmitter<AttachmentUploadEvent> emitter) {
            emitter.setDisposable(call.subscribe(
                    result -> {
                        if (result instanceof ApiResult.Success<?> success) {
                            emitter.onNext(new AttachmentUploadEvent.Progress(1f));
                            emitter.onNext(new AttachmentUploadEvent.Uploaded(
                                    (AttachmentDto) success.getBody()));
                        } else {
                            emitter.onNext(new AttachmentUploadEvent.Failed(
                                    apiFailure(result)));
                        }
                        emitter.onComplete();
                    },
                    error -> {
                        emitter.onNext(new AttachmentUploadEvent.Failed(
                                new ApiError.Network(error.getMessage())));
                        emitter.onComplete();
                    }));
        }

        @NonNull
        private static ApiError apiFailure(@NonNull ApiResult<?> result) {
            if (result instanceof ApiResult.HttpError<?> httpError) {
                return ApiError.from(httpError.getStatus(), httpError.getErrorCode());
            }
            ApiResult.NetworkError<?> networkError = (ApiResult.NetworkError<?>) result;
            return new ApiError.Network(networkError.getCause().getMessage());
        }
    }

    static final class OversizeException extends IOException {
        private static final long serialVersionUID = 1L;
    }
}
