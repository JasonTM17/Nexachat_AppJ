package com.nexachat.feed;

import com.nexachat.attachment.ByteRanges;
import com.nexachat.attachment.StorageService;
import com.nexachat.attachment.StorageTransactionCleanup;
import com.nexachat.attachment.UploadAdmission;
import com.nexachat.common.MagicBytes;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.feed.dto.FeedImageResponse;
import com.nexachat.feed.dto.FeedVideoResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;

/**
 * Feed media upload and serving: still images and short MP4 videos.
 *
 * <p>Uploads are stored through the shared {@link StorageService} pipeline
 * (magic-byte sniffing, server-minted key, 25MB ceiling) under the
 * {@code feed/} prefix and recorded as unbound rows in {@code public_images}.
 * That table is the one-slot storage for either kind: a post binds one asset;
 * an unbound upload is swept after the retention window, and retention and
 * account deletion make no MIME distinction, so videos inherit both.
 *
 * <p><b>Videos.</b> {@code POST /videos} accepts at most
 * {@value #MAX_VIDEO_BYTES} bytes and only a file whose box tree proves a
 * video track ({@link MagicBytes#hasMp4VideoTrack(byte[])}) — the client
 * filename and declared MIME are never trusted. The check is structural: it
 * does not probe codecs and no transcoding or re-encoding happens here.
 *
 * <p><b>Read access</b> is feed-only: the bytes are served by
 * {@code GET /feed/images/{id}} or {@code GET /feed/videos/{id}} and by
 * nothing else. Each path refuses the other kind, so an image id never
 * serves video bytes and vice versa. The raw {@code /api/v1/files/**} path
 * refuses a feed key unless the same rule passes ({@link FeedImageAccess}),
 * so knowing the storage key is not a capability.
 */
@Service
public class FeedImageService {

    private static final Logger log = LoggerFactory.getLogger(FeedImageService.class);

    /** Logical storage subdirectory for feed media. */
    static final String STORAGE_SUBDIR = "feed";

    /** The feed accepts these still-image types; the sniffed type decides, never the client. */
    static final Set<String> ALLOWED_MIME_TYPES = Set.of("image/jpeg", "image/png", "image/webp");

    /** The only video container the feed accepts. */
    public static final String VIDEO_MIME = "video/mp4";

    /** Videos are capped well below the storage ceiling so one upload stays cheap. */
    public static final int MAX_VIDEO_BYTES = 4 * 1024 * 1024;

    static final int IMAGES_PER_MINUTE = 10;
    static final int IMAGES_PER_DAY = 100;
    static final int MAX_UNBOUND_IMAGES = 20;
    static final int MAX_IMAGE_DIMENSION = 16384;

    private final FeedStore store;
    private final FeedRateLimits rateLimits;
    private final StorageService storageService;
    private final UploadAdmission uploadAdmission;
    private final Clock clock;
    private final ObjectProvider<PlatformTransactionManager> transactionManagerProvider;

    public FeedImageService(FeedStore store,
                            FeedRateLimits rateLimits,
                            StorageService storageService,
                            UploadAdmission uploadAdmission,
                            Clock clock,
                            ObjectProvider<PlatformTransactionManager> transactionManagerProvider) {
        this.store = store;
        this.rateLimits = rateLimits;
        this.storageService = storageService;
        this.uploadAdmission = uploadAdmission;
        this.clock = clock;
        this.transactionManagerProvider = transactionManagerProvider;
    }

    private TransactionTemplate bindTransaction() {
        return new TransactionTemplate(transactionManagerProvider.getObject());
    }

    /** A stored asset plus an open stream over its bytes; the caller closes it. */
    public record ImageContent(FeedStore.ImageRow image, StorageService.StreamHandle content)
            implements AutoCloseable {

        @Override
        public void close() throws IOException {
            content.close();
        }
    }

    /** A stored video slice plus its exact byte range; the caller closes it. */
    public record VideoRangeContent(FeedStore.ImageRow image,
                                    StorageService.StreamHandle content,
                                    long start,
                                    long end,
                                    long count,
                                    long totalSize) implements AutoCloseable {

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
    public FeedImageResponse upload(UUID ownerId, MultipartFile file, Integer width,
                                    Integer height) {
        return uploadAsset(ownerId, file, width, height, false);
    }

    /**
     * Stores {@code file} as an unbound feed video owned by {@code ownerId}.
     *
     * @throws ApiException {@code VALIDATION_FAILED} (missing/empty file),
     *                      {@code FILE_TOO_LARGE} (413 — over 4 MiB),
     *                      {@code MIME_NOT_ALLOWED} (415 — anything whose box
     *                      tree does not prove an MP4 video track),
     *                      {@code RATE_LIMITED} (429)
     */
    public FeedVideoResponse uploadVideo(UUID ownerId, MultipartFile file) {
        FeedImageResponse stored = uploadAsset(ownerId, file, null, null, true);
        return new FeedVideoResponse(stored.imageId(), stored.url(), stored.mimeType());
    }

    private FeedImageResponse uploadAsset(UUID ownerId, MultipartFile file, Integer width,
                                          Integer height, boolean video) {
        long maxBytes = video ? MAX_VIDEO_BYTES : StorageService.MAX_FILE_SIZE_BYTES;
        if (store.countUnboundImages(ownerId) >= MAX_UNBOUND_IMAGES) {
            throw ApiException.of(ApiErrorCode.RATE_LIMITED,
                    "Too many unattached feed images; bind or let them expire first");
        }
        if (file == null || file.isEmpty()) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                    video ? "Video file is required" : "Image file is required");
        }
        if (file.getSize() > maxBytes) {
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
                        video ? "Could not read the uploaded video"
                                : "Could not read the uploaded image");
            }
            if (bytes.length > maxBytes) {
                throw ApiException.of(ApiErrorCode.FILE_TOO_LARGE);
            }
            if (video) {
                if (!MagicBytes.hasMp4VideoTrack(bytes)) {
                    throw ApiException.of(ApiErrorCode.MIME_NOT_ALLOWED);
                }
            } else {
                String sniffed = MagicBytes.sniff(bytes);
                if (sniffed == null || !ALLOWED_MIME_TYPES.contains(sniffed)) {
                    throw ApiException.of(ApiErrorCode.MIME_NOT_ALLOWED);
                }
            }
            // Quota is consumed only once the file passed validation, matching
            // the post/comment ordering: a rejected upload must not burn a token.
            // Images and videos share the same minute/day/unbound buckets, so
            // picking another type is never a bypass.
            rateLimits.consume("feed:image:minute:" + ownerId, IMAGES_PER_MINUTE,
                    Duration.ofMinutes(1), "Too many image uploads");
            rateLimits.consume("feed:image:day:" + ownerId, IMAGES_PER_DAY,
                    Duration.ofDays(1), "Daily image upload limit reached");

            // Storage I/O runs outside any caller transaction; the row insert
            // below runs in its own short transaction through
            // StorageTransactionCleanup.bind, which deletes the stored object
            // again when that transaction rolls back — no orphaned bytes.
            // The declared content type passed to storage is the sniffed one
            // for video; it is informational only and re-sniffed inside.
            StorageService.StoredFile stored = StorageTransactionCleanup.outsideCallerTransaction(
                    transactionManagerProvider.getObject(), () -> storageService.store(
                            STORAGE_SUBDIR, file.getOriginalFilename(),
                            video ? VIDEO_MIME : file.getContentType(), bytes));
            boolean storedTypeMatches = video
                    ? VIDEO_MIME.equals(stored.mimeType())
                    : ALLOWED_MIME_TYPES.contains(stored.mimeType());
            if (!storedTypeMatches) {
                storageService.deleteIfExists(stored.storageKey());
                throw ApiException.of(ApiErrorCode.INTERNAL_ERROR,
                        "Stored media type does not match the validated upload");
            }
            return StorageTransactionCleanup.bind(storageService, stored.storageKey(),
                    bindTransaction(), () -> {
                        UUID imageId = UUID.randomUUID();
                        store.insertImage(imageId, ownerId, stored.storageKey(), stored.mimeType(),
                                stored.sizeBytes(), width, height, clock.instant());
                        log.info("Feed {} {} stored for {}", video ? "video" : "image",
                                imageId, ownerId);
                        return new FeedImageResponse(imageId,
                                (video ? FeedService.VIDEO_PATH : FeedService.IMAGE_PATH) + imageId,
                                width, height, stored.mimeType());
                    });
        }
    }

    /**
     * Opens one image for the caller: its owner always may, everyone else only
     * while it is attached to a post they can see.
     *
     * @throws ApiException {@code FILE_NOT_FOUND} when the image is missing,
     *                      not readable by the caller, or not a still image
     */
    public ImageContent open(UUID viewerId, UUID imageId) {
        return openAsset(viewerId, imageId, false);
    }

    /**
     * Opens one video for the caller under the same visibility rule as images.
     *
     * @throws ApiException {@code FILE_NOT_FOUND} when the video is missing,
     *                      not readable by the caller, or not an MP4 video
     */
    public ImageContent openVideo(UUID viewerId, UUID videoId) {
        return openAsset(viewerId, videoId, true);
    }

    /**
     * Opens a single byte range of one video for the caller under the same
     * visibility rule as {@link #openVideo}.
     *
     * @throws ApiException {@code FILE_NOT_FOUND} when missing/unreadable, or
     *                      {@code RANGE_NOT_SATISFIABLE} when the header is
     *                      malformed or unsatisfiable
     */
    public VideoRangeContent openVideoRange(UUID viewerId, UUID videoId, String rangeHeader) {
        FeedStore.ImageRow image = requireReadableAssetInReadOnlyTx(viewerId, videoId, true);
        long size = image.sizeBytes();
        ByteRanges.ByteRange range = ByteRanges.parse(rangeHeader, size);
        long count = range.count();
        StorageService.StreamHandle handle =
                storageService.openRange(image.storageKey(), range.start(), count);
        return new VideoRangeContent(image, handle, range.start(), range.end(), count, size);
    }

    private FeedStore.ImageRow requireReadableAssetInReadOnlyTx(UUID viewerId, UUID imageId, boolean video) {
        PlatformTransactionManager txManager = transactionManagerProvider.getIfAvailable();
        if (txManager == null) {
            return requireReadableAsset(viewerId, imageId, video);
        }
        TransactionTemplate template = new TransactionTemplate(txManager);
        template.setReadOnly(true);
        return template.execute(status -> requireReadableAsset(viewerId, imageId, video));
    }

    private FeedStore.ImageRow requireReadableAsset(UUID viewerId, UUID imageId, boolean video) {
        return store.findImage(imageId)
                .filter(row -> video
                        ? VIDEO_MIME.equals(row.mimeType())
                        : ALLOWED_MIME_TYPES.contains(row.mimeType()))
                .filter(row -> row.boundAt() == null
                        ? row.ownerId().equals(viewerId)
                        : store.isImageVisibleTo(row.id(), viewerId))
                .orElseThrow(() -> ApiException.of(ApiErrorCode.FILE_NOT_FOUND));
    }

    private ImageContent openAsset(UUID viewerId, UUID imageId, boolean video) {
        FeedStore.ImageRow image = requireReadableAssetInReadOnlyTx(viewerId, imageId, video);
        return new ImageContent(image, storageService.open(image.storageKey()));
    }

    private static void validateDimension(String field, Integer value) {
        if (value != null && (value < 0 || value > MAX_IMAGE_DIMENSION)) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                    field + " must be between 0 and " + MAX_IMAGE_DIMENSION);
        }
    }
}
