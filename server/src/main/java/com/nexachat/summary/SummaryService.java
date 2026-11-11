package com.nexachat.summary;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@Profile("!test")
public class SummaryService {

    private static final int MIN_MESSAGES = 5;

    private final SummaryRateLimiter rateLimiter;
    private final SummaryWindowService windowService;
    private final SummaryProviderSelector providerSelector;
    private final SummaryCacheRepository cacheRepository;
    private final ConversationAiOptOuts aiOptOuts;

    public SummaryService(
            SummaryRateLimiter rateLimiter,
            SummaryWindowService windowService,
            SummaryProviderSelector providerSelector,
            SummaryCacheRepository cacheRepository) {
        this(rateLimiter, windowService, providerSelector, cacheRepository, ConversationAiOptOuts.none());
    }

    @Autowired
    public SummaryService(
            SummaryRateLimiter rateLimiter,
            SummaryWindowService windowService,
            SummaryProviderSelector providerSelector,
            SummaryCacheRepository cacheRepository,
            ConversationAiOptOuts aiOptOuts) {
        this.rateLimiter = rateLimiter;
        this.windowService = windowService;
        this.providerSelector = providerSelector;
        this.cacheRepository = cacheRepository;
        this.aiOptOuts = aiOptOuts;
    }

    public SummaryResponse summarize(UUID conversationId, UUID userId, SummaryRequest request) {
        rateLimiter.consume(userId);
        SummaryReadContext readContext = windowService.load(conversationId, userId, request);
        SummaryWindow window = readContext.window();
        if (window.messageCount() < MIN_MESSAGES) {
            throw new SummaryTooFewMessagesException();
        }

        Set<UUID> optedOut = aiOptOuts.optedOutUserIds(conversationId);
        String contentHash = contentHash(window);
        PreparedSummaryProvider prepared = providerSelector.prepare(readContext.aiSummaryConsent(), window, request.refresh());
        if (!request.refresh()) {
            var cached = cacheRepository.findValid(conversationId, window.fromSeq(), window.toSeq(),
                    contentHash, prepared.cacheProvider(), request.language());
            if (cached.isPresent()) {
                SummaryCacheEntry entry = cached.get();
                if (replayAllowed(entry, window, optedOut)) {
                    SummaryGeneration generation = forRequest(entry.payload().toGeneration(), prepared, null);
                    return SummaryResponse.create(entry.id(), window, generation,
                            true, contextOmitted(generation, window, optedOut), entry.createdAt());
                }
                // A previous external summary cites a message whose author has
                // since opted out. It must never be replayed; remove it and
                // regenerate the cache entry below.
                cacheRepository.delete(conversationId, window.fromSeq(), window.toSeq(),
                        contentHash, prepared.cacheProvider(), request.language());
            }
        }

        SummaryGeneration generated = providerSelector.generate(prepared, window, request.language());
        SummaryCacheEntry entry;
        boolean cached;
        if (request.refresh()) {
            entry = cacheRepository.replace(userId, conversationId, window, contentHash, generated.generatedBy(),
                    request.language(), generated);
            cached = false;
        } else {
            SummaryCacheRepository.SavedSummary saved = cacheRepository.saveIfAbsent(
                    userId, conversationId, window, contentHash, generated.generatedBy(), request.language(),
                    generated);
            entry = saved.entry();
            cached = !saved.inserted();
            if (cached && !replayAllowed(entry, window, optedOut)) {
                // A concurrent fill that started before the opt-out won the
                // insert. It must not be replayed: drop it and store this
                // request's own generation under the same cache key.
                cacheRepository.delete(conversationId, entry.fromSeq(), entry.toSeq(),
                        entry.contentHash(), entry.cacheProvider(), entry.requestedLanguage());
                entry = cacheRepository.replace(userId, conversationId, window, contentHash,
                        generated.generatedBy(), request.language(), generated);
                cached = false;
            }
        }
        SummaryGeneration responseGeneration = forRequest(
                entry.payload().toGeneration(), prepared, generated);
        return SummaryResponse.create(entry.id(), window, responseGeneration, cached,
                contextOmitted(responseGeneration, window, optedOut), entry.createdAt());
    }

    private static SummaryGeneration forRequest(
            SummaryGeneration cachedGeneration,
            PreparedSummaryProvider prepared,
            SummaryGeneration generatedThisRequest) {
        if (prepared.provider().id().equals("LOCAL")) {
            return new SummaryGeneration(cachedGeneration.content(), cachedGeneration.language(), "LOCAL", null,
                    prepared.fallbackReason(), null);
        }
        if (generatedThisRequest != null && generatedThisRequest.fallbackReason() != null) {
            return new SummaryGeneration(cachedGeneration.content(), cachedGeneration.language(), "LOCAL", null,
                    generatedThisRequest.fallbackReason(), null);
        }
        return cachedGeneration;
    }

    /**
     * A cached external summary is replayed only when none of its cited
     * messages (or listed participants) belong to a member who opted out.
     * Local summaries may quote opted-out messages because they never leave
     * the server.
     */
    static boolean replayAllowed(
            SummaryCacheEntry entry,
            SummaryWindow window,
            Set<UUID> optedOut) {
        if (optedOut.isEmpty() || !"LLM".equals(entry.payload().generatedBy())) {
            return true;
        }
        SummaryContent content = entry.payload().content();
        Map<UUID, UUID> senderByMessage = new HashMap<>();
        for (SummaryMessage message : window.messages()) {
            if (message.id() != null) {
                senderByMessage.putIfAbsent(message.id(), message.senderId());
            }
        }
        for (List<UUID> messageIds : citations(content)) {
            for (UUID messageId : messageIds) {
                UUID sender = senderByMessage.get(messageId);
                if (sender == null || optedOut.contains(sender)) {
                    return false;
                }
            }
        }
        for (SummaryParticipant participant : content.participants()) {
            if (participant.userId() != null && optedOut.contains(participant.userId())) {
                return false;
            }
        }
        return true;
    }

    /**
     * True when the returned summary was produced by the external LLM and at
     * least one message in the window now belongs to an opted-out member. The
     * local engine returns false because it may still use those messages.
     */
    static boolean contextOmitted(
            SummaryGeneration generation,
            SummaryWindow window,
            Set<UUID> optedOut) {
        if (!"LLM".equals(generation.generatedBy()) || optedOut.isEmpty()) {
            return false;
        }
        for (SummaryMessage message : window.messages()) {
            if (message.senderId() != null && optedOut.contains(message.senderId())) {
                return true;
            }
        }
        return false;
    }

    private static List<List<UUID>> citations(SummaryContent content) {
        List<List<UUID>> citations = new java.util.ArrayList<>();
        for (SummaryItem item : content.keyPoints()) {
            citations.add(item.messageIds());
        }
        for (SummaryItem item : content.decisions()) {
            citations.add(item.messageIds());
        }
        for (SummaryActionItem item : content.actionItems()) {
            citations.add(item.messageIds());
        }
        for (SummaryItem item : content.questions()) {
            citations.add(item.messageIds());
        }
        return citations;
    }

    static String contentHash(SummaryWindow window) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateHashField(digest, Integer.toString(window.messages().size()));
            for (SummaryMessage message : window.messages()) {
                updateHashField(digest, message.id() == null ? null : message.id().toString());
                updateHashField(digest, Long.toString(message.seq()));
                updateHashField(digest, message.senderId() == null ? null : message.senderId().toString());
                updateHashField(digest, message.senderName());
                updateHashField(digest, message.text());
                updateHashField(digest, message.createdAt() == null ? null : message.createdAt().toString());
                updateHashField(digest, message.editedAt() == null ? null : message.editedAt().toString());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private static void updateHashField(MessageDigest digest, String value) {
        byte[] bytes = value == null ? null : value.getBytes(StandardCharsets.UTF_8);
        int length = bytes == null ? -1 : bytes.length;
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(length).array());
        if (bytes != null) {
            digest.update(bytes);
        }
    }
}
