package com.nexachat.attachment;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Prepares the servlet-container directory that receives multipart parts.
 *
 * <p>{@code spring.servlet.multipart.file-size-threshold: 0B} makes the
 * container write every upload part to disk before the controller runs. The
 * default location is the servlet container's work directory under its temp
 * root. A deployment that mounts an explicit scratch volume points
 * {@code spring.servlet.multipart.location} (environment:
 * {@code NEXACHAT_MULTIPART_TEMP_DIR}) at it. This bean creates that directory
 * before the web server accepts requests and proves the runtime user can write
 * to it, so a bad mount fails startup with a clear message instead of turning
 * the first upload into a generic parsing error.</p>
 *
 * <p>An empty value keeps the container default unchanged.</p>
 */
@Component
public class MultipartTempDirectory implements InitializingBean {

    private final String location;

    public MultipartTempDirectory(
            @Value("${spring.servlet.multipart.location:}") String location) {
        this.location = location;
    }

    @Override
    public void afterPropertiesSet() {
        prepare(location);
    }

    /**
     * Creates {@code location} when configured and checks it with a real write.
     *
     * @return the normalized directory, or {@code null} when the container
     *         default is in use
     * @throws IllegalStateException when the path cannot be created, is not a
     *                               directory, or the runtime user cannot
     *                               create and delete a probe file there
     */
    static Path prepare(String location) {
        if (location == null || location.isEmpty()) {
            return null;
        }
        if (location.isBlank()) {
            throw new IllegalStateException(
                    "spring.servlet.multipart.location (NEXACHAT_MULTIPART_TEMP_DIR) must not be blank");
        }
        Path directory;
        try {
            directory = Path.of(location).toAbsolutePath().normalize();
            Files.createDirectories(directory);
            if (!Files.isDirectory(directory)) {
                throw new IOException("path is not a directory");
            }
            Path probe = Files.createTempFile(directory, ".multipart-probe-", ".tmp");
            Files.deleteIfExists(probe);
        } catch (IOException | RuntimeException failure) {
            throw new IllegalStateException(
                    "spring.servlet.multipart.location (NEXACHAT_MULTIPART_TEMP_DIR) is not a "
                            + "writable directory: " + location, failure);
        }
        return directory;
    }
}
