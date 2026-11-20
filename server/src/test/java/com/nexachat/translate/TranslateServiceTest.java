package com.nexachat.translate;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.nexachat.summary.llm.Redactor;
import com.nexachat.translate.TranslationContextRepository.TargetRow;
import com.nexachat.translate.TranslationGlossaryRepository.GlossaryTerm;
import com.nexachat.translate.dto.GlossaryTermDto;
import com.nexachat.translate.dto.TranslateBatchItem;
import com.nexachat.translate.dto.TranslateResponse;
import com.nexachat.translate.dto.TranslationGlossaryResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TranslateServiceTest {

    private static final Instant JOINED_AT = Instant.parse("2026-11-01T00:00:00Z");

    private final ConversationRepository conversationRepository = mock(ConversationRepository.class);
    private final ConversationMemberRepository memberRepository = mock(ConversationMemberRepository.class);
    private final MessageRepository messageRepository = mock(MessageRepository.class);
    private final TranslationContextRepository contextRepository = mock(TranslationContextRepository.class);
    private final TranslationGlossaryRepository glossaryRepository = mock(TranslationGlossaryRepository.class);
    private final TranslationCacheRepository cacheRepository = mock(TranslationCacheRepository.class);
    private final ConversationTranslatePrefsRepository prefsRepository =
            mock(ConversationTranslatePrefsRepository.class);
    private final ConversationAiOptOuts optOuts = mock(ConversationAiOptOuts.class);
    private final SummaryConsentRepository consentRepository = mock(SummaryConsentRepository.class);
    private final TranslateRateLimiter rateLimiter = mock(TranslateRateLimiter.class);
    private final TranslationPromptBuilder promptBuilder =
            new TranslationPromptBuilder(new ObjectMapper(), new Redactor());
    private final TranslationOutputValidator outputValidator =
            new TranslationOutputValidator(new ObjectMapper());
    private final TranslationProvider provider = mock(TranslationProvider.class);
    private final ObservabilityMetrics metrics = mock(ObservabilityMetrics.class);
    private final TranslateProperties properties = new TranslateProperties();
    private final AtomicReference<Instant> clockNow =
            new AtomicReference<>(Instant.parse("2026-11-16T10:00:00Z"));
    private final Clock clock = new Clock() {
        @Override public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override public Instant instant() {
            return clockNow.get();
        }
    };

    private final TranslateService service = new TranslateService(
            conversationRepository, memberRepository, messageRepository, contextRepository,
            glossaryRepository, cacheRepository, prefsRepository, optOuts, consentRepository,
            rateLimiter, promptBuilder, outputValidator, provider, metrics, properties, clock);

    private UUID userId;
    private UUID senderId;
    private UUID conversationId;
    private UUID messageId;
    private Conversation conversation;
    private ConversationMember member;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        senderId = UUID.randomUUID();
        conversationId = UUID.randomUUID();
        messageId = UUID.randomUUID();

        conversation = mock(Conversation.class);
        lenient().when(conversation.isE2eeEnabled()).thenReturn(false);
        member = mock(ConversationMember.class);
        lenient().when(member.getJoinedAt()).thenReturn(JOINED_AT);

        lenient().when(messageRepository.findConversationIdById(messageId))
                .thenReturn(Optional.of(conversationId));
        lenient().when(conversationRepository.findById(conversationId))
                .thenReturn(Optional.of(conversation));
        lenient().when(conversationRepository.existsById(conversationId)).thenReturn(true);
        lenient().when(memberRepository.findByConversation_IdAndUser_Id(conversationId, userId))
                .thenReturn(Optional.of(member));
        lenient().when(optOuts.optedOutUserIds(conversationId)).thenReturn(Set.of());
        lenient().when(consentRepository.isConsented(userId)).thenReturn(true);
        lenient().when(glossaryRepository.findTerms(conversationId, userId)).thenReturn(List.of());
        lenient().when(cacheRepository.find(any(), anyLong(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        lenient().when(contextRepository.findContextBefore(any(), any(), anyLong(), any(), anyInt(), anyInt()))
                .thenReturn(List.of());
    }

    private TargetRow row(String content) {
        return new TargetRow(messageId, 42L, senderId, "Lan", content, 0L,
                Instant.parse("2026-11-16T09:00:00Z"), "TEXT", null, null, false);
    }

    private void stubTarget(TargetRow row) {
        when(contextRepository.findTargets(conversationId, List.of(messageId)))
                .thenReturn(row == null ? List.of() : List.of(row));
    }

    private TranslationProvider.ProviderCompletion completion(String text, String sourceLang) {
        return new TranslationProvider.ProviderCompletion(
                "{\"text\":\"" + text.replace("\"", "\\\"") + "\",\"source_lang\":\""
                        + sourceLang + "\"}", "gemini-flash", "cloud");
    }

    // ------------------------------------------------------------- authz

    @Test
    void nonMemberIsRejected() {
        when(memberRepository.findByConversation_IdAndUser_Id(conversationId, userId))
                .thenReturn(Optional.empty());
        stubTarget(row("xin chào"));

        assertThatThrownBy(() -> service.translate(userId, messageId, "en"))
                .satisfies(error -> assertThat(((ApiException) error).getCode())
                        .isEqualTo(ApiErrorCode.NOT_MEMBER));
    }

    @Test
    void unknownMessageIsNotFound() {
        when(messageRepository.findConversationIdById(messageId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.translate(userId, messageId, "en"))
                .satisfies(error -> assertThat(((ApiException) error).getCode())
                        .isEqualTo(ApiErrorCode.MESSAGE_NOT_FOUND));
    }

    @Test
    void messageBeforeAdmissionIsNotFound() {
        stubTarget(new TargetRow(messageId, 1L, senderId, "Lan", "hi", 0L,
                Instant.parse("2025-01-01T00:00:00Z"), "TEXT", null, null, false));

        assertThatThrownBy(() -> service.translate(userId, messageId, "en"))
                .satisfies(error -> assertThat(((ApiException) error).getCode())
                        .isEqualTo(ApiErrorCode.MESSAGE_NOT_FOUND));
    }

    @Test
    void deletedMessageIsRejected() {
        stubTarget(new TargetRow(messageId, 1L, senderId, "Lan", "hi", 0L,
                Instant.parse("2026-11-16T09:00:00Z"), "TEXT",
                Instant.parse("2026-11-16T09:30:00Z"), null, false));

        assertThatThrownBy(() -> service.translate(userId, messageId, "en"))
                .satisfies(error -> assertThat(((ApiException) error).getCode())
                        .isEqualTo(ApiErrorCode.MESSAGE_DELETED));
    }

    @Test
    void encryptedMessageIsRefused() {
        stubTarget(new TargetRow(messageId, 1L, senderId, "Lan", "hi", 0L,
                Instant.parse("2026-11-16T09:00:00Z"), "TEXT", null, "SIGNAL", false));

        assertThatThrownBy(() -> service.translate(userId, messageId, "en"))
                .satisfies(error -> assertThat(((ApiException) error).getCode())
                        .isEqualTo(ApiErrorCode.E2EE_AI_UNAVAILABLE));
    }

    @Test
    void e2eeConversationIsRefused() {
        when(conversation.isE2eeEnabled()).thenReturn(true);
        stubTarget(row("xin chào"));

        assertThatThrownBy(() -> service.translate(userId, messageId, "en"))
                .satisfies(error -> assertThat(((ApiException) error).getCode())
                        .isEqualTo(ApiErrorCode.E2EE_AI_UNAVAILABLE));
        verify(provider, never()).translate(any());
    }

    @Test
    void nonTextMessageIsRejected() {
        stubTarget(new TargetRow(messageId, 1L, senderId, "Lan", null, 0L,
                Instant.parse("2026-11-16T09:00:00Z"), "IMAGE", null, null, false));

        assertThatThrownBy(() -> service.translate(userId, messageId, "en"))
                .satisfies(error -> assertThat(((ApiException) error).getCode())
                        .isEqualTo(ApiErrorCode.TRANSLATION_UNSUPPORTED));
    }

    @Test
    void optedOutSenderIsRefused() {
        stubTarget(row("xin chào"));
        when(optOuts.optedOutUserIds(conversationId)).thenReturn(Set.of(senderId));

        assertThatThrownBy(() -> service.translate(userId, messageId, "en"))
                .satisfies(error -> assertThat(((ApiException) error).getCode())
                        .isEqualTo(ApiErrorCode.AI_OPTED_OUT));
        verify(provider, never()).translate(any());
    }

    @Test
    void peerSuppressedMessageIsNotFoundForNonSender() {
        stubTarget(new TargetRow(messageId, 1L, senderId, "Lan", "blocked secret text", 0L,
                Instant.parse("2026-11-16T09:00:00Z"), "TEXT", null, null, true));

        assertThatThrownBy(() -> service.translate(userId, messageId, "en"))
                .satisfies(error -> assertThat(((ApiException) error).getCode())
                        .isEqualTo(ApiErrorCode.MESSAGE_NOT_FOUND));
        verify(provider, never()).translate(any());
        verify(rateLimiter, never()).consumeOne(any());
    }

    @Test
    void senderCanStillTranslateOwnSuppressedMessage() {
        stubTarget(new TargetRow(messageId, 1L, userId, "Me", "my own suppressed text", 0L,
                Instant.parse("2026-11-16T09:00:00Z"), "TEXT", null, null, true));
        when(provider.translate(any())).thenReturn(completion("bản dịch của riêng tôi", "en"));

        TranslateResponse response = service.translate(userId, messageId, "vi");

        assertThat(response.text()).isEqualTo("bản dịch của riêng tôi");
        verify(provider).translate(any());
    }

    @Test
    void contextWindowIsScopedToTheRequestingViewer() {
        stubTarget(row("xin chào"));
        when(provider.translate(any())).thenReturn(completion("hello", "vi"));

        service.translate(userId, messageId, "en");

        verify(contextRepository).findContextBefore(eq(conversationId), eq(userId), anyLong(),
                any(), anyInt(), anyInt());
    }

    @Test
    void unsupportedTargetIsRejected() {
        assertThatThrownBy(() -> service.translate(userId, messageId, "xx"))
                .satisfies(error -> assertThat(((ApiException) error).getCode())
                        .isEqualTo(ApiErrorCode.VALIDATION_FAILED));
    }

    // ------------------------------------------------------------- cache

    @Test
    void cacheHitReturnsStoredTranslationWithoutProvider() {
        stubTarget(row("xin chào"));
        when(cacheRepository.find(eq(messageId), eq(0L), eq("en"), eq(GlossaryHasher.EMPTY_HASH)))
                .thenReturn(Optional.of(new TranslationCacheRepository.CacheEntry(
                        "hello", "vi", "gemini-flash", "cloud")));

        TranslateResponse response = service.translate(userId, messageId, "en");

        assertThat(response.text()).isEqualTo("hello");
        assertThat(response.sourceLang()).isEqualTo("vi");
        assertThat(response.cached()).isTrue();
        verify(provider, never()).translate(any());
        verify(rateLimiter, never()).consumeOne(any());
    }

    @Test
    void generateCachesWithMessageVersionAndGlossaryHash() {
        stubTarget(row("xin chào"));
        when(provider.translate(any())).thenReturn(completion("hello", "vi"));

        TranslateResponse response = service.translate(userId, messageId, "en");

        assertThat(response.text()).isEqualTo("hello");
        assertThat(response.cached()).isFalse();
        verify(cacheRepository).save(eq(messageId), eq(0L), eq("en"),
                eq(GlossaryHasher.EMPTY_HASH), any(), any(), anyInt());
    }

    // ------------------------------------------------- provider & guards

    @Test
    void consentRevokedDuringGenerationIsRefusedAndNotCached() {
        stubTarget(row("xin chào"));
        when(provider.translate(any())).thenReturn(completion("hello", "vi"));
        // The entry check consents; the post-generation re-check sees the revoke.
        when(consentRepository.isConsented(userId)).thenReturn(true).thenReturn(false);

        assertThatThrownBy(() -> service.translate(userId, messageId, "en"))
                .satisfies(error -> assertThat(((ApiException) error).getCode())
                        .isEqualTo(ApiErrorCode.AI_CONSENT_REQUIRED));
        verify(provider).translate(any());
        verify(cacheRepository, never()).save(any(), anyLong(), anyString(), anyString(),
                any(), any(), anyInt());
        // The revoke, not the caller's budget, pays for the refused generation.
        verify(rateLimiter).refundOne(userId);
    }

    @Test
    void missingConsentIsRefused() {
        stubTarget(row("xin chào"));
        when(consentRepository.isConsented(userId)).thenReturn(false);

        assertThatThrownBy(() -> service.translate(userId, messageId, "en"))
                .satisfies(error -> assertThat(((ApiException) error).getCode())
                        .isEqualTo(ApiErrorCode.AI_CONSENT_REQUIRED));
        verify(provider, never()).translate(any());
        verify(rateLimiter, never()).consumeOne(any());
        verify(rateLimiter, never()).refundOne(any());
    }

    @Test
    void rateLimitExceededRejectsBeforeProvider() {
        stubTarget(row("xin chào"));
        doThrow(ApiException.of(ApiErrorCode.RATE_LIMITED, "limited"))
                .when(rateLimiter).consumeOne(userId);

        assertThatThrownBy(() -> service.translate(userId, messageId, "en"))
                .satisfies(error -> assertThat(((ApiException) error).getCode())
                        .isEqualTo(ApiErrorCode.RATE_LIMITED));
        verify(provider, never()).translate(any());
        // A refused consume spends nothing, so there is nothing to refund.
        verify(rateLimiter, never()).refundOne(any());
    }

    @Test
    void providerFailureMapsToUnavailableAndRefundsTheToken() {
        stubTarget(row("xin chào"));
        when(provider.translate(any())).thenThrow(new TranslationUnavailableException("down"));

        assertThatThrownBy(() -> service.translate(userId, messageId, "en"))
                .satisfies(error -> assertThat(((ApiException) error).getCode())
                        .isEqualTo(ApiErrorCode.TRANSLATION_UNAVAILABLE));
        // The token bought no generation, so it goes back to the budget.
        verify(rateLimiter).refundOne(userId);
        verify(cacheRepository, never()).save(any(), anyLong(), anyString(), anyString(),
                any(), any(), anyInt());
    }

    @Test
    void unusableProviderOutputMapsToUnavailableAndRefundsTheToken() {
        stubTarget(row("xin chào các bạn"));
        when(provider.translate(any())).thenReturn(
                new TranslationProvider.ProviderCompletion("not json", "m", "cloud"));

        assertThatThrownBy(() -> service.translate(userId, messageId, "en"))
                .satisfies(error -> assertThat(((ApiException) error).getCode())
                        .isEqualTo(ApiErrorCode.TRANSLATION_UNAVAILABLE));
        verify(rateLimiter).refundOne(userId);
        verify(cacheRepository, never()).save(any(), anyLong(), anyString(), anyString(),
                any(), any(), anyInt());
    }

    @Test
    void blankCompletionIsUnavailableRefundedAndNotCached() {
        stubTarget(row("xin chào các bạn"));
        when(provider.translate(any())).thenReturn(
                new TranslationProvider.ProviderCompletion("   \t\n", "m", "cloud"));

        assertThatThrownBy(() -> service.translate(userId, messageId, "en"))
                .satisfies(error -> assertThat(((ApiException) error).getCode())
                        .isEqualTo(ApiErrorCode.TRANSLATION_UNAVAILABLE));
        verify(rateLimiter).refundOne(userId);
        verify(cacheRepository, never()).save(any(), anyLong(), anyString(), anyString(),
                any(), any(), anyInt());
    }

    @Test
    void whitespaceOnlyTranslationTextIsNeverCached() {
        stubTarget(row("xin chào"));
        // Non-breaking spaces slip past String.isBlank(); the validator must
        // still refuse to return or cache a translation that carries no text.
        when(provider.translate(any())).thenReturn(
                new TranslationProvider.ProviderCompletion(
                        "{\"text\":\"\\u00A0\",\"source_lang\":\"vi\"}", "m", "cloud"));

        assertThatThrownBy(() -> service.translate(userId, messageId, "en"))
                .satisfies(error -> assertThat(((ApiException) error).getCode())
                        .isEqualTo(ApiErrorCode.TRANSLATION_UNAVAILABLE));
        verify(rateLimiter).refundOne(userId);
        verify(cacheRepository, never()).save(any(), anyLong(), anyString(), anyString(),
                any(), any(), anyInt());
    }

    @Test
    void sameLanguageSkipsProviderAndRateLimit() {
        stubTarget(row("Xin chào các bạn, hôm nay đẹp trời"));

        TranslateResponse response = service.translate(userId, messageId, "vi");

        assertThat(response.text()).isEqualTo("Xin chào các bạn, hôm nay đẹp trời");
        assertThat(response.sourceLang()).isEqualTo("vi");
        verify(provider, never()).translate(any());
        verify(rateLimiter, never()).consumeOne(any());
    }

    @Test
    void emojiOnlyContentShortCircuits() {
        stubTarget(row("🎉🎊✨"));

        TranslateResponse response = service.translate(userId, messageId, "fr");

        assertThat(response.text()).isEqualTo("🎉🎊✨");
        verify(provider, never()).translate(any());
    }

    @Test
    void vietnameseSourceTranslatedToEnglish() {
        stubTarget(row("tối nay ăn gì"));
        when(provider.translate(any())).thenReturn(completion("what's for dinner", "vi"));

        TranslateResponse response = service.translate(userId, messageId, "en");

        assertThat(response.text()).isEqualTo("what's for dinner");
        assertThat(response.sourceLang()).isEqualTo("vi");
        verify(rateLimiter).consumeOne(userId);
    }

    // ------------------------------------------------------------- batch

    @Test
    void batchMixesHitsRefusalsAndGenerations() {
        UUID cached = UUID.randomUUID();
        UUID deleted = UUID.randomUUID();
        UUID fresh = UUID.randomUUID();
        Instant created = Instant.parse("2026-11-16T09:00:00Z");
        when(contextRepository.findTargets(eq(conversationId), any()))
                .thenReturn(List.of(
                        new TargetRow(cached, 1L, senderId, "A", "xin chào", 0L, created, "TEXT", null, null, false),
                        new TargetRow(deleted, 2L, senderId, "A", "bye", 0L, created, "TEXT",
                                Instant.parse("2026-11-16T09:30:00Z"), null, false),
                        new TargetRow(fresh, 3L, senderId, "A", "tối nay", 0L, created, "TEXT", null, null, false)));
        when(cacheRepository.find(eq(cached), anyLong(), eq("en"), anyString()))
                .thenReturn(Optional.of(new TranslationCacheRepository.CacheEntry(
                        "hello", "vi", "m", "cloud")));
        when(provider.translate(any())).thenReturn(completion("tonight", "vi"));
        when(rateLimiter.tryConsume(userId, 1)).thenReturn(true);

        List<TranslateBatchItem> results = service.translateBatch(
                userId, conversationId, List.of(cached, deleted, fresh), "en");

        assertThat(results).hasSize(3);
        assertThat(results.get(0).messageId()).isEqualTo(cached);
        assertThat(results.get(0).cached()).isTrue();
        assertThat(results.get(1).error()).isEqualTo(ApiErrorCode.MESSAGE_DELETED.getCode());
        assertThat(results.get(2).text()).isEqualTo("tonight");
        verify(provider).translate(any());
    }

    @Test
    void batchStopsConsumingAfterRateLimit() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        Instant created = Instant.parse("2026-11-16T09:00:00Z");
        when(contextRepository.findTargets(eq(conversationId), any()))
                .thenReturn(List.of(
                        new TargetRow(a, 1L, senderId, "A", "tối nay", 0L, created, "TEXT", null, null, false),
                        new TargetRow(b, 2L, senderId, "A", "mai nhé", 0L, created, "TEXT", null, null, false)));
        when(rateLimiter.tryConsume(userId, 1)).thenReturn(false);
        lenient().when(provider.translate(any())).thenReturn(completion("x", "vi"));

        List<TranslateBatchItem> results = service.translateBatch(
                userId, conversationId, List.of(a, b), "en");
        verify(rateLimiter, times(1)).tryConsume(userId, 1);

        assertThat(results).allSatisfy(item -> assertThat(item.error())
                .isEqualTo(ApiErrorCode.RATE_LIMITED.getCode()));
        verify(provider, never()).translate(any());
        // A refused consume spends nothing, so there is nothing to refund.
        verify(rateLimiter, never()).refundOne(any());
    }

    @Test
    void batchRefusesSuppressedItemForNonSender() {
        UUID suppressed = UUID.randomUUID();
        Instant created = Instant.parse("2026-11-16T09:00:00Z");
        when(contextRepository.findTargets(eq(conversationId), any()))
                .thenReturn(List.of(new TargetRow(suppressed, 1L, senderId, "A", "blocked secret", 0L,
                        created, "TEXT", null, null, true)));

        List<TranslateBatchItem> results = service.translateBatch(
                userId, conversationId, List.of(suppressed), "en");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).messageId()).isEqualTo(suppressed);
        assertThat(results.get(0).error()).isEqualTo(ApiErrorCode.MESSAGE_NOT_FOUND.getCode());
        verify(provider, never()).translate(any());
    }

    @Test
    void batchWithoutConsentReturnsConsentErrorPerItem() {
        UUID a = UUID.randomUUID();
        Instant created = Instant.parse("2026-11-16T09:00:00Z");
        when(contextRepository.findTargets(eq(conversationId), any()))
                .thenReturn(List.of(
                        new TargetRow(a, 1L, senderId, "A", "tối nay", 0L, created, "TEXT", null, null, false)));
        when(consentRepository.isConsented(userId)).thenReturn(false);

        List<TranslateBatchItem> results = service.translateBatch(
                userId, conversationId, List.of(a), "en");

        assertThat(results.get(0).error()).isEqualTo(ApiErrorCode.AI_CONSENT_REQUIRED.getCode());
        verify(rateLimiter, never()).tryConsume(any(), anyInt());
        verify(rateLimiter, never()).refundOne(any());
    }

    @Test
    void batchConsentRevokedDuringGenerationRefusesSkipsCacheAndStopsTheBatch() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        Instant created = Instant.parse("2026-11-16T09:00:00Z");
        when(contextRepository.findTargets(eq(conversationId), any()))
                .thenReturn(List.of(
                        new TargetRow(a, 1L, senderId, "A", "tối nay", 0L, created, "TEXT", null, null, false),
                        new TargetRow(b, 2L, senderId, "A", "mai nhé", 0L, created, "TEXT", null, null, false)));
        when(provider.translate(any())).thenReturn(completion("tonight", "vi"));
        when(rateLimiter.tryConsume(userId, 1)).thenReturn(true);
        // Item a's entry check consents and its post-generation re-check sees
        // the revoke; item b's own per-item entry check then refuses without
        // consuming a token or contacting the provider.
        when(consentRepository.isConsented(userId)).thenReturn(true).thenReturn(false);

        List<TranslateBatchItem> results = service.translateBatch(
                userId, conversationId, List.of(a, b), "en");

        assertThat(results).allSatisfy(item -> assertThat(item.error())
                .isEqualTo(ApiErrorCode.AI_CONSENT_REQUIRED.getCode()));
        verify(provider, times(1)).translate(any());
        verify(rateLimiter, times(1)).tryConsume(userId, 1);
        // Only item a consumed; its token is refunded because the revoke won.
        verify(rateLimiter, times(1)).refundOne(userId);
        verify(cacheRepository, never()).save(any(), anyLong(), anyString(), anyString(),
                any(), any(), anyInt());
    }

    @Test
    void batchProviderFailureRefundsEveryConsumedToken() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        Instant created = Instant.parse("2026-11-16T09:00:00Z");
        when(contextRepository.findTargets(eq(conversationId), any()))
                .thenReturn(List.of(
                        new TargetRow(a, 1L, senderId, "A", "tối nay", 0L, created, "TEXT", null, null, false),
                        new TargetRow(b, 2L, senderId, "A", "mai nhé", 0L, created, "TEXT", null, null, false)));
        when(rateLimiter.tryConsume(userId, 1)).thenReturn(true);
        when(provider.translate(any())).thenThrow(new TranslationUnavailableException("down"));

        List<TranslateBatchItem> results = service.translateBatch(
                userId, conversationId, List.of(a, b), "en");

        assertThat(results).allSatisfy(item -> assertThat(item.error())
                .isEqualTo(ApiErrorCode.TRANSLATION_UNAVAILABLE.getCode()));
        verify(provider, times(2)).translate(any());
        // Every consumed token is returned: a failed generation bills no one.
        verify(rateLimiter, times(2)).refundOne(userId);
        verify(cacheRepository, never()).save(any(), anyLong(), anyString(), anyString(),
                any(), any(), anyInt());
    }

    @Test
    void batchRejectsNonMember() {
        when(memberRepository.findByConversation_IdAndUser_Id(conversationId, userId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.translateBatch(userId, conversationId,
                List.of(messageId), "en"))
                .satisfies(error -> assertThat(((ApiException) error).getCode())
                        .isEqualTo(ApiErrorCode.NOT_MEMBER));
    }

    @Test
    void batchRefusesE2eeConversation() {
        when(conversation.isE2eeEnabled()).thenReturn(true);

        assertThatThrownBy(() -> service.translateBatch(userId, conversationId,
                List.of(messageId), "en"))
                .satisfies(error -> assertThat(((ApiException) error).getCode())
                        .isEqualTo(ApiErrorCode.E2EE_AI_UNAVAILABLE));
    }

    // ---------------------------------------------------------- glossary

    @Test
    void glossaryReplaceDedupesAndTrims() {
        TranslationGlossaryResponse response = service.replaceGlossary(userId, conversationId,
                List.of(new GlossaryTermDto("  NexaChat  ", null),
                        new GlossaryTermDto("nexachat", "NexaChat"),
                        new GlossaryTermDto("sếp", "boss")));

        assertThat(response.terms()).hasSize(2);
        // Case-insensitive dedupe, last entry wins (position kept).
        verify(glossaryRepository).replaceTerms(eq(conversationId), eq(userId),
                eq(List.of(new GlossaryTerm("nexachat", "NexaChat"),
                        new GlossaryTerm("sếp", "boss"))));
    }

    @Test
    void glossaryRejectsBlankTerm() {
        assertThatThrownBy(() -> service.replaceGlossary(userId, conversationId,
                List.of(new GlossaryTermDto("   ", "x"))))
                .satisfies(error -> assertThat(((ApiException) error).getCode())
                        .isEqualTo(ApiErrorCode.VALIDATION_FAILED));
    }

    @Test
    void glossaryHashChangesWithTerms() {
        String a = GlossaryHasher.hash(List.of(new GlossaryTerm("a", "b")));
        String b = GlossaryHasher.hash(List.of(new GlossaryTerm("a", "c")));
        assertThat(a).isNotEqualTo(b);
        assertThat(GlossaryHasher.hash(List.of())).isEqualTo(GlossaryHasher.EMPTY_HASH);
    }

    @Test
    void glossaryRequiresMembership() {
        when(memberRepository.findByConversation_IdAndUser_Id(conversationId, userId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getGlossary(userId, conversationId))
                .satisfies(error -> assertThat(((ApiException) error).getCode())
                        .isEqualTo(ApiErrorCode.NOT_MEMBER));
    }

    // -------------------------------------------------------------- prefs

    @Test
    void prefsDefaultWhenUnset() {
        when(prefsRepository.find(conversationId, userId)).thenReturn(Optional.empty());

        var response = service.getPrefs(userId, conversationId);

        assertThat(response.autoTranslate()).isFalse();
        assertThat(response.targetLang()).isNull();
    }

    @Test
    void prefsUpdateValidatesLanguage() {
        assertThatThrownBy(() -> service.updatePrefs(userId, conversationId, true, "klingon"))
                .satisfies(error -> assertThat(((ApiException) error).getCode())
                        .isEqualTo(ApiErrorCode.VALIDATION_FAILED));
    }

    @Test
    void prefsUpdatePersists() {
        var response = service.updatePrefs(userId, conversationId, true, "EN");

        assertThat(response.autoTranslate()).isTrue();
        assertThat(response.targetLang()).isEqualTo("en");
        verify(prefsRepository).upsert(conversationId, userId, true, "en");
    }
}
