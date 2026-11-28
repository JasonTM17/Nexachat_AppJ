package com.nexachat.social;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Anonymous bootstrap for the web Social page: the two effective feature
 * flags plus the currently published bilingual announcement, or {@code null}
 * when nothing is live. This is the only {@code /api/social/v1} route that
 * stays open while the surface is disabled — the page must be able to learn
 * that it is disabled — and it deliberately projects no revision numbers,
 * draft copy, or staff detail. The answer is never cacheable: a flag flip or
 * takedown must be visible on the next read.
 */
@RestController
public class SocialConfigController {

    private final SocialSettingsService settings;

    public SocialConfigController(SocialSettingsService settings) {
        this.settings = settings;
    }

    @GetMapping("/api/social/v1/config")
    public ResponseEntity<SocialSettingsService.PublicConfig> config() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(settings.publicConfig());
    }
}
