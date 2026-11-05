package com.nexachat.summary;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.conversation.ConversationMember;
import com.nexachat.conversation.ConversationMemberRepository;
import com.nexachat.conversation.ConversationRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Service
@Profile("!test")
public class SummaryWindowService {

    private final ConversationRepository conversationRepository;
    private final ConversationMemberRepository memberRepository;
    private final SummaryConsentRepository consentRepository;
    private final SummaryWindowRepository windowRepository;
    private final Clock clock;

    public SummaryWindowService(
            ConversationRepository conversationRepository,
            ConversationMemberRepository memberRepository,
            SummaryConsentRepository consentRepository,
            SummaryWindowRepository windowRepository,
            Clock clock) {
        this.conversationRepository = conversationRepository;
        this.memberRepository = memberRepository;
        this.consentRepository = consentRepository;
        this.windowRepository = windowRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public SummaryReadContext load(UUID conversationId, UUID userId, SummaryRequest request) {
        if (!conversationRepository.existsById(conversationId)) {
            throw ApiException.of(ApiErrorCode.CONVERSATION_NOT_FOUND, "Conversation not found");
        }
        ConversationMember member = memberRepository.findByConversation_IdAndUser_Id(conversationId, userId)
                .orElseThrow(() -> ApiException.of(ApiErrorCode.NOT_MEMBER, "Conversation membership required"));

        Long afterSeq = request.scope() == SummaryScope.UNREAD ? member.getLastReadSeq() : null;
        Instant fromInclusive = null;
        Instant toExclusive = null;
        if (request.scope() == SummaryScope.TODAY) {
            LocalDate utcDate = LocalDate.now(clock.withZone(ZoneOffset.UTC));
            fromInclusive = utcDate.atStartOfDay(ZoneOffset.UTC).toInstant();
            toExclusive = utcDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        }

        List<SummaryMessage> messages = windowRepository.findWindow(
                conversationId, request, afterSeq, fromInclusive, toExclusive);
        return new SummaryReadContext(
                new SummaryWindow(conversationId, 0, 0, messages),
                consentRepository.isConsented(userId));
    }
}
