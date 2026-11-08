package com.nexachat.core.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Test;

public final class ImageCompressorTest {

    @Test
    public void samplesClampsAspectRatioAndRemovesExifGps() throws Exception {
        byte[] original = jpegWithExifGps(1, 120_000);
        FakeBackend backend = new FakeBackend(4000, 3000);
        File directory = Files.createTempDirectory("nexa-image-compression").toFile();

        ImageCompressor.Result result = new ImageCompressor(backend, directory)
                .compress(original, "image/jpeg", "photo.jpeg");

        assertNotNull(result);
        assertTrue(result.recompressed());
        assertEquals(2, backend.sampleSize);
        assertEquals(1920, backend.normalized.width());
        assertEquals(1440, backend.normalized.height());
        assertEquals("image/webp", result.mimeType());
        assertEquals("photo.webp", result.fileName());
        assertTrue(result.sizeBytes() < original.length * 0.40d);
        assertFalse(contains(readResultBytes(result), "GPSLatitude"));
        assertTrue(BlurHashDecoder.isValid(result.blurHash()));
        result.deleteTemporaryFile();
    }

    @Test
    public void appliesExifRotationBeforeClamping() throws Exception {
        byte[] original = jpegWithExifGps(6, 12_000);
        FakeBackend backend = new FakeBackend(4000, 3000);
        ImageCompressor.Result result = new ImageCompressor(
                backend, Files.createTempDirectory("nexa-image-rotation").toFile())
                .compress(original, "image/jpeg", "rotated.jpg");

        assertNotNull(result);
        assertEquals(6, backend.orientation);
        assertEquals(1440, backend.normalized.width());
        assertEquals(1920, backend.normalized.height());
        assertFalse(contains(readResultBytes(result), "GPSLatitude"));
        result.deleteTemporaryFile();
    }

    @Test
    public void neverGrowsImageAndKeepsOnlyGpsCleanOriginal() throws Exception {
        byte[] original = jpegWithExifGps(1, 400);
        FakeBackend backend = new FakeBackend(100, 80);
        backend.encoded = new byte[original.length + 1];
        ImageCompressor.Result result = new ImageCompressor(
                backend, Files.createTempDirectory("nexa-image-no-grow").toFile())
                .compress(original, "image/jpeg", "photo.jpg");

        assertNotNull(result);
        assertFalse(result.recompressed());
        assertTrue(result.sizeBytes() < original.length);
        assertTrue(BlurHashDecoder.isValid(result.blurHash()));
        assertFalse(contains(readResultBytes(result), "GPSLatitude"));
        result.deleteTemporaryFile();
    }

    @Test
    public void oomFallsBackToGpsCleanOriginalWithoutHash() throws Exception {
        byte[] original = jpegWithExifGps(6, 400);
        FakeBackend backend = new FakeBackend(100, 80);
        backend.throwOutOfMemory = true;
        ImageCompressor.Result result = new ImageCompressor(
                backend, Files.createTempDirectory("nexa-image-oom").toFile())
                .compress(original, "image/jpeg", "photo.jpg");

        assertNotNull(result);
        assertFalse(result.recompressed());
        assertNull(result.blurHash());
        assertFalse(contains(readResultBytes(result), "GPSLatitude"));
        result.deleteTemporaryFile();
    }

    @Test(expected = ImageCompressor.PrivacyException.class)
    public void refusesToReturnImageBytesWhenLocationScrubbingFails() throws Exception {
        FakeBackend backend = new FakeBackend(100, 80);
        backend.failMetadataScrub = true;
        ImageCompressor compressor = new ImageCompressor(
                backend, Files.createTempDirectory("nexa-image-privacy").toFile());

        compressor.compress(jpegWithExifGps(1, 100), "image/jpeg", "photo.jpg");
    }

    @Test
    public void unsupportedGifVideoAndPdfPassThroughUntouched() throws Exception {
        FakeBackend backend = new FakeBackend(100, 80);
        ImageCompressor compressor = new ImageCompressor(
                backend, Files.createTempDirectory("nexa-media-passthrough").toFile());

        assertNull(compressor.compress(new byte[] {1, 2, 3}, "image/gif", "anim.gif"));
        assertNull(compressor.compress(new byte[] {1, 2, 3}, "video/mp4", "clip.mp4"));
        assertNull(compressor.compress(new byte[] {1, 2, 3}, "application/pdf", "doc.pdf"));
        assertEquals(0, backend.metadataScrubCalls);
    }

    @Test
    public void alphaEncodingAndPowerOfTwoSamplingFollowPlatformRules() {
        assertEquals(ImageCompressor.Encoding.WEBP_LOSSLESS,
                ImageCompressor.chooseEncoding(30, true));
        assertEquals(ImageCompressor.Encoding.PNG,
                ImageCompressor.chooseEncoding(29, true));
        assertEquals(ImageCompressor.Encoding.WEBP_LOSSY,
                ImageCompressor.chooseEncoding(30, false));
        assertEquals(ImageCompressor.Encoding.JPEG,
                ImageCompressor.chooseEncoding(29, false));
        assertEquals(2, ImageCompressor.sampleSizeFor(4000, 1920));
        assertEquals(4, ImageCompressor.sampleSizeFor(8000, 1920));
        assertEquals(1, ImageCompressor.sampleSizeFor(1600, 1920));
    }

    @Test
    public void alphaImagesUsePlatformSpecificLosslessFormats() throws Exception {
        byte[] source = new byte[100];
        FakeBackend modern = new FakeBackend(8, 6);
        modern.rasterAlpha = true;
        ImageCompressor.Result modernResult = new ImageCompressor(
                modern, Files.createTempDirectory("nexa-alpha-modern").toFile())
                .compress(source, "image/png", "alpha.png");

        assertEquals(ImageCompressor.Encoding.WEBP_LOSSLESS, modern.lastEncoding);
        assertEquals("image/webp", modernResult.mimeType());
        assertEquals("alpha.webp", modernResult.fileName());
        modernResult.deleteTemporaryFile();

        FakeBackend older = new FakeBackend(8, 6);
        older.apiLevel = 29;
        older.rasterAlpha = true;
        ImageCompressor.Result olderResult = new ImageCompressor(
                older, Files.createTempDirectory("nexa-alpha-older").toFile())
                .compress(source, "image/png", "alpha.png");

        assertEquals(ImageCompressor.Encoding.PNG, older.lastEncoding);
        assertEquals("image/png", olderResult.mimeType());
        assertEquals("alpha.png", olderResult.fileName());
        olderResult.deleteTemporaryFile();
    }

    private static byte[] jpegWithExifGps(int orientation, int padding) throws IOException {
        ByteArrayOutputStream exif = new ByteArrayOutputStream();
        exif.write("Exif\0\0".getBytes(StandardCharsets.US_ASCII));
        exif.write(new byte[] {
            'I', 'I', 42, 0, 8, 0, 0, 0,
            1, 0, 0x12, 0x01, 3, 0, 1, 0, 0, 0,
            (byte) orientation, 0, 0, 0, 0, 0, 0, 0
        });
        exif.write("GPSLatitude=47/1,1/1,0/1".getBytes(StandardCharsets.US_ASCII));
        int segmentLength = exif.size() + 2;
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        jpeg.write(new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe1,
                (byte) (segmentLength >>> 8), (byte) segmentLength});
        exif.writeTo(jpeg);
        jpeg.write(new byte[] {(byte) 0xff, (byte) 0xda, 0, 2});
        for (int index = 0; index < padding; index++) {
            jpeg.write(index & 0xff);
        }
        return jpeg.toByteArray();
    }

    private static byte[] readResultBytes(ImageCompressor.Result result) throws IOException {
        return result.file() == null ? result.bytes() : Files.readAllBytes(result.file().toPath());
    }

    private static boolean contains(byte[] bytes, String value) {
        byte[] needle = value.getBytes(StandardCharsets.US_ASCII);
        outer:
        for (int start = 0; start <= bytes.length - needle.length; start++) {
            for (int offset = 0; offset < needle.length; offset++) {
                if (bytes[start + offset] != needle[offset]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private static final class FakeBackend implements ImageCompressor.Backend {
        private final int sourceWidth;
        private final int sourceHeight;
        private Raster normalized;
        private byte[] encoded = new byte[] {1, 2, 3};
        private boolean throwOutOfMemory;
        private boolean rasterAlpha;
        private boolean failMetadataScrub;
        private int metadataScrubCalls;
        private int sampleSize;
        private int orientation;
        private int apiLevel = 30;
        private ImageCompressor.Encoding lastEncoding;

        private FakeBackend(int sourceWidth, int sourceHeight) {
            this.sourceWidth = sourceWidth;
            this.sourceHeight = sourceHeight;
        }

        @Override
        public ImageCompressor.Dimensions readDimensions(byte[] source) {
            return new ImageCompressor.Dimensions(sourceWidth, sourceHeight);
        }

        @Override
        public int readOrientation(byte[] source, String mimeType) {
            return ExifMetadataScrubber.readOrientation(source, mimeType);
        }

        @Override
        public ImageCompressor.Raster decode(byte[] source, int sampleSize) {
            this.sampleSize = sampleSize;
            if (throwOutOfMemory) {
                throw new OutOfMemoryError("controlled decoder failure");
            }
            int width = Math.max(1, sourceWidth / sampleSize);
            int height = Math.max(1, sourceHeight / sampleSize);
            return new Raster(width, height, rasterAlpha);
        }

        @Override
        public ImageCompressor.Raster orientAndScale(
                ImageCompressor.Raster source, int orientation, int maximumLongEdge) {
            this.orientation = orientation;
            Raster input = (Raster) source;
            boolean transposed = orientation >= 5 && orientation <= 8;
            int width = transposed ? input.height : input.width;
            int height = transposed ? input.width : input.height;
            int longEdge = Math.max(width, height);
            double scale = Math.min(1.0, (double) maximumLongEdge / longEdge);
            width = Math.max(1, (int) Math.round(width * scale));
            height = Math.max(1, (int) Math.round(height * scale));
            normalized = new Raster(width, height, input.alpha);
            return normalized;
        }

        @Override
        public String encodeBlurHash(
                ImageCompressor.Raster raster,
                int maximumEdge,
                int componentsX,
                int componentsY) {
            assertTrue(maximumEdge <= ImageCompressor.BLUR_HASH_MAX_EDGE);
            assertTrue((componentsX == 4 && componentsY == 3)
                    || (componentsX == 3 && componentsY == 4));
            return "000000";
        }

        @Override public int apiLevel() { return apiLevel; }

        @Override
        public byte[] encode(ImageCompressor.Raster raster, ImageCompressor.Encoding encoding) {
            lastEncoding = encoding;
            return encoded;
        }

        @Override
        public byte[] stripLocationMetadata(byte[] source, String mimeType) throws IOException {
            metadataScrubCalls++;
            if (failMetadataScrub) {
                throw new IOException("controlled metadata cleanup failure");
            }
            return ExifMetadataScrubber.stripLocationMetadata(source, mimeType);
        }

        @Override public void recycle(ImageCompressor.Raster raster) { }
    }

    private static final class Raster implements ImageCompressor.Raster {
        private final int width;
        private final int height;
        private final boolean alpha;

        private Raster(int width, int height, boolean alpha) {
            this.width = width;
            this.height = height;
            this.alpha = alpha;
        }

        @Override public int width() { return width; }
        @Override public int height() { return height; }
        @Override public boolean hasAlpha() { return alpha; }
    }
}
