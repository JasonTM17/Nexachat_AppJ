package com.nexachat.translate;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.conversation.Conversation;
import com.nexachat.conversation.ConversationMember;
import com.nexachat.conversation.ConversationMemberRepository;
import com.nexachat.conversation.ConversationRepository;
import com.nexachat.message.MessageRepository;
import com.nexachat.observability.ObservabilityMetrics;
import com.nexachat.summary.ConversationAiOptOuts;
import com.nexachat.summary.SummaryConsentRepository;
import com.nexachat.translate.TranslationContextRepository.TargetRow;
import com.nexachat.translate.TranslationGlossaryRepository.GlossaryTerm;
import com.nexachat.translate.dto.GlossaryTermDto;
import com.nexachat.translate.dto.TranslateBatchItem;
import com.nexachat.translate.dto.TranslatePrefsResponse;
import com.nexachat.translate.dto.TranslateResponse;
import com.nexachat.translate.dto.TranslationGlossaryResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Message translation use cases: membership-checked single and batch
 * translation grounded in recent conversation context and the caller's
 * glossary, plus the glossary and auto-translate preference endpoints.
 *
 * <p>Privacy contract: a message reaches an external provider only when the
 * caller is a member with AI consent, the sender has not opted out of AI for
 * the conversation, and the message is plain text that predates neither the
 * member's admission nor encryption. Cache hits never consume rate-limit
 * tokens and never contact a provider.
 */
@Service
@Profile("!test")
public class TranslateService {

    private static final Pattern NO_LETTERS = Pattern.compile("^[^\\p{L}]*$");
    private static final Pattern VIETNAMESE_MARKS = Pattern.compile(
            "[ăâđêôơưĂÂĐÊÔƠƯáàảãạấầẩẫậắằẳẵặéèẻẽẹếềểễệíìỉĩịóòỏõọốồổỗộớờởỡợúùủũụứừửữựýỳỷỹỵ"
                    + "ÁÀẢÃẠẤẦẨẪẬẮẰẲẴẶÉÈẺẼẸẾỀỂỄỆÍÌỈĨỊÓÒỎÕỌỐỒỔỖỘỚỜỞỠỢÚÙỦŨỤỨỪỬỮỰÝỲỶỸỴ]");
    private static final Pattern HANGUL = Pattern.compile("\\p{IsHangul}");
    private static final Pattern CYRILLIC = Pattern.compile("\\p{IsCyrillic}");
    private static final Pattern ARABIC = Pattern.compile("\\p{IsArabic}");
    private static final Pattern THAI = Pattern.compile("\\p{IsThai}");
    private static final Pattern DEVANAGARI = Pattern.compile("\\p{IsDevanagari}");
    private static final Pattern KANA = Pattern.compile("[\\p{IsHiragana}\\p{IsKatakana}]");
    private static final Pattern CJK = Pattern.compile("\\p{IsHan}");

    private final ConversationRepository conversationRepository;
    private final ConversationMemberRepository memberRepository;
    private final MessageRepository messageRepository;
    private final TranslationContextRepository contextRepository;
    private final TranslationGlossaryRepository glossaryRepository;
    private final TranslationCacheRepository cacheRepository;
    private final ConversationTranslatePrefsRepository prefsRepository;
    private final ConversationAiOptOuts aiOptOuts;
    private final SummaryConsentRepository consentRepository;
    private final TranslateRateLimiter rateLimiter;
    private final TranslationPromptBuilder promptBuilder;
    private final TranslationOutputValidator outputValidator;
    private final TranslationProvider provider;
    private final ObservabilityMetrics metrics;
    private final TranslateProperties properties;
    private final Clock clock;

    public TranslateService(
            ConversationRepository conversationRepository,
            ConversationMemberRepository memberRepository,
            MessageRepository messageRepository,
            TranslationContextRepository contextRepository,
            TranslationGlossaryRepository glossaryRepository,
            TranslationCacheRepository cacheRepository,
            ConversationTranslatePrefsRepository prefsRepository,
            ConversationAiOptOuts aiOptOuts,
            SummaryConsentRepository consentRepository,
            TranslateRateLimiter rateLimiter,
            TranslationPromptBuilder promptBuilder,
            TranslationOutputValidator outputValidator,
            TranslationProvider provider,
            ObservabilityMetrics metrics,
            TranslateProperties properties,
            Clock clock) {
        this.conversationRepository = conversationRepository;
        this.memberRepository = memberRepository;
        this.messageRepository = messageRepository;
        this.contextRepository = contextRepository;
        this.glossaryRepository = glossaryRepository;
        this.cacheRepository = cacheRepository;
        this.prefsRepository = prefsRepository;
        this.aiOptOuts = aiOptOuts;
        this.consentRepository = consentRepository;
        this.rateLimiter = rateLimiter;
        this.promptBuilder = promptBuilder;
        this.outputValidator = outputValidator;
        this.provider = provider;
        this.metrics = metrics;
        this.properties = properties;
        this.clock = clock;
    }

    // ------------------------------------------------------------------
    // Single-message translate
    // ------------------------------------------------------------------

    // Not readOnly: the cache write on a miss requires a read-write tx, and
    // Postgres would reject the INSERT inside a read-only transaction.
    @Transactional
    public TranslateResponse translate(UUID userId, UUID messageId, String targetLangCode) {
        TranslationLanguage target = requireTargetLanguage(targetLangCode);
        UUID conversationId = messageRepository.findConversationIdById(messageId)
                .orElseThrow(() -> ApiException.of(ApiErrorCode.MESSAGE_NOT_FOUND,
                        "Message not found"));
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> ApiException.of(ApiErrorCode.CONVERSATION_NOT_FOUND,
                        "Conversation not found"));
        ConversationMember member = requireMember(conversationId, userId);
        refuseE2ee(conversation);

        List<TargetRow> rows = contextRepository.findTargets(conversationId, List.of(messageId));
        TargetRow row = rows.isEmpty() ? null : rows.get(0);
        Set<UUID> optedOut = aiOptOuts.optedOutUserIds(conversationId);
        ApiErrorCode refusal = refusalFor(row, member, optedOut);
        if (refusal != null) {
            metrics.recordTranslateRequest("REFUSED", "none");
            throw ApiException.of(refusal, refusalMessage(refusal));
        }
        List<GlossaryTerm> glossary = glossaryRepository.findTerms(conversationId, userId);
        String glossaryHash = GlossaryHasher.hash(glossary);
        Optional<TranslationCacheRepository.CacheEntry> cached = cacheRepository.find(
                messageId, row.changeVersion(), target.code(), glossaryHash);
        if (cached.isPresent()) {
            metrics.recordTranslateRequest("CACHE", "none");
            return new TranslateResponse(cached.get().translatedText(),
                    cached.get().sourceLang(), target.code(), true, cached.get().model());
        }
        String content = row.toMessage().content();
        Optional<String> shortcut = sameLanguageShortcut(content, target);
        if (shortcut.isPresent()) {
            metrics.recordTranslateRequest("SHORT_CIRCUIT", "none");
            return new TranslateResponse(shortcut.get(), target.code(), target.code(), false, null);
        }
        if (!consentRepository.isConsented(userId)) {
            metrics.recordTranslateRequest("REFUSED", "none");
            throw ApiException.of(ApiErrorCode.AI_CONSENT_REQUIRED,
                    "External AI consent is required to translate messages");
        }
        rateLimiter.consumeOne(userId);
        Generation generation = runProvider(conversationId, member, row.toMessage(),
                target, glossary);
        if (generation == null) {
            metrics.recordTranslateRequest("FAILURE", "none");
            throw ApiException.of(ApiErrorCode.TRANSLATION_UNAVAILABLE,
                    "Translation is temporarily unavailable");
        }
        String text = target.code().equals(generation.output().sourceLang())
                ? content : generation.output().text();
        cacheRepository.save(messageId, row.changeVersion(), target.code(), glossaryHash,
                new TranslationCacheRepository.CacheEntry(text, generation.output().sourceLang(),
                        generation.model(), generation.providerLabel()),
                expiresAt(), properties.getCacheMaxLangsPerMessage());
        metrics.recordTranslateRequest("GENERATE", generation.providerLabel());
        return new TranslateResponse(text, generation.output().sourceLang(), target.code(),
                false, generation.model());
    }

    // ------------------------------------------------------------------
    // Batch translate (auto-translate)
    // ------------------------------------------------------------------

    // Not readOnly: cache writes on misses require a read-write tx.
    @Transactional
    public List<TranslateBatchItem> translateBatch(
            UUID userId, UUID conversationId, List<UUID> messageIds, String targetLangCode) {
        TranslationLanguage target = requireTargetLanguage(targetLangCode);
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> ApiException.of(ApiErrorCode.CONVERSATION_NOT_FOUND,
                        "Conversation not found"));
        ConversationMember member = requireMember(conversationId, userId);
        refuseE2ee(conversation);

        // Distinct ids, request order preserved; callers may repeat ids.
        List<UUID> ids = new ArrayList<>(new LinkedHashSet<>(messageIds));
        if (ids.size() > properties.getBatchMaxMessages()) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                    "At most " + properties.getBatchMaxMessages() + " messages per batch");
        }
        Map<UUID, TargetRow> rows = new LinkedHashMap<>();
        for (TargetRow row : contextRepository.findTargets(conversationId, ids)) {
            rows.put(row.id(), row);
        }
        Set<UUID> optedOut = aiOptOuts.optedOutUserIds(conversationId);
        List<GlossaryTerm> glossary = glossaryRepository.findTerms(conversationId, userId);
        String glossaryHash = GlossaryHasher.hash(glossary);
        Boolean consented = null;
        boolean rateLimited = false;

        List<TranslateBatchItem> items = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            TargetRow row = rows.get(id);
            ApiErrorCode refusal = refusalFor(row, member, optedOut);
            if (refusal != null) {
                metrics.recordTranslateRequest("REFUSED", "none");
                items.add(TranslateBatchItem.failed(id, target.code(), refusal.getCode()));
                continue;
            }
            TranslationMessage message = row.toMessage();
            Optional<TranslationCacheRepository.CacheEntry> cached = cacheRepository.find(
                    id, message.changeVersion(), target.code(), glossaryHash);
            if (cached.isPresent()) {
                metrics.recordTranslateRequest("CACHE", "none");
                items.add(TranslateBatchItem.translated(id, cached.get().translatedText(),
                        cached.get().sourceLang(), target.code(), true, cached.get().model()));
                continue;
            }
            Optional<String> shortcut = sameLanguageShortcut(message.content(), target);
            if (shortcut.isPresent()) {
                metrics.recordTranslateRequest("SHORT_CIRCUIT", "none");
                items.add(TranslateBatchItem.translated(id, shortcut.get(),
                        target.code(), target.code(), false, null));
                continue;
            }
            if (consented == null) {
                consented = consentRepository.isConsented(userId);
            }
            if (!consented) {
                items.add(TranslateBatchItem.failed(id, target.code(),
                        ApiErrorCode.AI_CONSENT_REQUIRED.getCode()));
                continue;
            }
            if (rateLimited) {
                items.add(TranslateBatchItem.failed(id, target.code(),
                        ApiErrorCode.RATE_LIMITED.getCode()));
                continue;
            }
            if (!rateLimiter.tryConsume(userId, 1)) {
                rateLimited = true;
                items.add(TranslateBatchItem.failed(id, target.code(),
                        ApiErrorCode.RATE_LIMITED.getCode()));
                continue;
            }
            Generation generation = runProvider(conversationId, member, message, target, glossary);
            if (generation == null) {
                metrics.recordTranslateRequest("FAILURE", "none");
                items.add(TranslateBatchItem.failed(id, target.code(),
                        ApiErrorCode.TRANSLATION_UNAVAILABLE.getCode()));
                continue;
            }
            String text = target.code().equals(generation.output().sourceLang())
                    ? message.content() : generation.output().text();
            cacheRepository.save(id, message.changeVersion(), target.code(), glossaryHash,
                    new TranslationCacheRepository.CacheEntry(text, generation.output().sourceLang(),
                            generation.model(), generation.providerLabel()),
                    expiresAt(), properties.getCacheMaxLangsPerMessage());
            metrics.recordTranslateRequest("GENERATE", generation.providerLabel());
            items.add(TranslateBatchItem.translated(id, text, generation.output().sourceLang(),
                    target.code(), false, generation.model()));
        }
        return items;
    }

    // ------------------------------------------------------------------
    // Glossary
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public TranslationGlossaryResponse getGlossary(UUID userId, UUID conversationId) {
        requireMemberConversation(conversationId, userId);
        List<GlossaryTerm> terms = glossaryRepository.findTerms(conversationId, userId);
        return new TranslationGlossaryResponse(conversationId,
                terms.stream().map(term -> new GlossaryTermDto(term.term(), term.replacement())).toList());
    }

    /**
     * Replaces the caller's glossary atomically. Terms are trimmed, blank
     * terms rejected, duplicates folded by case-insensitive source (last
     * wins) and caps enforced by the request DTO plus the DB columns.
     */
    @Transactional
    public TranslationGlossaryResponse replaceGlossary(
            UUID userId, UUID conversationId, List<GlossaryTermDto> requested) {
        requireMemberConversation(conversationId, userId);
        List<GlossaryTermDto> safe = requested == null ? List.of() : requested;
        if (safe.size() > properties.getGlossaryMaxTerms()) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                    "At most " + properties.getGlossaryMaxTerms() + " glossary terms");
        }
        Map<String, GlossaryTerm> deduped = new LinkedHashMap<>();
        for (GlossaryTermDto dto : safe) {
            if (dto == null || dto.term() == null || dto.term().isBlank()) {
                throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "Glossary terms must not be blank");
            }
            String term = dto.term().trim();
            String replacement = dto.replacement() == null || dto.replacement().isBlank()
                    ? null : dto.replacement().trim();
            if (term.codePointCount(0, term.length()) > properties.getGlossaryMaxTermChars()
                    || (replacement != null
                            && replacement.codePointCount(0, replacement.length())
                                    > properties.getGlossaryMaxTermChars())) {
                throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                        "Glossary terms are limited to " + properties.getGlossaryMaxTermChars() + " characters");
            }
            deduped.put(term.toLowerCase(Locale.ROOT), new GlossaryTerm(term, replacement));
        }
        List<GlossaryTerm> terms = List.copyOf(deduped.values());
        glossaryRepository.replaceTerms(conversationId, userId, terms);
        return new TranslationGlossaryResponse(conversationId,
                terms.stream().map(term -> new GlossaryTermDto(term.term(), term.replacement())).toList());
    }

    // ------------------------------------------------------------------
    // Auto-translate preferences
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public TranslatePrefsResponse getPrefs(UUID userId, UUID conversationId) {
        requireMemberConversation(conversationId, userId);
        return prefsRepository.find(conversationId, userId)
                .map(prefs -> new TranslatePrefsResponse(conversationId,
                        prefs.autoTranslate(), prefs.targetLang()))
                .orElse(new TranslatePrefsResponse(conversationId, false, null));
    }

    @Transactional
    public TranslatePrefsResponse updatePrefs(
            UUID userId, UUID conversationId, Boolean autoTranslate, String targetLangCode) {
        requireMemberConversation(conversationId, userId);
        String targetLang = null;
        if (targetLangCode != null && !targetLangCode.isBlank()) {
            targetLang = requireTargetLanguage(targetLangCode).code();
        }
        prefsRepository.upsert(conversationId, userId,
                Boolean.TRUE.equals(autoTranslate), targetLang);
        return new TranslatePrefsResponse(conversationId, Boolean.TRUE.equals(autoTranslate), targetLang);
    }

    // ------------------------------------------------------------------
    // Generation pipeline
    // ------------------------------------------------------------------

    /** A validated provider output plus the provenance cached beside it. */
    private record Generation(TranslationOutput output, String model, String providerLabel) {
    }

    private Generation runProvider(
            UUID conversationId, ConversationMember member, TranslationMessage message,
            TranslationLanguage target, List<GlossaryTerm> glossary) {
        List<TranslationMessage> context = contextRepository.findContextBefore(
                conversationId, message.seq(), member.getJoinedAt(),
                properties.getContextMessages(), properties.getContextMessageMaxChars());
        TranslationPrompt prompt = promptBuilder.build(
                message, target, context, glossary, properties.getContextMaxChars());
        TranslationProvider.ProviderCompletion completion;
        try {
            completion = provider.translate(prompt);
        } catch (TranslationUnavailableException exception) {
            return null;
        }
        TranslationOutput output = outputValidator.validate(
                completion.content(), prompt, message.content(), target);
        if (output == null) {
            return null;
        }
        return new Generation(output, completion.model(), completion.provider());
    }

    private Instant expiresAt() {
        return clock.instant().plus(properties.getCacheTtlHours(), ChronoUnit.HOURS);
    }

    // ------------------------------------------------------------------
    // Guards and helpers
    // ------------------------------------------------------------------

    private ConversationMember requireMember(UUID conversationId, UUID userId) {
        return memberRepository.findByConversation_IdAndUser_Id(conversationId, userId)
                .orElseThrow(() -> ApiException.of(ApiErrorCode.NOT_MEMBER,
                        "Conversation membership required"));
    }

    private void requireMemberConversation(UUID conversationId, UUID userId) {
        if (!conversationRepository.existsById(conversationId)) {
            throw ApiException.of(ApiErrorCode.CONVERSATION_NOT_FOUND, "Conversation not found");
        }
        requireMember(conversationId, userId);
    }

    private static void refuseE2ee(Conversation conversation) {
        if (conversation.isE2eeEnabled()) {
            throw ApiException.of(ApiErrorCode.E2EE_AI_UNAVAILABLE,
                    "AI features are unavailable in encrypted conversations");
        }
    }

    /**
     * The typed refusal for one message, in probe-safe order: rows a member
     * cannot see (missing, or sent before their admission) are
     * {@code MESSAGE_NOT_FOUND}; deleted, encrypted, non-text and opted-out
     * rows get their own codes.
     */
    private ApiErrorCode refusalFor(TargetRow row, ConversationMember member, Set<UUID> optedOut) {
        if (row == null || row.createdAt().isBefore(member.getJoinedAt())) {
            return ApiErrorCode.MESSAGE_NOT_FOUND;
        }
        if (row.deletedAt() != null) {
            return ApiErrorCode.MESSAGE_DELETED;
        }
        if (row.encryption() != null) {
            return ApiErrorCode.E2EE_AI_UNAVAILABLE;
        }
        if (!"TEXT".equals(row.type()) || row.content() == null || row.content().isBlank()) {
            return ApiErrorCode.TRANSLATION_UNSUPPORTED;
        }
        if (optedOut != null && optedOut.contains(row.senderId())) {
            return ApiErrorCode.AI_OPTED_OUT;
        }
        return null;
    }

    private static String refusalMessage(ApiErrorCode code) {
        return switch (code) {
            case MESSAGE_NOT_FOUND -> "Message not found";
            case MESSAGE_DELETED -> "Message was deleted";
            case E2EE_AI_UNAVAILABLE -> "Encrypted messages cannot be translated";
            case TRANSLATION_UNSUPPORTED -> "Only text messages can be translated";
            case AI_OPTED_OUT -> "A participant opted out of AI processing";
            default -> code.getCode();
        };
    }

    private TranslationLanguage requireTargetLanguage(String code) {
        return TranslationLanguage.of(code)
                .orElseThrow(() -> ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                        "Unsupported target language"));
    }

    /**
     * Cheap deterministic cases that never need a provider: text without
     * letters is identical in every language, and text already carrying the
     * target's distinctive script needs no translation. Returns the text to
     * serve when confident.
     */
    private static Optional<String> sameLanguageShortcut(String content, TranslationLanguage target) {
        if (NO_LETTERS.matcher(content).matches() || looksLikeLanguage(content, target.code())) {
            return Optional.of(content);
        }
        return Optional.empty();
    }

    /**
     * Script-signature detection: true only when the text carries a script
     * that is unambiguous for the target language. Latin-script targets and
     * mixed/ambiguous scripts return false — the provider's detection stays
     * authoritative there.
     */
    static boolean looksLikeLanguage(String text, String code) {
        return switch (code) {
            case "vi" -> VIETNAMESE_MARKS.matcher(text).find();
            case "ko" -> HANGUL.matcher(text).find();
            case "ru" -> CYRILLIC.matcher(text).find();
            case "ar" -> ARABIC.matcher(text).find();
            case "th" -> THAI.matcher(text).find();
            case "hi" -> DEVANAGARI.matcher(text).find();
            case "ja" -> KANA.matcher(text).find();
            case "zh" -> CJK.matcher(text).find() && !KANA.matcher(text).find();
            default -> false;
        };
    }
}
