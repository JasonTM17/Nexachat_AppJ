package com.nexachat.attachment;

import com.nexachat.common.error.ApiException;
import com.nexachat.common.error.ApiErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class LocalStorageServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void storesJpegWithJpgExtension() {
        byte[] jpeg = hex("FFD8FFE000104A4649460001");

        StorageService.StoredFile stored =
                newService().store("avatars", "me.jpg", "image/jpeg", jpeg);

        assertThat(stored.storageKey()).startsWith("avatars/").endsWith(".jpg");
        assertThat(stored.mimeType()).isEqualTo("image/jpeg");
        assertThat(stored.sizeBytes()).isEqualTo(jpeg.length);
    }

    @Test
    void storageKeyUsesUuidShape() {
        StorageService.StoredFile stored = newService().store(
                "attachments", "a.png", "image/png",
                hex("89504E470D0A1A0A0000000D49484452"));

        assertThat(stored.storageKey()).matches("attachments/[0-9a-f-]{36}\\.png");
    }

    @Test
    void rejectsExecutableBytes() {
        ApiException ex = catchThrowableOfType(ApiException.class, () ->
                newService().store("attachments", "setup.exe", "application/octet-stream",
                        hex("4D5A90000300000004000000")));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.MIME_NOT_ALLOWED);
        assertThat(tempDir).isEmptyDirectory();
    }

    @Test
    void rejectsUnknownBytes() {
        ApiException ex = catchThrowableOfType(ApiException.class, () ->
                newService().store("attachments", "blob.bin", "application/octet-stream",
                        hex("00010203040506070809")));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.MIME_NOT_ALLOWED);
    }

    @Test
    void rejectsHtmlAndSvgPayloads() {
        byte[] html = "<html><script>alert(1)</script></html>".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] svg = "<svg xmlns='http://www.w3.org/2000/svg'><script>alert(1)</script></svg>"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);

        ApiException htmlEx = catchThrowableOfType(ApiException.class,
                () -> newService().store("attachments", "page.html", "text/html", html));
        ApiException svgEx = catchThrowableOfType(ApiException.class,
                () -> newService().store("attachments", "image.svg", "image/svg+xml", svg));

        assertThat(htmlEx.getCode()).isEqualTo(ApiErrorCode.MIME_NOT_ALLOWED);
        assertThat(svgEx.getCode()).isEqualTo(ApiErrorCode.MIME_NOT_ALLOWED);
        assertThat(tempDir).isEmptyDirectory();
    }

    @Test
    void rejectsEmptyContent() {
        ApiException ex = catchThrowableOfType(ApiException.class, () ->
                newService().store("avatars", "empty.jpg", "image/jpeg", new byte[0]));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.MIME_NOT_ALLOWED);
    }

    @Test
    void rejectsOversizedFile() {
        byte[] big = new byte[(int) LocalStorageService.MAX_FILE_SIZE_BYTES + 1];
        big[0] = (byte) 0xFF;
        big[1] = (byte) 0xD8;
        big[2] = (byte) 0xFF;

        ApiException ex = catchThrowableOfType(ApiException.class, () ->
                newService().store("attachments", "big.jpg", "image/jpeg", big));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.FILE_TOO_LARGE);
        assertThat(tempDir).isEmptyDirectory();
    }

    @Test
    void rejectsTraversalSubdir() {
        for (String hostile : new String[] {"..", "../evil", "a/b"}) {
            ApiException ex = catchThrowableOfType(ApiException.class, () ->
                    newService().store(hostile, "x.jpg", "image/jpeg",
                            hex("FFD8FFE000104A4649460001")));
            assertThat(ex.getCode()).isEqualTo(ApiErrorCode.VALIDATION_FAILED);
        }
        assertThat(tempDir).isEmptyDirectory();
    }

    @Test
    void ignoresClientFilenameAndDeclaredContentType() {
        byte[] jpeg = hex("FFD8FFE000104A4649460001");

        StorageService.StoredFile stored =
                newService().store("avatars", "evil.exe", "application/x-msdownload", jpeg);

        assertThat(stored.storageKey()).endsWith(".jpg");
        assertThat(stored.mimeType()).isEqualTo("image/jpeg");
    }

    @Test
    void storesDocxZipWithSafeZipExtension() {
        byte[] docx = hex("504B0304140006000800");

        StorageService.StoredFile stored = newService().store(
                "attachments", "report.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", docx);

        assertThat(stored.storageKey()).endsWith(".zip");
        assertThat(stored.mimeType()).isEqualTo("application/zip");
    }

    @Test
    void resolveUrlUsesFilesPrefix() {
        assertThat(newService().resolveUrl("avatars/abc.jpg"))
                .isEqualTo("/api/v1/files/avatars/abc.jpg");
    }

    @Test
    void writesBytesToDiskWithReportedSize() throws IOException {
        byte[] png = hex("89504E470D0A1A0A0000000D49484452");

        StorageService.StoredFile stored =
                newService().store("avatars", "p.png", "image/png", png);

        Path written = tempDir.resolve(stored.storageKey());
        assertThat(written).exists().hasBinaryContent(png);
        assertThat(stored.sizeBytes()).isEqualTo(Files.size(written));
    }

    // -------------------------------------------------------------------- read

    @Test
    void read_returnsStoredBytes() {
        byte[] png = hex("89504E470D0A1A0A0000000D49484452");
        StorageService.StoredFile stored =
                newService().store("attachments", "p.png", "image/png", png);

        assertThat(newService().read(stored.storageKey())).containsExactly(png);
    }

    @Test
    void read_traversalKey_throwsFileNotFound() {
        // Same rejection set as store: the read side is the last line of
        // defence behind FileController's key check.
        for (String hostile : new String[] {
                "..", "../escape.png", "a/../b.png", "a\\b.png", "  ",
                tempDir.resolveSibling("outside.png").toAbsolutePath().toString().replace('\\', '/'),
                "%2e%2e/escape.png", "%252e%252e/escape.png"}) {
            ApiException ex = catchThrowableOfType(ApiException.class,
                    () -> newService().read(hostile));
            assertThat(ex.getCode()).isEqualTo(ApiErrorCode.FILE_NOT_FOUND);
        }
        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> newService().read(null));
        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.FILE_NOT_FOUND);
    }

    @Test
    void read_missingFile_throwsFileNotFound() {
        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> newService().read("attachments/never-written.png"));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.FILE_NOT_FOUND);
        assertThat(ex.getHttpStatus()).isEqualTo(404);
    }

    @Test
    void deleteIfExists_isIdempotentForStoredAttachmentFiles() throws IOException {
        String key = "attachments/00000000-0000-0000-0000-000000000001.png";
        Path file = tempDir.resolve(key);
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[] {1, 2, 3});

        assertThat(newService().deleteIfExists(key)).isTrue();
        assertThat(file).doesNotExist();
        assertThat(newService().deleteIfExists(key)).isFalse();
    }

    @Test
    void exists_checksRegularFilesAndRejectsTraversal() throws IOException {
        String key = "attachments/00000000-0000-0000-0000-000000000002.png";
        Path file = tempDir.resolve(key);
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[] {1, 2, 3});
        LocalStorageService service = newService();

        assertThat(service.exists(key)).isTrue();
        assertThat(service.exists("attachments/not-present.png")).isFalse();

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> service.exists("../outside.png"));
        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.FILE_NOT_FOUND);
    }

    @Test
    void deleteIfExists_rejectsTraversalAndLeavesOutsideFileUntouched() throws IOException {
        Path root = tempDir.resolve("uploads");
        Path outside = tempDir.resolve("outside.txt");
        Files.createDirectories(root);
        Files.writeString(outside, "must survive");
        LocalStorageService service = new LocalStorageService(root.toString());

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> service.deleteIfExists("../outside.txt"));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.FILE_NOT_FOUND);
        assertThat(outside).hasContent("must survive");
    }

    // ------------------------------------------------------------------- open

    @Test
    void open_returnsStreamAtOffsetZeroWithExactSize() throws IOException {
        byte[] png = hex("89504E470D0A1A0A0000000D49484452");
        StorageService.StoredFile stored =
                newService().store("attachments", "p.png", "image/png", png);

        try (StorageService.StreamHandle handle = newService().open(stored.storageKey())) {
            assertThat(handle.sizeBytes()).isEqualTo(png.length);
            assertThat(handle.content().readAllBytes()).containsExactly(png);
        }
    }

    @Test
    void open_rejectsCaseVariantWhenTheFilesystemResolvesIt() {
        byte[] png = hex("89504E470D0A1A0A0000000D49484452");
        StorageService.StoredFile stored =
                newService().store("attachments", "p.png", "image/png", png);
        String caseVariant = stored.storageKey().toUpperCase(Locale.ROOT);
        Path variantPath = tempDir.resolve(caseVariant);

        if (!Files.exists(variantPath)) {
            // On case-sensitive filesystems the altered path cannot reach this file.
            assertThat(variantPath).doesNotExist();
            return;
        }

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> newService().open(caseVariant));

        assertThat(ex).as("case-varied paths must not resolve to the stored file").isNotNull();
        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.FILE_NOT_FOUND);
    }

    @Test
    void open_traversalAndAbsolutePath_throwsFileNotFound() {
        for (String hostile : new String[] {
                "..", "../escape.png", "a\\b.png", "  ",
                tempDir.resolveSibling("outside.png").toAbsolutePath().toString().replace('\\', '/')}) {
            ApiException ex = catchThrowableOfType(ApiException.class,
                    () -> newService().open(hostile));
            assertThat(ex.getCode()).isEqualTo(ApiErrorCode.FILE_NOT_FOUND);
        }
    }

    @Test
    void open_missingFile_throwsFileNotFound() {
        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> newService().open("attachments/never-written.png"));
        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.FILE_NOT_FOUND);
        assertThat(ex.getHttpStatus()).isEqualTo(404);
    }

    private LocalStorageService newService() {
        return new LocalStorageService(tempDir.toString());
    }

    private static byte[] hex(String hex) {
        int length = hex.length();
        byte[] out = new byte[length / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
