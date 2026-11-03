package com.nexachat.attachment;

import com.nexachat.common.error.ApiException;
import com.nexachat.common.error.ApiErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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
