package com.nexachat.admin;

import com.nexachat.rag.RagStats;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Staff-facing proxy for the aggregate RAG counters. The browser console is
 * allowed to call {@code /api/v1/admin/**} cross-origin, while the public RAG
 * stats route is outside that CORS surface; both endpoints expose the same
 * count-only snapshot.
 */
@RestController
@RequestMapping("/api/v1/admin/rag")
public class AdminRagStatsController {

    private final RagStats stats;

    public AdminRagStatsController(RagStats stats) {
        this.stats = stats;
    }

    @GetMapping(value = "/stats", produces = MediaType.APPLICATION_JSON_VALUE)
    public RagStats.Snapshot stats() {
        AdminGuard.requireStaff();
        return stats.snapshot();
    }
}
