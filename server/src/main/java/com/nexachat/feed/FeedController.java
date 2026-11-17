package com.nexachat.feed;

import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.feed.dto.CommentPageResponse;
import com.nexachat.feed.dto.CommentResponse;
import com.nexachat.feed.dto.CreateCommentRequest;
import com.nexachat.feed.dto.CreatePostRequest;
import com.nexachat.feed.dto.FeedImageResponse;
import com.nexachat.feed.dto.PostPageResponse;
import com.nexachat.feed.dto.PostResponse;
import com.nexachat.feed.dto.ReactionListResponse;
import jakarta.validation.Valid;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.util.UUID;

/**
 * Public-feed REST surface ({@code /api/v1/feed/**}), caller-scoped:
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
@RequestMapping("/api/v1/feed")
public class FeedController {

    private final FeedService feedService;
    private final FeedImageService imageService;

    public FeedController(FeedService feedService, FeedImageService imageService) {
        this.feedService = feedService;
        this.imageService = imageService;
    }

    @GetMapping("/posts")
    public PostPageResponse list(@AuthenticationPrincipal JwtPrincipal principal,
                                 @RequestParam(name = "limit", required = false) Integer limit,
                                 @RequestParam(name = "cursor", required = false) String cursor) {
        return feedService.list(requireUserId(principal), limit, cursor);
    }

    @PostMapping("/posts")
    public ResponseEntity<PostResponse> create(@AuthenticationPrincipal JwtPrincipal principal,
                                               @Valid @RequestBody CreatePostRequest request) {
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

    @DeleteMapping("/comments/{commentId}")
    public ResponseEntity<Void> deleteComment(@AuthenticationPrincipal JwtPrincipal principal,
                                              @PathVariable UUID commentId) {
        feedService.deleteComment(requireUserId(principal), commentId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/images")
    public ResponseEntity<FeedImageResponse> uploadImage(
            @AuthenticationPrincipal JwtPrincipal principal,
            @RequestParam("file") MultipartFile file,
            @RequestParam(name = "width", required = false) Integer width,
            @RequestParam(name = "height", required = false) Integer height) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(imageService.upload(requireUserId(principal), file, width, height));
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
                .body(new InputStreamResource(content.content().content()));
    }

    private static UUID requireUserId(JwtPrincipal principal) {
        if (principal == null) {
            throw ApiException.of(ApiErrorCode.UNAUTHORIZED, "Authentication required");
        }
        return principal.userId();
    }
}
