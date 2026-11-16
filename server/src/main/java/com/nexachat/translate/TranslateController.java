package com.nexachat.translate;

import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.translate.dto.TranslateBatchItem;
import com.nexachat.translate.dto.TranslateBatchRequest;
import com.nexachat.translate.dto.TranslatePrefsResponse;
import com.nexachat.translate.dto.TranslateRequest;
import com.nexachat.translate.dto.TranslateResponse;
import com.nexachat.translate.dto.TranslationGlossaryResponse;
import com.nexachat.translate.dto.UpdateTranslatePrefsRequest;
import com.nexachat.translate.dto.UpdateTranslationGlossaryRequest;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Message translation endpoints. Single-message and batch translate plus the
 * caller-scoped glossary and auto-translate preference APIs. Every path runs
 * the same membership and privacy checks as message reading; per-item batch
 * refusals come back as typed error codes in the item's {@code error} field.
 */
@RestController
@Profile("!test")
@RequestMapping("/api/v1")
public class TranslateController {

    private final TranslateService service;

    public TranslateController(TranslateService service) {
        this.service = service;
    }

    @PostMapping("/messages/{messageId}/translate")
    public TranslateResponse translate(
            @AuthenticationPrincipal JwtPrincipal principal,
            @PathVariable UUID messageId,
            @Valid @RequestBody TranslateRequest request) {
        return service.translate(requireUserId(principal), messageId, request.targetLang());
    }

    @PostMapping("/conversations/{conversationId}/translate-batch")
    public Map<String, List<TranslateBatchItem>> translateBatch(
            @AuthenticationPrincipal JwtPrincipal principal,
            @PathVariable UUID conversationId,
            @Valid @RequestBody TranslateBatchRequest request) {
        List<TranslateBatchItem> results = service.translateBatch(
                requireUserId(principal), conversationId, request.messageIds(), request.targetLang());
        return Map.of("results", results);
    }

    @GetMapping("/conversations/{conversationId}/translation-glossary")
    public TranslationGlossaryResponse getGlossary(
            @AuthenticationPrincipal JwtPrincipal principal,
            @PathVariable UUID conversationId) {
        return service.getGlossary(requireUserId(principal), conversationId);
    }

    @PutMapping("/conversations/{conversationId}/translation-glossary")
    public TranslationGlossaryResponse putGlossary(
            @AuthenticationPrincipal JwtPrincipal principal,
            @PathVariable UUID conversationId,
            @Valid @RequestBody UpdateTranslationGlossaryRequest request) {
        return service.replaceGlossary(requireUserId(principal), conversationId, request.terms());
    }

    @GetMapping("/conversations/{conversationId}/translate-prefs")
    public TranslatePrefsResponse getPrefs(
            @AuthenticationPrincipal JwtPrincipal principal,
            @PathVariable UUID conversationId) {
        return service.getPrefs(requireUserId(principal), conversationId);
    }

    @PutMapping("/conversations/{conversationId}/translate-prefs")
    public TranslatePrefsResponse updatePrefs(
            @AuthenticationPrincipal JwtPrincipal principal,
            @PathVariable UUID conversationId,
            @Valid @RequestBody UpdateTranslatePrefsRequest request) {
        return service.updatePrefs(requireUserId(principal), conversationId,
                request.autoTranslate(), request.targetLang());
    }

    private static UUID requireUserId(JwtPrincipal principal) {
        if (principal == null) {
            throw ApiException.of(ApiErrorCode.UNAUTHORIZED, "Authentication required");
        }
        return principal.userId();
    }
}
