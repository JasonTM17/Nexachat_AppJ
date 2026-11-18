package com.nexachat.summary;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.summary.llm.CircuitBreaker;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
@Profile("!test")
public class SummaryService {

    private static final int MIN_MESSAGES = 5;
    /**
     * The default wait bound is the sum of the default per-tier budgets
     * (cloud 30 s + local server 60 s + cloud fallback 30 s): one generation
     * can spend every tier sequentially, so a waiter that has waited this
     * long knows its winner will not finish either.
     */
    private static final long DEFAULT_SINGLE_FLIGHT_WAIT_MS = 120_000;
    /**
     * How many times a waiter re-enters the flight map before it answers on
     * its own. Each round is one leadership attempt plus one wait bounded by
     * what remains of the shared wait deadline, so waiters follow a failing
     * or slow leader through a bounded number of replacements instead of all
     * calling the provider at once. This cap is only a secondary bound: the
     * end-to-end wait deadline usually ends re-coalescing first.
     */
    private static final int SINGLE_FLIGHT_ROUNDS = 3;
    /**
     * Fallback reasons that record a provider failure. Only these make a stored
     * local row replayable to a consented request prepared for the external
     * tier; consent or policy downgrades and routing downgrades must not pin
     * a stale local answer to them.
     */
    private static final Set<SummaryFallbackReason> PROVIDER_FAILURE_FALLBACKS = Set.of(
            SummaryFallbackReason.LLM_TIMEOUT,
            SummaryFallbackReason.LLM_HTTP_ERROR,
            SummaryFallbackReason.LLM_BAD_OUTPUT);

    private final SummaryRateLimiter rateLimiter;
    private final SummaryWindowService windowService;
    private final SummaryProviderSelector providerSelector;
    private final SummaryCacheRepository cacheRepository;
    private final ConversationAiOptOuts aiOptOuts;
    /**
     * Consent collaborators. {@code consentRepository} is the cheap unlocked
     * snapshot reader used for the pre-generate lane check; it is null only
     * when the service is constructed directly without it (unit tests that do
     * not exercise consent). {@code consentRecheck} is the post-generation
     * response-lock re-check; {@link SummaryConsentRecheck#noop()} disables it
     * explicitly in tests — production always wires the real bean.
     */
    private final SummaryConsentRepository consentRepository;
    private final SummaryConsentRecheck consentRecheck;
    /**
     * In-process single-flight sessions, keyed by the cache tuple. The server
     * runs a single node, so this map spans every summary request the node
     * serves. A waiter parks on the current leader's future for at most what
     * remains of its one end-to-end wait budget before it stops re-coalescing
     * and answers on its own.
     */
    private final ConcurrentHashMap<String, CompletableFuture<Void>> singleFlights = new ConcurrentHashMap<>();
    private final Clock clock;
    private final long singleFlightWaitMs;

    public SummaryService(
            SummaryRateLimiter rateLimiter,
            SummaryWindowService windowService,
            SummaryProviderSelector providerSelector,
            SummaryCacheRepository cacheRepository) {
        this(rateLimiter, windowService, providerSelector, cacheRepository, ConversationAiOptOuts.none());
    }

    public SummaryService(
            SummaryRateLimiter rateLimiter,
            SummaryWindowService windowService,
            SummaryProviderSelector providerSelector,
            SummaryCacheRepository cacheRepository,
            ConversationAiOptOuts aiOptOuts) {
        this(rateLimiter, windowService, providerSelector, cacheRepository, aiOptOuts,
                DEFAULT_SINGLE_FLIGHT_WAIT_MS);
    }

    public SummaryService(
            SummaryRateLimiter rateLimiter,
            SummaryWindowService windowService,
            SummaryProviderSelector providerSelector,
            SummaryCacheRepository cacheRepository,
            ConversationAiOptOuts aiOptOuts,
            long singleFlightWaitMs) {
        this(rateLimiter, windowService, providerSelector, cacheRepository, aiOptOuts,
                Clock.systemUTC(), singleFlightWaitMs);
    }

    public SummaryService(
            SummaryRateLimiter rateLimiter,
            SummaryWindowService windowService,
            SummaryProviderSelector providerSelector,
            SummaryCacheRepository cacheRepository,
            ConversationAiOptOuts aiOptOuts,
            Clock clock,
            long singleFlightWaitMs) {
        this(rateLimiter, windowService, providerSelector, cacheRepository, aiOptOuts,
                clock, singleFlightWaitMs, null, SummaryConsentRecheck.noop());
    }

    public SummaryService(
            SummaryRateLimiter rateLimiter,
            SummaryWindowService windowService,
            SummaryProviderSelector providerSelector,
            SummaryCacheRepository cacheRepository,
            ConversationAiOptOuts aiOptOuts,
            Clock clock,
            long singleFlightWaitMs,
            SummaryConsentRepository consentRepository,
            SummaryConsentRecheck consentRecheck) {
        this.rateLimiter = rateLimiter;
        this.windowService = windowService;
        this.providerSelector = providerSelector;
        this.cacheRepository = cacheRepository;
        this.aiOptOuts = aiOptOuts;
        this.clock = clock;
        this.singleFlightWaitMs = singleFlightWaitMs;
        this.consentRepository = consentRepository;
        this.consentRecheck = consentRecheck;
    }

    @Autowired
    public SummaryService(
            SummaryRateLimiter rateLimiter,
            SummaryWindowService windowService,
            SummaryProviderSelector providerSelector,
            SummaryCacheRepository cacheRepository,
            ConversationAiOptOuts aiOptOuts,
            Clock clock,
            @Value("${nexachat.summary.llm.timeout-ms:30000}") long llmTimeoutMs,
            @Value("${nexachat.summary.llm.local-timeout-ms:60000}") long localLlmTimeoutMs,
            @Value("${nexachat.summary.llm-fallback.timeout-ms:30000}") long llmFallbackTimeoutMs,
            SummaryConsentRepository consentRepository,
            SummaryConsentRecheck consentRecheck) {
        // One generation can spend the primary, fallback and local-server budgets
        // sequentially, so the wait bound is their sum, not one tier's timeout.
        this(rateLimiter, windowService, providerSelector, cacheRepository, aiOptOuts, clock,
                llmTimeoutMs + localLlmTimeoutMs + llmFallbackTimeoutMs, consentRepository, consentRecheck);
    }

    public SummaryResponse summarize(UUID conversationId, UUID userId, SummaryRequest request) {
        // Reject a garbage zone before it costs a rate-limit token.
        SummaryPeriods.resolveZone(request.timeZone());
        rateLimiter.consume(userId);
        SummaryReadContext readContext = windowService.load(conversationId, userId, request);
        SummaryWindow window = readContext.window();
        if (window.messageCount() < MIN_MESSAGES) {
            // Never widen the range to make up the numbers: report what the chosen scope holds.
            throw new SummaryTooFewMessagesException(
                    request.scope(), window.messageCount(), MIN_MESSAGES, readContext.period());
        }

        Set<UUID> optedOut = aiOptOuts.optedOutUserIds(conversationId);
        PreparedSummaryProvider prepared = providerSelector.prepare(readContext.aiSummaryConsent(), window, request.refresh());
        if (request.refresh()) {
            // An explicit refresh always regenerates: it neither replays nor waits on a flight.
            GeneratePreparation gen = preparedForGenerate(userId, readContext, prepared, window, true);
            SummaryGeneration generated = providerSelector.generate(gen.prepared(), window, request.language());
            return generateAndStore(userId, conversationId, window, request, readContext, gen, generated, optedOut);
        }
        String flightKey = flightKey(conversationId, window, request, readContext.period(), prepared,
                readContext.consentEpoch());
        // Single-flight: one provider call per cache-cold key. Every request that
        // arrives while the key is being filled waits, then replays the winner.
        // A waiter whose leader failed, timed out or was rejected re-enters the
        // flight map for a bounded number of rounds - one waiter becomes the new
        // leader - and only answers on its own after the last round. All waits
        // share one end-to-end deadline measured on the monotonic clock: waiting
        // the whole bound per round would outlive the client's own read timeout.
        long waitDeadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(singleFlightWaitMs);
        for (int round = 0; round < SINGLE_FLIGHT_ROUNDS; round++) {
            SummaryResponse replayed = replayIfCached(conversationId, window, request, readContext, prepared, optedOut);
            if (replayed != null) {
                return replayed;
            }
            boolean[] leader = {false};
            CompletableFuture<Void> flight = singleFlights.computeIfAbsent(flightKey, key -> {
                leader[0] = true;
                return new CompletableFuture<>();
            });
            if (leader[0]) {
                try {
                    // A previous flight may have filled this key just before this
                    // leader registered: re-check before spending a provider call.
                    SummaryResponse winner = replayIfCached(conversationId, window, request, readContext, prepared, optedOut);
                    if (winner == null) {
                        GeneratePreparation gen = preparedForGenerate(
                                userId, readContext, prepared, window, false);
                        SummaryGeneration generated = providerSelector.generate(gen.prepared(), window, request.language());
                        winner = generateAndStore(userId, conversationId, window, request, readContext, gen,
                                generated, optedOut);
                    }
                    return winner;
                } finally {
                    // The entry leaves the map before the future is completed, so
                    // a waiter woken by the completion either replays this
                    // leader's row or registers a new flight. The future is
                    // completed even when the leader dies with an error, so
                    // waiters never sit out the whole bound after its death.
                    singleFlights.remove(flightKey, flight);
                    if (!flight.isDone()) {
                        flight.complete(null);
                    }
                }
            }
            if (!awaitWinner(flight, waitDeadlineNanos)) {
                // The shared wait budget is spent: stop re-coalescing and
                // answer through the final probe and own generation below.
                break;
            }
        }
        // The last wait may have ended the moment the leader finished: probe once
        // more before spending a provider call of our own.
        SummaryResponse finalReplay = replayIfCached(conversationId, window, request, readContext, prepared, optedOut);
        if (finalReplay != null) {
            return finalReplay;
        }
        // No usable winner after the bounded rounds or once the shared wait
        // deadline ran out: answer on our own.
        GeneratePreparation gen = preparedForGenerate(userId, readContext, prepared, window, false);
        SummaryGeneration generated = providerSelector.generate(gen.prepared(), window, request.language());
        return generateAndStore(userId, conversationId, window, request, readContext, gen, generated, optedOut);
    }

    /** The provider preparation plus the consent snapshot it was authorized under. */
    private record GeneratePreparation(PreparedSummaryProvider prepared,
            SummaryConsentRepository.ConsentSnapshot snapshot) {
    }

    /**
     * One unlocked consent look before spending an external call: a revoke
     * committed after the window read must not transmit conversation text to
     * the provider. A moved snapshot re-prepares on its own truth, so the
     * request degrades to whatever tier the current consent allows instead of
     * failing — the same soft degradation the ask lane uses. The returned
     * snapshot is what {@link SummaryConsentRecheck} compares against after
     * generation, so a revoke-then-regrant is judged on the snapshot that
     * actually authorized the call. Local-tier preparations skip the look
     * entirely: they never transmit anyway.
     */
    private GeneratePreparation preparedForGenerate(UUID userId, SummaryReadContext readContext,
            PreparedSummaryProvider prepared, SummaryWindow window, boolean refresh) {
        SummaryConsentRepository.ConsentSnapshot atRead = new SummaryConsentRepository.ConsentSnapshot(
                readContext.aiSummaryConsent(), readContext.consentEpoch());
        if (consentRepository == null || !"LLM".equals(prepared.cacheProvider())
                || readContext.consentEpoch() < 0) {
            return new GeneratePreparation(prepared, atRead);
        }
        SummaryConsentRepository.ConsentSnapshot current = consentRepository.snapshot(userId);
        if (current.epoch() < 0 || atRead.equals(current)) {
            return new GeneratePreparation(prepared, atRead);
        }
        return new GeneratePreparation(
                providerSelector.prepare(current.consented(), window, refresh), current);
    }

    /**
     * One round of waiting for the leader, capped by the shared end-to-end
     * deadline: the wait lasts at most until {@code waitDeadlineNanos},
     * however much of the budget is left. Returns false once that deadline is
     * reached, which ends re-coalescing; while budget remains, neither a
     * timeout nor a failed leader decides the answer, so the caller re-probes
     * the cache and re-enters the flight map. A request cancelled mid-wait
     * never spends a provider call: its own request thread failed, so
     * generating for nobody is waste.
     */
    private boolean awaitWinner(CompletableFuture<Void> flight, long waitDeadlineNanos) {
        long remainingMs = TimeUnit.NANOSECONDS.toMillis(waitDeadlineNanos - System.nanoTime());
        if (remainingMs <= 0) {
            return false;
        }
        try {
            flight.get(remainingMs, TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException | ExecutionException ex) {
            // No usable winner in time, or the winner failed: re-coalesce only
            // while the shared deadline still has room.
            return System.nanoTime() < waitDeadlineNanos;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw ApiException.of(ApiErrorCode.INTERNAL_ERROR, "The summary request was cancelled");
        }
    }

    /**
     * Cache identity of a request: the conversation, the window span, the content
     * hash, the cache tier and the language. Two requests share a flight only
     * when they would read and write the same cache row.
     */
    private static String flightKey(
            UUID conversationId,
            SummaryWindow window,
            SummaryRequest request,
            SummaryPeriod period,
            PreparedSummaryProvider prepared,
            long consentEpoch) {
        long lookupEpoch = "LLM".equals(prepared.cacheProvider()) ? consentEpoch : -1L;
        return conversationId + "|" + window.fromSeq() + "|" + window.toSeq() + "|"
                + contentHash(window, request, period, lookupEpoch) + "|"
                + prepared.cacheProvider() + "|" + request.language().name();
    }

    /**
     * Serves the cached response for the engine this request would use, or null
     * when the key must be generated. A row that fails the playback guards is
     * dropped instead of served. A request prepared for the external engine
     * also accepts a stored local row whose recorded reason was a provider
     * failure, so the failing tier is not retried on every repeat; rows stored
     * under a consent, policy or routing downgrade stay invisible to it.
     */
    private SummaryResponse replayIfCached(
            UUID conversationId,
            SummaryWindow window,
            SummaryRequest request,
            SummaryReadContext readContext,
            PreparedSummaryProvider prepared,
            Set<UUID> optedOut) {
        long consentEpoch = "LLM".equals(prepared.cacheProvider()) ? readContext.consentEpoch() : -1L;
        // The key names the scope, the resolved day and zone, every included message and, for
        // external AI, the consent generation, so a result is never replayed under other access.
        String contentHash = contentHash(window, request, readContext.period(), consentEpoch);
        Optional<SummaryCacheEntry> cached = cacheRepository.findValid(conversationId, window.fromSeq(),
                window.toSeq(), contentHash, prepared.cacheProvider(), request.language());
        if (cached.isPresent()) {
            SummaryCacheEntry entry = cached.get();
            if (citationsWithinWindow(entry.payload().content(), window)
                    && replayAllowed(entry, window, optedOut)) {
                return cachedResponse(entry, window, request, readContext, prepared, optedOut);
            }
            // A previous external summary cites a message whose author has
            // since opted out. It must never be replayed; remove it and let
            // the caller regenerate the cache entry below.
            cacheRepository.delete(conversationId, window.fromSeq(), window.toSeq(),
                    contentHash, prepared.cacheProvider(), request.language());
        }
        if (!"LLM".equals(prepared.cacheProvider())) {
            return null;
        }
        // The same window can hold a local fallback row the external tier stored
        // after a provider failure. It lives under the local engine's own key.
        String fallbackHash = contentHash(window, request, readContext.period(), -1L);
        Optional<SummaryCacheEntry> fallback = cacheRepository.findValid(conversationId, window.fromSeq(),
                window.toSeq(), fallbackHash, "LOCAL", request.language());
        if (fallback.isEmpty()) {
            return null;
        }
        SummaryCacheEntry entry = fallback.get();
        SummaryFallbackReason fallbackReason = fallbackReasonOf(entry.payload().fallbackReason());
        if (fallbackReason == null || !PROVIDER_FAILURE_FALLBACKS.contains(fallbackReason)) {
            return null;
        }
        // The replay lasts only as long as the circuit breaker keeps the failing
        // external tier closed. After that window the breaker itself retries
        // the external provider, so replaying the local answer any longer would
        // pin it past that recovery point for the whole 24 h cache lifetime.
        if (!clock.instant().isBefore(entry.createdAt().plus(CircuitBreaker.OPEN_DURATION))) {
            return null;
        }
        if (!citationsWithinWindow(entry.payload().content(), window) || !replayAllowed(entry, window, optedOut)) {
            // A fallback row that can no longer be replayed is dropped, like the
            // primary-key path, so it is not re-probed on every request.
            cacheRepository.delete(conversationId, window.fromSeq(), window.toSeq(),
                    fallbackHash, "LOCAL", request.language());
            return null;
        }
        return cachedResponse(entry, window, request, readContext, prepared, optedOut);
    }

    private static SummaryFallbackReason fallbackReasonOf(String raw) {
        try {
            return raw == null ? null : SummaryFallbackReason.valueOf(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private SummaryResponse cachedResponse(
            SummaryCacheEntry entry,
            SummaryWindow window,
            SummaryRequest request,
            SummaryReadContext readContext,
            PreparedSummaryProvider prepared,
            Set<UUID> optedOut) {
        SummaryGeneration generation = forRequest(entry.payload().toGeneration(), prepared, null);
        return SummaryResponse.create(entry.id(), window, generation, true,
                contextOmitted(generation, window, optedOut), entry.createdAt(), request, readContext.period());
    }

    /**
     * Post-generation consent re-check, mirroring the ask lane's
     * {@code AskResponseWriter}: {@link SummaryConsentRecheck} takes the
     * {@code FOR SHARE} row lock and re-reads the snapshot on the external
     * lane only. A consent revoke uses a conflicting write lock on the same
     * row, so it either commits before this lock (the snapshot differs and
     * the generated summary is dropped — never stored, never served) or
     * after it (the epoch bump already keeps any stored row out of LLM-tier
     * replay through the content hash, so the cache write needs no lock of
     * its own, exactly as the ask lane writes its cache outside the response
     * lock). The check is one locked SELECT: no extra LLM call.
     */
    private SummaryResponse generateAndStore(
            UUID userId,
            UUID conversationId,
            SummaryWindow window,
            SummaryRequest request,
            SummaryReadContext readContext,
            GeneratePreparation preparation,
            SummaryGeneration generated,
            Set<UUID> optedOut) {
        consentRecheck.verifyUnchanged(userId, generated.generatedBy(), preparation.snapshot());
        return storeGenerated(userId, conversationId, window, request, readContext, preparation.prepared(),
                generated, optedOut, preparation.snapshot().epoch());
    }

    /**
     * Stores the passed generation under the engine that actually produced it
     * and reports the response, including the concurrent-fill guards on the
     * save path.
     */
    private SummaryResponse storeGenerated(
            UUID userId,
            UUID conversationId,
            SummaryWindow window,
            SummaryRequest request,
            SummaryReadContext readContext,
            PreparedSummaryProvider prepared,
            SummaryGeneration generated,
            Set<UUID> optedOut,
            long generationConsentEpoch) {
        // The row is stored under the engine that actually answered, so its key carries the
        // consent generation only when that engine was the external one. The epoch is the
        // one the generation was authorized under, which can be newer than the window
        // read's when consent moved before the provider call.
        String storeHash = contentHash(window, request, readContext.period(),
                "LLM".equals(generated.generatedBy()) ? generationConsentEpoch : -1L);
        SummaryCacheEntry entry;
        boolean cached;
        if (request.refresh()) {
            entry = cacheRepository.replace(userId, conversationId, window, storeHash, generated.generatedBy(),
                    request.language(), generated);
            cached = false;
        } else {
            SummaryCacheRepository.SavedSummary saved = cacheRepository.saveIfAbsent(
                    userId, conversationId, window, storeHash, generated.generatedBy(), request.language(),
                    generated);
            entry = saved.entry();
            cached = !saved.inserted();
            if (cached && !(citationsWithinWindow(entry.payload().content(), window)
                    && replayAllowed(entry, window, optedOut))) {
                // A concurrent fill that started before the opt-out won the
                // insert. It must not be replayed: drop it and store this
                // request's own generation under the same cache key.
                cacheRepository.delete(conversationId, entry.fromSeq(), entry.toSeq(),
                        entry.contentHash(), entry.cacheProvider(), entry.requestedLanguage());
                entry = cacheRepository.replace(userId, conversationId, window, storeHash,
                        generated.generatedBy(), request.language(), generated);
                cached = false;
            }
        }
        // The stored row is this request's own generation exactly when it won
        // the save; when a concurrent request won, the response must describe
        // the winner's row, not this request's own labels.
        SummaryGeneration responseGeneration = forRequest(
                entry.payload().toGeneration(), prepared, cached ? null : generated);
        return SummaryResponse.create(entry.id(), window, responseGeneration, cached,
                contextOmitted(responseGeneration, window, optedOut), entry.createdAt(),
                request, readContext.period());
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
            return new SummaryGeneration(cachedGeneration.content(), cachedGeneration.language(), "LOCAL",
                    cachedGeneration.model(), generatedThisRequest.fallbackReason(),
                    cachedGeneration.provider());
        }
        if ("LOCAL".equals(cachedGeneration.generatedBy())) {
            // The stored row was produced by the local engine, either as a provider
            // fallback or by a concurrent fill: report the recorded engine, model
            // and fallback, not the external tier this request was prepared for.
            return new SummaryGeneration(cachedGeneration.content(), cachedGeneration.language(), "LOCAL",
                    cachedGeneration.model(), cachedGeneration.fallbackReason(), cachedGeneration.provider());
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

    /** True when every citation and listed participant refers to a message in the live window. */
    static boolean citationsWithinWindow(SummaryContent content, SummaryWindow window) {
        Set<UUID> ids = new java.util.HashSet<>();
        Set<UUID> senders = new java.util.HashSet<>();
        for (SummaryMessage message : window.messages()) {
            ids.add(message.id());
            senders.add(message.senderId());
        }
        for (List<UUID> messageIds : citations(content)) {
            if (messageIds == null || messageIds.isEmpty() || !ids.containsAll(messageIds)) {
                return false;
            }
        }
        for (SummaryParticipant participant : content.participants()) {
            if (!senders.contains(participant.userId())) {
                return false;
            }
        }
        return true;
    }

    static String contentHash(SummaryWindow window) {
        return contentHash(window, null, null, -1L);
    }

    static String contentHash(SummaryWindow window, SummaryRequest request, SummaryPeriod period, long consentEpoch) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            if (request != null) {
                updateHashField(digest, request.scope().name());
                updateHashField(digest, request.scope() == SummaryScope.LAST_N && request.limit() != null
                        ? request.limit().toString() : null);
                updateHashField(digest, period == null ? null : period.date().toString());
                updateHashField(digest, period == null ? null : period.timeZone());
                updateHashField(digest, Long.toString(consentEpoch));
            }
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
