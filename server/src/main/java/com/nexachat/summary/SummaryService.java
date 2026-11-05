package com.nexachat.summary;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

@Service
@Profile("!test")
public class SummaryService {

    private static final int MIN_MESSAGES = 5;

    private final SummaryRateLimiter rateLimiter;
    private final SummaryWindowService windowService;
    private final SummaryProviderSelector providerSelector;
    private final SummaryCacheRepository cacheRepository;

    public SummaryService(
            SummaryRateLimiter rateLimiter,
            SummaryWindowService windowService,
            SummaryProviderSelector providerSelector,
            SummaryCacheRepository cacheRepository) {
        this.rateLimiter = rateLimiter;
        this.windowService = windowService;
        this.providerSelector = providerSelector;
        this.cacheRepository = cacheRepository;
    }

    public SummaryResponse summarize(UUID conversationId, UUID userId, SummaryRequest request) {
        rateLimiter.consume(userId);
        SummaryReadContext readContext = windowService.load(conversationId, userId, request);
        SummaryWindow window = readContext.window();
        if (window.messageCount() < MIN_MESSAGES) {
            throw new SummaryTooFewMessagesException();
        }

        String contentHash = contentHash(window);
        PreparedSummaryProvider prepared = providerSelector.prepare(readContext.aiSummaryConsent());
        if (!request.refresh()) {
            var cached = cacheRepository.findValid(conversationId, window.fromSeq(), window.toSeq(),
                    contentHash, prepared.cacheProvider(), request.language());
            if (cached.isPresent()) {
                SummaryCacheEntry entry = cached.get();
                SummaryGeneration generation = forRequest(entry.payload().toGeneration(), prepared, null);
                return SummaryResponse.create(entry.id(), window, generation,
                        true, entry.createdAt());
            }
        }

        SummaryGeneration generated = providerSelector.generate(prepared, window, request.language());
        SummaryCacheEntry entry;
        boolean cached;
        if (request.refresh()) {
            entry = cacheRepository.replace(conversationId, window, contentHash, generated.generatedBy(),
                    request.language(), generated);
            cached = false;
        } else {
            SummaryCacheRepository.SavedSummary saved = cacheRepository.saveIfAbsent(
                    conversationId, window, contentHash, generated.generatedBy(), request.language(), generated);
            entry = saved.entry();
            cached = !saved.inserted();
        }
        SummaryGeneration responseGeneration = forRequest(
                entry.payload().toGeneration(), prepared, generated);
        return SummaryResponse.create(entry.id(), window, responseGeneration, cached, entry.createdAt());
    }

    private static SummaryGeneration forRequest(
            SummaryGeneration cachedGeneration,
            PreparedSummaryProvider prepared,
            SummaryGeneration generatedThisRequest) {
        if (prepared.provider().id().equals("LOCAL")) {
            return new SummaryGeneration(cachedGeneration.content(), cachedGeneration.language(), "LOCAL", null,
                    prepared.fallbackReason());
        }
        if (generatedThisRequest != null && generatedThisRequest.fallbackReason() != null) {
            return new SummaryGeneration(cachedGeneration.content(), cachedGeneration.language(), "LOCAL", null,
                    generatedThisRequest.fallbackReason());
        }
        return cachedGeneration;
    }

    static String contentHash(SummaryWindow window) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (SummaryMessage message : window.messages()) {
                String value = message.id() + ":"
                        + (message.editedAt() == null ? "" : message.editedAt().toString())
                        + "\n";
                digest.update(value.getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
