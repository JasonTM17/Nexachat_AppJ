package com.nexachat.feed;

import com.nexachat.attachment.ByteRanges;
import com.nexachat.attachment.SizedInputStreamResource;
import com.nexachat.common.MultipartRequests;
import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.feed.dto.AuthorProfileResponse;
import com.nexachat.feed.dto.CommentPageResponse;
import com.nexachat.feed.dto.CommentResponse;
import com.nexachat.feed.dto.CreateCommentRequest;
import com.nexachat.feed.dto.CreatePostRequest;
import com.nexachat.feed.dto.EditCommentRequest;
import com.nexachat.feed.dto.EditPostRequest;
import com.nexachat.feed.dto.FeedImageResponse;
import com.nexachat.feed.dto.FeedVideoResponse;
import com.nexachat.feed.dto.PostPageResponse;
import com.nexachat.feed.dto.PostResponse;
import com.nexachat.feed.dto.ReactionListResponse;
import com.nexachat.social.SocialSettingsService;
import jakarta.validation.Valid;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Public-feed REST surface ({@code /api/v1/feed/**} and the canonical
 * social alias {@code /api/social/v1/**}), caller-scoped:
 *
 * <ul>
 *   <li>{@code GET /posts?limit=&cursor=} — newest-first keyset page;</li>
 *   <li>{@code POST /posts} — 201 on create, 200 with the stored row on a
 *       {@code clientPostId} replay;</li>
 *   <li>{@code GET/DELETE /posts/{id}} — one visible post, own-post delete;</li>
 *   <li>{@code PUT/DELETE /posts/{id}/reactions/{emoji}} — idempotent, returns
 *       the post's aggregate;</li>
 *   <li>{@code GET/POST /posts/{id}/comments} — oldest-first page, create;</li>
 *   <li>{@code DELETE /comments/{id}} — own-comment delete;</li>
 *   <li>{@code POST /images} — one unbound upload; {@code GET /images/{id}}
 *       serves it back to a caller who may see the post it belongs to.</li>
 * </ul>
 */
@RestController
@RequestMapping({"/api/v1/feed", "/api/social/v1"})
public class FeedController {

    private final FeedService feedService;
    private final FeedImageService imageService;
    private final SocialSettingsService socialSettings;

    public FeedController(FeedService feedService, FeedImageService imageService,
                          SocialSettingsService socialSettings) {
        this.feedService = feedService;
        this.imageService = imageService;
        this.socialSettings = socialSettings;
    }

    @GetMapping("/posts")
    public PostPageResponse list(@AuthenticationPrincipal JwtPrincipal principal,
                                 @RequestParam(name = "limit", required = false) Integer limit,
                                 @RequestParam(name = "cursor", required = false) String cursor,
                                 @RequestParam(name = "authorId", required = false) UUID authorId,
                                 @RequestParam(name = "q", required = false) String q) {
        return feedService.list(requireUserId(principal), limit, cursor, authorId, q);
    }

    @GetMapping("/authors/{authorId}")
    public AuthorProfileResponse authorProfile(@AuthenticationPrincipal JwtPrincipal principal,
                                               @PathVariable UUID authorId) {
        return feedService.getAuthorProfile(requireUserId(principal), authorId);
    }

    @PostMapping("/posts")
    public ResponseEntity<PostResponse> create(@AuthenticationPrincipal JwtPrincipal principal,
                                               @Valid @RequestBody CreatePostRequest request) {
        if (request.videoId() != null) {
            requireVideoUploadsEnabled();
        }
        FeedService.Result result = feedService.create(requireUserId(principal), request);
        return ResponseEntity
                .status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(result.post());
    }

    @GetMapping("/posts/{postId}")
    public PostResponse get(@AuthenticationPrincipal JwtPrincipal principal,
                            @PathVariable UUID postId) {
        return feedService.get(requireUserId(principal), postId);
    }

    @PatchMapping("/posts/{postId}")
    public PostResponse editPost(@AuthenticationPrincipal JwtPrincipal principal,
                                 @PathVariable UUID postId,
                                 @Valid @RequestBody EditPostRequest request) {
        return feedService.editPost(requireUserId(principal), postId, request);
    }

    @DeleteMapping("/posts/{postId}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal JwtPrincipal principal,
                                       @PathVariable UUID postId) {
        feedService.deletePost(requireUserId(principal), postId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/posts/{postId}/reactions/{emoji}")
    public ReactionListResponse addReaction(@AuthenticationPrincipal JwtPrincipal principal,
                                            @PathVariable UUID postId,
                                            @PathVariable String emoji) {
        return feedService.react(requireUserId(principal), postId, emoji, true);
    }

    @DeleteMapping("/posts/{postId}/reactions/{emoji}")
    public ReactionListResponse removeReaction(@AuthenticationPrincipal JwtPrincipal principal,
                                               @PathVariable UUID postId,
                                               @PathVariable String emoji) {
        return feedService.react(requireUserId(principal), postId, emoji, false);
    }

    @GetMapping("/posts/{postId}/comments")
    public CommentPageResponse comments(@AuthenticationPrincipal JwtPrincipal principal,
                                        @PathVariable UUID postId,
                                        @RequestParam(name = "limit", required = false) Integer limit,
                                        @RequestParam(name = "cursor", required = false) String cursor) {
        return feedService.comments(requireUserId(principal), postId, limit, cursor);
    }

    @PostMapping("/posts/{postId}/comments")
    public ResponseEntity<CommentResponse> createComment(
            @AuthenticationPrincipal JwtPrincipal principal,
            @PathVariable UUID postId,
            @Valid @RequestBody CreateCommentRequest request) {
        FeedService.CommentResult result =
                feedService.createComment(requireUserId(principal), postId, request);
        return ResponseEntity
                .status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(result.comment());
    }

    @PatchMapping("/comments/{commentId}")
    public CommentResponse editComment(@AuthenticationPrincipal JwtPrincipal principal,
                                       @PathVariable UUID commentId,
                                       @Valid @RequestBody EditCommentRequest request) {
        return feedService.editComment(requireUserId(principal), commentId, request);
    }

    @DeleteMapping("/comments/{commentId}")
    public ResponseEntity<Void> deleteComment(@AuthenticationPrincipal JwtPrincipal principal,
                                              @PathVariable UUID commentId) {
        feedService.deleteComment(requireUserId(principal), commentId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/images")
    public ResponseEntity<FeedImageResponse> uploadImage(
            @AuthenticationPrincipal JwtPrincipal principal,
            @RequestParam("file") List<MultipartFile> files,
            @RequestParam(name = "width", required = false) Integer width,
            @RequestParam(name = "height", required = false) Integer height) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(imageService.upload(requireUserId(principal),
                        MultipartRequests.requireSingleFile(files), width, height));
    }

    @GetMapping("/images/{imageId}")
    public ResponseEntity<Resource> image(@AuthenticationPrincipal JwtPrincipal principal,
                                          @PathVariable UUID imageId) {
        FeedImageService.ImageContent content = imageService.open(requireUserId(principal), imageId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.image().mimeType()))
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate())
                .contentLength(content.content().sizeBytes())
                .body(new SizedInputStreamResource(
                        content.content().content(), content.content().sizeBytes()));
    }

    @PostMapping("/videos")
    public ResponseEntity<FeedVideoResponse> uploadVideo(
            @AuthenticationPrincipal JwtPrincipal principal,
            @RequestParam("file") List<MultipartFile> files) {
        requireVideoUploadsEnabled();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(imageService.uploadVideo(requireUserId(principal),
                        MultipartRequests.requireSingleFile(files)));
    }

    @GetMapping("/videos/{videoId}")
    public ResponseEntity<Resource> video(
            @AuthenticationPrincipal JwtPrincipal principal,
            @PathVariable UUID videoId,
            @RequestHeader(name = HttpHeaders.RANGE, required = false) String rangeHeader) {
        UUID viewerId = requireUserId(principal);
        if (rangeHeader != null && !rangeHeader.isBlank()) {
            FeedImageService.VideoRangeContent slice =
                    imageService.openVideoRange(viewerId, videoId, rangeHeader);
            return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                    .contentType(MediaType.parseMediaType(slice.image().mimeType()))
                    .header("X-Content-Type-Options", "nosniff")
                    .header(HttpHeaders.ACCEPT_RANGES, ByteRanges.BYTES_UNIT)
                    .header(HttpHeaders.CONTENT_RANGE,
                            "bytes " + slice.start() + "-" + slice.end() + "/" + slice.totalSize())
                    .cacheControl(CacheControl.noStore().cachePrivate())
                    .contentLength(slice.count())
                    .body(new SizedInputStreamResource(slice.content().content(), slice.count()));
        }
        FeedImageService.ImageContent content = imageService.openVideo(viewerId, videoId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.image().mimeType()))
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.ACCEPT_RANGES, ByteRanges.BYTES_UNIT)
                .cacheControl(CacheControl.noStore().cachePrivate())
                .contentLength(content.content().sizeBytes())
                .body(new SizedInputStreamResource(
                        content.content().content(), content.content().sizeBytes()));
    }

    /**
     * New video uploads and new video binds are the two mutation entry points
     * the runtime switch gates. Both aliases route through this controller, so
     * one check covers each; existing videos stay readable regardless.
     */
    private void requireVideoUploadsEnabled() {
        if (!socialSettings.isVideoUploadsEnabled()) {
            throw ApiException.of(ApiErrorCode.SOCIAL_FEATURE_DISABLED,
                    "Video uploads are currently disabled");
        }
    }

    private static UUID requireUserId(JwtPrincipal principal) {
        if (principal == null) {
            throw ApiException.of(ApiErrorCode.UNAUTHORIZED, "Authentication required");
        }
        return principal.userId();
    }
}
