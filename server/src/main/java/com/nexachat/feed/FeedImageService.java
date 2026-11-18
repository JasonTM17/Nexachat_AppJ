package com.nexachat.feed;

import com.nexachat.attachment.StorageService;
import com.nexachat.attachment.UploadAdmission;
import com.nexachat.common.MagicBytes;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.feed.dto.FeedImageResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;

/**
 * Feed image upload and serving.
 *
 * <p>Uploads are stored through the shared {@link StorageService} pipeline
 * (magic-byte sniffing, server-minted key, 25MB ceiling) under the
 * {@code feed/} prefix and recorded as unbound rows. A post binds one image;
 * an unbound upload is swept after the retention window.
 *
 * <p><b>Read access</b> is feed-only: the bytes are served by
 * {@code GET /feed/images/{id}} and by nothing else. The raw
 * {@code /api/v1/files/**} path refuses a feed key unless the same rule
 * passes ({@link FeedImageAccess}), so knowing the storage key is not a
 * capability.
 */
@Service
public class FeedImageService {

    private static final Logger log = LoggerFactory.getLogger(FeedImageService.class);

    /** Logical storage subdirectory for feed images. */
    static final String STORAGE_SUBDIR = "feed";

    /** The feed accepts still images only; the sniffed type decides, never the client. */
    static final Set<String> ALLOWED_MIME_TYPES = Set.of("image/jpeg", "image/png", "image/webp");

    static final int IMAGES_PER_MINUTE = 10;
    static final int IMAGES_PER_DAY = 100;
    static final int MAX_UNBOUND_IMAGES = 20;
    static final int MAX_IMAGE_DIMENSION = 16384;

    private final FeedStore store;
    private final FeedRateLimits rateLimits;
    private final StorageService storageService;
    private final UploadAdmission uploadAdmission;
    private final Clock clock;

    public FeedImageService(FeedStore store,
                            FeedRateLimits rateLimits,
                            StorageService storageService,
                            UploadAdmission uploadAdmission,
                            Clock clock) {
        this.store = store;
        this.rateLimits = rateLimits;
        this.storageService = storageService;
        this.uploadAdmission = uploadAdmission;
        this.clock = clock;
    }

    /** A stored image plus an open stream over its bytes; the caller closes it. */
    public record ImageContent(FeedStore.ImageRow image, StorageService.StreamHandle content)
            implements AutoCloseable {

        @Override
        public void close() throws IOException {
            content.close();
        }
    }

    /**
     * Stores {@code file} as an unbound feed image owned by {@code ownerId}.
     *
     * @throws ApiException {@code VALIDATION_FAILED} (missing/empty file or bad
     *                      dimensions), {@code FILE_TOO_LARGE} (413),
     *                      {@code MIME_NOT_ALLOWED} (415 — anything that is not
     *                      a sniffed JPEG/PNG/WebP), {@code RATE_LIMITED} (429)
     */
    @Transactional
    public FeedImageResponse upload(UUID ownerId, MultipartFile file, Integer width,
                                    Integer height) {
        rateLimits.consume("feed:image:minute:" + ownerId, IMAGES_PER_MINUTE,
                Duration.ofMinutes(1), "Too many image uploads");
        rateLimits.consume("feed:image:day:" + ownerId, IMAGES_PER_DAY,
                Duration.ofDays(1), "Daily image upload limit reached");
        if (store.countUnboundImages(ownerId) >= MAX_UNBOUND_IMAGES) {
            throw ApiException.of(ApiErrorCode.RATE_LIMITED,
                    "Too many unattached feed images; bind or let them expire first");
        }
        if (file == null || file.isEmpty()) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "Image file is required");
        }
        if (file.getSize() > StorageService.MAX_FILE_SIZE_BYTES) {
            throw ApiException.of(ApiErrorCode.FILE_TOO_LARGE);
        }
        validateDimension("width", width);
        validateDimension("height", height);

        try (UploadAdmission.Permit permit = uploadAdmission.acquire()) {
            final byte[] bytes;
            try {
                bytes = file.getBytes();
            } catch (IOException failure) {
                throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                        "Could not read the uploaded image");
            }
            if (bytes.length > StorageService.MAX_FILE_SIZE_BYTES) {
                throw ApiException.of(ApiErrorCode.FILE_TOO_LARGE);
            }
            String sniffed = MagicBytes.sniff(bytes);
            if (sniffed == null || !ALLOWED_MIME_TYPES.contains(sniffed)) {
                throw ApiException.of(ApiErrorCode.MIME_NOT_ALLOWED);
            }
            StorageService.StoredFile stored = storageService.store(
                    STORAGE_SUBDIR, file.getOriginalFilename(), file.getContentType(), bytes);
            UUID imageId = UUID.randomUUID();
            store.insertImage(imageId, ownerId, stored.storageKey(), stored.mimeType(),
                    stored.sizeBytes(), width, height, clock.instant());
            log.info("Feed image {} stored for {}", imageId, ownerId);
            return new FeedImageResponse(imageId, FeedService.IMAGE_PATH + imageId, width, height,
                    stored.mimeType());
        }
    }

    /**
     * Opens one image for the caller: its owner always may, everyone else only
     * while it is attached to a post they can see.
     *
     * @throws ApiException {@code FILE_NOT_FOUND} when the image is missing or
     *                      not readable by the caller
     */
    @Transactional(readOnly = true)
    public ImageContent open(UUID viewerId, UUID imageId) {
        FeedStore.ImageRow image = store.findImage(imageId)
                .filter(row -> row.ownerId().equals(viewerId)
                        || store.isImageVisibleTo(imageId, viewerId))
                .orElseThrow(() -> ApiException.of(ApiErrorCode.FILE_NOT_FOUND));
        return new ImageContent(image, storageService.open(image.storageKey()));
    }

    private static void validateDimension(String field, Integer value) {
        if (value != null && (value < 0 || value > MAX_IMAGE_DIMENSION)) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                    field + " must be between 0 and " + MAX_IMAGE_DIMENSION);
        }
    }
}
