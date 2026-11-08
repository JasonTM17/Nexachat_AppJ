package com.nexachat.rag;

import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Aggregate RAG counters. Counts only; no message text, questions or vectors. */
@RestController
@Profile("!test")
@RequestMapping("/api/v1/rag")
public class RagStatsController {

    private final RagStats stats;

    public RagStatsController(RagStats stats) {
        this.stats = stats;
    }

    @GetMapping("/stats")
    public RagStats.Snapshot stats(@AuthenticationPrincipal JwtPrincipal principal) {
        if (principal == null) {
            throw ApiException.of(ApiErrorCode.UNAUTHORIZED, "Authentication required");
        }
        return stats.snapshot();
    }
}
