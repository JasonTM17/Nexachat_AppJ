package com.nexachat.attachment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class MultipartTempDirectoryTest {

    @TempDir
    Path tempDir;

    @Test
    void blankLocationKeepsTheContainerDefault() {
        assertThat(MultipartTempDirectory.prepare(null)).isNull();
        assertThat(MultipartTempDirectory.prepare("")).isNull();
    }

    @Test
    void aWhitespaceOnlyLocationFailsStartup() {
        IllegalStateException failure = catchThrowableOfType(IllegalStateException.class,
                () -> MultipartTempDirectory.prepare("   "));

        assertThat(failure).isNotNull();
        assertThat(failure.getMessage()).contains("must not be blank");
    }

    @Test
    void createsAMissingDirectoryAndDeletesItsProbeFile() throws IOException {
        Path nested = tempDir.resolve("scratch/nexachat");

        Path prepared = MultipartTempDirectory.prepare(nested.toString());

        assertThat(prepared).isEqualTo(nested.toAbsolutePath().normalize());
        assertThat(Files.isDirectory(nested)).isTrue();
        try (var entries = Files.list(nested)) {
            assertThat(entries.toList()).isEmpty();
        }
    }

    @Test
    void startupFailsWhenTheConfiguredPathIsAFile() throws IOException {
        Path file = Files.writeString(tempDir.resolve("not-a-directory"), "occupied");

        IllegalStateException failure = catchThrowableOfType(IllegalStateException.class,
                () -> new MultipartTempDirectory(file.toString()).afterPropertiesSet());

        assertThat(failure).isNotNull();
        assertThat(failure.getMessage())
                .contains("not a writable directory")
                .contains(file.toString());
    }

    @Test
    void startupFailsWhenThePathCannotBeCreated() {
        Path file = tempDir.resolve("parent-file");
        try {
            Files.writeString(file, "occupied");
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }

        IllegalStateException failure = catchThrowableOfType(IllegalStateException.class,
                () -> MultipartTempDirectory.prepare(file.resolve("child").toString()));

        assertThat(failure).isNotNull();
        assertThat(failure.getMessage()).contains("not a writable directory");
    }

    @Test
    void configuredDirectoryIsUsableByTheRuntimeUser() throws IOException {
        Path scratch = tempDir.resolve("scratch");

        MultipartTempDirectory.prepare(scratch.toString());

        assertThat(Files.isWritable(scratch)).isTrue();
        assertThat(Files.createTempFile(scratch, "probe", ".tmp")).exists();
        try (var entries = Files.list(scratch)) {
            assertThat(entries.toList()).hasSize(1);
        }
    }
}
