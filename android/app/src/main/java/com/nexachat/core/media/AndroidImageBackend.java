package com.nexachat.core.media;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.os.Build;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import dagger.hilt.android.qualifiers.ApplicationContext;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Objects;

/** Android Bitmap/Exif implementation kept behind {@link ImageCompressor.Backend}. */
@SuppressLint("ExifInterface")
final class AndroidImageBackend implements ImageCompressor.Backend {

    private static final String[] GPS_TAGS = {
        "GPSVersionID",
        "GPSLatitude", "GPSLatitudeRef", "GPSLongitude", "GPSLongitudeRef",
        "GPSAltitude", "GPSAltitudeRef", "GPSTimeStamp", "GPSDateStamp",
        "GPSProcessingMethod", "GPSAreaInformation", "GPSSatellites", "GPSStatus",
        "GPSMeasureMode", "GPSDOP", "GPSSpeed", "GPSSpeedRef", "GPSTrack",
        "GPSTrackRef", "GPSImgDirection", "GPSImgDirectionRef", "GPSMapDatum",
        "GPSDestLatitude", "GPSDestLatitudeRef", "GPSDestLongitude", "GPSDestLongitudeRef",
        "GPSDestBearing", "GPSDestBearingRef", "GPSDestDistance", "GPSDestDistanceRef",
        "GPSDifferential", "GPSHPositioningError"
    };

    private final Context context;

    AndroidImageBackend(@ApplicationContext @NonNull Context context) {
        this.context = Objects.requireNonNull(context, "context").getApplicationContext();
    }

    @Nullable
    @Override
    public ImageCompressor.Dimensions readDimensions(@NonNull byte[] source) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(source, 0, source.length, options);
        if (options.outWidth <= 0 || options.outHeight <= 0) {
            return null;
        }
        return new ImageCompressor.Dimensions(options.outWidth, options.outHeight);
    }

    @Override
    public int readOrientation(@NonNull byte[] source, @NonNull String mimeType) {
        try {
            ExifInterface exif = new ExifInterface(new ByteArrayInputStream(source));
            int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL);
            return orientation >= ExifInterface.ORIENTATION_NORMAL
                    && orientation <= ExifInterface.ORIENTATION_ROTATE_270
                    ? orientation
                    : ExifInterface.ORIENTATION_NORMAL;
        } catch (IOException | RuntimeException exception) {
            return ExifMetadataScrubber.readOrientation(source, mimeType);
        }
    }

    @Nullable
    @Override
    public ImageCompressor.Raster decode(@NonNull byte[] source, int sampleSize) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        options.inScaled = false;
        Bitmap bitmap = BitmapFactory.decodeByteArray(source, 0, source.length, options);
        return bitmap == null ? null : new BitmapRaster(bitmap);
    }

    @Nullable
    @Override
    public ImageCompressor.Raster orientAndScale(
            @NonNull ImageCompressor.Raster source,
            int orientation,
            int maximumLongEdge) {
        Bitmap original = bitmap(source);
        Matrix matrix = orientationMatrix(orientation);
        Bitmap oriented = original;
        if (matrix != null) {
            oriented = Bitmap.createBitmap(
                    original, 0, 0, original.getWidth(), original.getHeight(), matrix, true);
        }

        try {
            int longEdge = Math.max(oriented.getWidth(), oriented.getHeight());
            if (longEdge > maximumLongEdge) {
                float scale = (float) maximumLongEdge / (float) longEdge;
                int width = Math.max(1, Math.round(oriented.getWidth() * scale));
                int height = Math.max(1, Math.round(oriented.getHeight() * scale));
                if (width > maximumLongEdge) {
                    width = maximumLongEdge;
                }
                if (height > maximumLongEdge) {
                    height = maximumLongEdge;
                }
                Bitmap scaled = Bitmap.createScaledBitmap(oriented, width, height, true);
                if (scaled != oriented && oriented != original && !oriented.isRecycled()) {
                    oriented.recycle();
                }
                oriented = scaled;
            }
            return new BitmapRaster(oriented);
        } catch (RuntimeException | OutOfMemoryError failure) {
            if (oriented != original && !oriented.isRecycled()) {
                oriented.recycle();
            }
            throw failure;
        }
    }

    @Nullable
    @Override
    public String encodeBlurHash(
            @NonNull ImageCompressor.Raster raster,
            int maximumEdge,
            int componentsX,
            int componentsY) {
        Bitmap bitmap = bitmap(raster);
        int longEdge = Math.max(bitmap.getWidth(), bitmap.getHeight());
        float scale = Math.min(1f, (float) maximumEdge / (float) longEdge);
        int width = Math.max(1, Math.round(bitmap.getWidth() * scale));
        int height = Math.max(1, Math.round(bitmap.getHeight() * scale));
        Bitmap sample = bitmap;
        boolean ownsSample = false;
        if (width != bitmap.getWidth() || height != bitmap.getHeight()) {
            sample = Bitmap.createScaledBitmap(bitmap, width, height, true);
            ownsSample = sample != bitmap;
        }
        try {
            int[] pixels = new int[sample.getWidth() * sample.getHeight()];
            sample.getPixels(pixels, 0, sample.getWidth(), 0, 0,
                    sample.getWidth(), sample.getHeight());
            return BlurHashEncoder.encode(
                    sample.getWidth(), sample.getHeight(), pixels, componentsX, componentsY);
        } finally {
            if (ownsSample && !sample.isRecycled()) {
                sample.recycle();
            }
        }
    }

    @Override
    public int apiLevel() {
        return Build.VERSION.SDK_INT;
    }

    @Nullable
    @Override
    @SuppressLint("NewApi")
    public byte[] encode(
            @NonNull ImageCompressor.Raster raster,
            @NonNull ImageCompressor.Encoding encoding) throws IOException {
        if (Build.VERSION.SDK_INT < 30
                && (encoding == ImageCompressor.Encoding.WEBP_LOSSY
                        || encoding == ImageCompressor.Encoding.WEBP_LOSSLESS)) {
            throw new IllegalArgumentException("WebP encoding requires Android 30 or newer");
        }
        Bitmap.CompressFormat format = switch (encoding) {
            case WEBP_LOSSY -> Bitmap.CompressFormat.WEBP_LOSSY;
            case WEBP_LOSSLESS -> Bitmap.CompressFormat.WEBP_LOSSLESS;
            case JPEG -> Bitmap.CompressFormat.JPEG;
            case PNG -> Bitmap.CompressFormat.PNG;
            default -> throw new IllegalArgumentException("unsupported image encoding");
        };
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        boolean success = bitmap(raster).compress(format, encoding.quality(), output);
        return success ? output.toByteArray() : null;
    }

    @NonNull
    @Override
    public byte[] stripLocationMetadata(
            @NonNull byte[] source, @NonNull String mimeType) throws IOException {
        if ("image/jpeg".equals(mimeType)
                || "image/png".equals(mimeType)
                || "image/webp".equals(mimeType)) {
            return ExifMetadataScrubber.stripLocationMetadata(source, mimeType);
        }
        if ("image/heic".equals(mimeType) || "image/heif".equals(mimeType)) {
            return stripHeifLocationMetadata(source);
        }
        return source;
    }

    @Override
    public void recycle(@NonNull ImageCompressor.Raster raster) {
        Bitmap bitmap = bitmap(raster);
        if (!bitmap.isRecycled()) {
            bitmap.recycle();
        }
    }

    private byte[] stripHeifLocationMetadata(byte[] source) throws IOException {
        File directory = new File(context.getCacheDir(), "media-uploads");
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("cannot create metadata scrub cache");
        }
        File temporary = File.createTempFile("nexa-exif-", ".heif", directory);
        try {
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                output.write(source);
            }
            ExifInterface exif = new ExifInterface(temporary.getAbsolutePath());
            boolean hasLocation = hasGpsAttributes(exif);
            if (!hasLocation) {
                return source;
            }
            for (String tag : GPS_TAGS) {
                exif.setAttribute(tag, null);
            }
            exif.saveAttributes();
            ExifInterface verified = new ExifInterface(temporary.getAbsolutePath());
            if (hasGpsAttributes(verified)) {
                throw new IOException("location metadata remains after cleanup");
            }
            byte[] clean;
            try (FileInputStream input = new FileInputStream(temporary);
                    ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] chunk = new byte[16 * 1024];
                int read;
                while ((read = input.read(chunk)) >= 0) {
                    output.write(chunk, 0, read);
                }
                clean = output.toByteArray();
            }
            if (clean.length > source.length) {
                throw new IOException("location metadata cleanup increased the image size");
            }
            return clean;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            temporary.delete();
        }
    }

    private static boolean hasGpsAttributes(ExifInterface exif) {
        for (String tag : GPS_TAGS) {
            String value = exif.getAttribute(tag);
            if (!MediaText.isBlank(value)) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    private static Matrix orientationMatrix(int orientation) {
        Matrix matrix = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f);
            case ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f);
            case ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f);
            case ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f);
                matrix.postScale(-1f, 1f);
            }
            case ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f);
            case ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f);
                matrix.postScale(-1f, 1f);
            }
            case ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f);
            default -> { return null; }
        }
        return matrix;
    }

    private static Bitmap bitmap(ImageCompressor.Raster raster) {
        if (!(raster instanceof BitmapRaster bitmapRaster)) {
            throw new IllegalArgumentException("unsupported bitmap raster");
        }
        return bitmapRaster.bitmap;
    }

    private static final class BitmapRaster implements ImageCompressor.Raster {
        private final Bitmap bitmap;

        private BitmapRaster(Bitmap bitmap) {
            this.bitmap = bitmap;
        }

        @Override
        public int width() {
            return bitmap.getWidth();
        }

        @Override
        public int height() {
            return bitmap.getHeight();
        }

        @Override
        public boolean hasAlpha() {
            return bitmap.hasAlpha();
        }
    }
}
