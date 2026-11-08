package com.nexachat.core.media;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import dagger.hilt.android.qualifiers.ApplicationContext;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.Objects;
import javax.inject.Inject;
import javax.inject.Singleton;

/** Compresses supported still images while keeping platform decoding behind a JVM-testable seam. */
@Singleton
public final class ImageCompressor {

    public static final int MAX_LONG_EDGE = 1920;
    public static final int BLUR_HASH_MAX_EDGE = 64;

    private final Backend backend;
    private final File temporaryDirectory;

    @Inject
    public ImageCompressor(@ApplicationContext @NonNull Context context) {
        this(new AndroidImageBackend(context),
                new File(context.getCacheDir(), "media-uploads"));
    }

    public ImageCompressor(@NonNull Backend backend, @NonNull File temporaryDirectory) {
        this.backend = Objects.requireNonNull(backend, "backend");
        this.temporaryDirectory = Objects.requireNonNull(temporaryDirectory, "temporaryDirectory");
    }

    /**
     * Returns {@code null} for formats that must pass through unchanged. Supported images always
     * return a cache-backed payload, or an in-memory privacy-clean fallback when the cache is not
     * writable. A failure to remove location metadata is fatal because uploading those bytes would
     * disclose private location data.
     */
    @Nullable
    public Result compress(
            @NonNull byte[] original,
            @NonNull String mimeType,
            @Nullable String originalName) throws IOException {
        Objects.requireNonNull(original, "original");
        Objects.requireNonNull(mimeType, "mimeType");
        String normalizedMime = mimeType.toLowerCase(Locale.ROOT);
        if (!isSupportedImage(normalizedMime)) {
            return null;
        }

        final byte[] safeOriginal;
        try {
            safeOriginal = Objects.requireNonNull(
                    backend.stripLocationMetadata(original, normalizedMime),
                    "metadata scrubber returned null");
        } catch (IOException | RuntimeException exception) {
            throw new PrivacyException("cannot remove image location metadata", exception);
        }
        if (safeOriginal.length > original.length) {
            throw new PrivacyException("metadata cleanup increased the original size");
        }

        Result fallback = Result.inline(
                safeOriginal,
                normalizedMime,
                replaceExtension(originalName, extensionForMime(normalizedMime)),
                null,
                false);
        Raster decoded = null;
        Raster normalized = null;
        try {
            Dimensions dimensions = backend.readDimensions(original);
            if (dimensions == null || dimensions.width() <= 0 || dimensions.height() <= 0) {
                return fallback;
            }

            int orientation = backend.readOrientation(original, normalizedMime);
            int sampleSize = sampleSizeFor(
                    Math.max(dimensions.width(), dimensions.height()), MAX_LONG_EDGE);
            decoded = backend.decode(original, sampleSize);
            if (decoded == null) {
                return fallback;
            }
            normalized = backend.orientAndScale(decoded, orientation, MAX_LONG_EDGE);
            if (normalized == null
                    || normalized.width() <= 0
                    || normalized.height() <= 0
                    || Math.max(normalized.width(), normalized.height()) > MAX_LONG_EDGE) {
                return fallback;
            }

            int componentsX = normalized.width() >= normalized.height() ? 4 : 3;
            int componentsY = normalized.width() >= normalized.height() ? 3 : 4;
            String blurHash = backend.encodeBlurHash(
                    normalized, BLUR_HASH_MAX_EDGE, componentsX, componentsY);
            if (!isValidBlurHash(blurHash)) {
                return fallback;
            }

            Encoding encoding = chooseEncoding(backend.apiLevel(), normalized.hasAlpha());
            byte[] compressed = backend.encode(normalized, encoding);
            if (compressed == null || compressed.length == 0) {
                return fallback;
            }
            if (compressed.length >= safeOriginal.length) {
                return fallback(safeOriginal, normalizedMime, originalName, blurHash);
            }
            try {
                return writeResult(
                        compressed,
                        encoding.mimeType(),
                        replaceExtension(originalName, encoding.extension()),
                        blurHash,
                        true);
            } catch (IOException cacheFailure) {
                return fallback(safeOriginal, normalizedMime, originalName, null);
            }
        } catch (OutOfMemoryError | IOException | RuntimeException failure) {
            return fallback;
        } finally {
            if (normalized != null && normalized != decoded) {
                backend.recycle(normalized);
            }
            if (decoded != null) {
                backend.recycle(decoded);
            }
        }
    }

    static boolean isSupportedImage(@NonNull String mimeType) {
        return "image/jpeg".equals(mimeType)
                || "image/png".equals(mimeType)
                || "image/webp".equals(mimeType)
                || "image/heic".equals(mimeType)
                || "image/heif".equals(mimeType);
    }

    static int sampleSizeFor(int sourceLongEdge, int targetLongEdge) {
        if (sourceLongEdge <= 0 || targetLongEdge <= 0) {
            throw new IllegalArgumentException("image dimensions must be positive");
        }
        int sampleSize = 1;
        while (sourceLongEdge / (sampleSize * 2) >= targetLongEdge
                && sampleSize <= (1 << 29)) {
            sampleSize *= 2;
        }
        return sampleSize;
    }

    static Encoding chooseEncoding(int apiLevel, boolean hasAlpha) {
        if (apiLevel >= 30) {
            return hasAlpha ? Encoding.WEBP_LOSSLESS : Encoding.WEBP_LOSSY;
        }
        return hasAlpha ? Encoding.PNG : Encoding.JPEG;
    }

    private static boolean isValidBlurHash(@Nullable String hash) {
        return hash != null
                && hash.length() >= 6
                && hash.length() <= 64
                && BlurHashDecoder.isValid(hash);
    }

    private Result fallback(
            byte[] safeOriginal,
            String originalMime,
            @Nullable String originalName,
            @Nullable String blurHash) {
        String extension = extensionForMime(originalMime);
        String safeName = replaceExtension(originalName, extension);
        try {
            return writeResult(safeOriginal, originalMime, safeName, blurHash, false);
        } catch (IOException cacheFailure) {
            return Result.inline(safeOriginal, originalMime, safeName, null, false);
        }
    }

    private Result writeResult(
            byte[] bytes,
            String mimeType,
            String fileName,
            @Nullable String blurHash,
            boolean recompressed) throws IOException {
        if (!temporaryDirectory.isDirectory() && !temporaryDirectory.mkdirs()
                && !temporaryDirectory.isDirectory()) {
            throw new IOException("cannot create media upload cache");
        }
        File temporaryFile = File.createTempFile(
                "nexa-image-", "." + extensionForMime(mimeType), temporaryDirectory);
        try (FileOutputStream output = new FileOutputStream(temporaryFile)) {
            output.write(bytes);
            output.flush();
        } catch (IOException failure) {
            //noinspection ResultOfMethodCallIgnored
            temporaryFile.delete();
            throw failure;
        }
        return Result.file(temporaryFile, mimeType, fileName, blurHash, recompressed);
    }

    private static String extensionForMime(String mimeType) {
        return switch (mimeType) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            case "image/heic" -> "heic";
            case "image/heif" -> "heif";
            default -> "bin";
        };
    }

    private static String replaceExtension(@Nullable String originalName, String extension) {
        String name = MediaText.isBlank(originalName)
                ? "attachment"
                : originalName;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        int dot = name.lastIndexOf('.');
        if (dot > slash) {
            name = name.substring(0, dot);
        }
        if (MediaText.isBlank(name)) {
            name = "attachment";
        }
        return name + "." + extension;
    }

    public enum Encoding {
        WEBP_LOSSY("image/webp", "webp", 80),
        WEBP_LOSSLESS("image/webp", "webp", 100),
        JPEG("image/jpeg", "jpg", 82),
        PNG("image/png", "png", 100);

        private final String mimeType;
        private final String extension;
        private final int quality;

        Encoding(String mimeType, String extension, int quality) {
            this.mimeType = mimeType;
            this.extension = extension;
            this.quality = quality;
        }

        public String mimeType() {
            return mimeType;
        }

        public String extension() {
            return extension;
        }

        public int quality() {
            return quality;
        }
    }

    public interface Raster {
        int width();

        int height();

        boolean hasAlpha();
    }

    public interface Backend {
        @Nullable Dimensions readDimensions(@NonNull byte[] source) throws IOException;

        int readOrientation(@NonNull byte[] source, @NonNull String mimeType) throws IOException;

        @Nullable Raster decode(@NonNull byte[] source, int sampleSize) throws IOException;

        @Nullable Raster orientAndScale(
                @NonNull Raster source, int orientation, int maximumLongEdge) throws IOException;

        @Nullable String encodeBlurHash(
                @NonNull Raster raster,
                int maximumEdge,
                int componentsX,
                int componentsY);

        int apiLevel();

        @Nullable byte[] encode(@NonNull Raster raster, @NonNull Encoding encoding)
                throws IOException;

        @NonNull byte[] stripLocationMetadata(
                @NonNull byte[] source, @NonNull String mimeType) throws IOException;

        void recycle(@NonNull Raster raster);
    }

    public static final class Dimensions {
        private final int width;
        private final int height;

        public Dimensions(int width, int height) {
            this.width = width;
            this.height = height;
        }

        public int width() {
            return width;
        }

        public int height() {
            return height;
        }
    }

    public static final class Result {
        @Nullable private final File file;
        @Nullable private final byte[] bytes;
        private final String mimeType;
        private final String fileName;
        @Nullable private final String blurHash;
        private final boolean recompressed;

        private Result(
                @Nullable File file,
                @Nullable byte[] bytes,
                String mimeType,
                String fileName,
                @Nullable String blurHash,
                boolean recompressed) {
            this.file = file;
            this.bytes = bytes;
            this.mimeType = mimeType;
            this.fileName = fileName;
            this.blurHash = blurHash;
            this.recompressed = recompressed;
        }

        private static Result file(
                File file,
                String mimeType,
                String fileName,
                @Nullable String blurHash,
                boolean recompressed) {
            return new Result(file, null, mimeType, fileName, blurHash, recompressed);
        }

        private static Result inline(
                byte[] bytes,
                String mimeType,
                String fileName,
                @Nullable String blurHash,
                boolean recompressed) {
            return new Result(null, bytes, mimeType, fileName, blurHash, recompressed);
        }

        @Nullable public File file() {
            return file;
        }

        @Nullable public byte[] bytes() {
            return bytes;
        }

        public String mimeType() {
            return mimeType;
        }

        public String fileName() {
            return fileName;
        }

        @Nullable public String blurHash() {
            return blurHash;
        }

        public boolean recompressed() {
            return recompressed;
        }

        public long sizeBytes() {
            return file == null ? bytes.length : file.length();
        }

        public void deleteTemporaryFile() {
            if (file != null) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        }
    }

    public static final class PrivacyException extends IOException {
        private static final long serialVersionUID = 1L;

        PrivacyException(String message, Throwable cause) {
            super(message, cause);
        }

        PrivacyException(String message) {
            super(message);
        }
    }
}
