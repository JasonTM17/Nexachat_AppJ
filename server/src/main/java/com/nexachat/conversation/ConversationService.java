package com.nexachat.conversation;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.conversation.dto.ConversationMemberView;
import com.nexachat.conversation.dto.ConversationResponse;
import com.nexachat.conversation.dto.OpenDirectRequest;
import com.nexachat.summary.ConversationAiOptOuts;
import com.nexachat.user.PhoneService;
import com.nexachat.user.User;
import com.nexachat.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Conversation use cases — currently only the DIRECT path (plan §10.2,
 * phase-06 scope; group endpoints arrive in a later phase).
 *
 * <p>{@link #openDirect} is idempotent by construction:
 * {@code direct_key = LEAST(u1,u2):GREATEST(u1,u2)} + the UNIQUE constraint is
 * the single source of truth, so both users pressing "chat" at once — or a
 * retried request — converges on one conversation id. The method is
 * deliberately NOT {@code @Transactional}: the insert runs in its own
 * transaction via {@link DirectConversationCreator} so a lost race rolls back
 * cleanly and the re-read below sees the winner's committed row (PostgreSQL
 * aborts a transaction on the first failed statement).
 */
@Service
public class ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);

    private final ConversationRepository conversationRepository;
    private final ConversationMemberRepository memberRepository;
    private final UserRepository userRepository;
    private final PhoneService phoneService;
    private final DirectConversationCreator directConversationCreator;
    private final ConversationAiOptOuts aiOptOuts;

    public ConversationService(ConversationRepository conversationRepository,
                               ConversationMemberRepository memberRepository,
                               UserRepository userRepository,
                               PhoneService phoneService,
                               DirectConversationCreator directConversationCreator) {
        this(conversationRepository, memberRepository, userRepository, phoneService,
                directConversationCreator, ConversationAiOptOuts.none());
    }

    @Autowired
    public ConversationService(ConversationRepository conversationRepository,
                               ConversationMemberRepository memberRepository,
                               UserRepository userRepository,
                               PhoneService phoneService,
                               DirectConversationCreator directConversationCreator,
                               ConversationAiOptOuts aiOptOuts) {
        this.conversationRepository = conversationRepository;
        this.memberRepository = memberRepository;
        this.userRepository = userRepository;
        this.phoneService = phoneService;
        this.directConversationCreator = directConversationCreator;
        this.aiOptOuts = aiOptOuts;
    }

    /** Service-internal result: response body plus whether this call created the row. */
    public record OpenDirectResult(ConversationResponse body, boolean created) {
    }

    /**
     * Opens (or re-opens) the DIRECT conversation between the caller and the
     * user identified by exactly one of {@code request.phone}/{@code request.userId}.
     *
     * @return the conversation — always the same id for a given pair
     */
    public OpenDirectResult openDirect(UUID callerId, OpenDirectRequest request) {
        boolean hasPhone = request.phone() != null && !request.phone().isBlank();
        boolean hasUserId = request.userId() != null;
        if (hasPhone == hasUserId) {
            throw ApiException.of(
                    ApiErrorCode.VALIDATION_FAILED, "Provide exactly one of phone or userId");
        }

        User other = hasUserId
                ? userRepository.findById(request.userId())
                        .orElseThrow(() -> ApiException.of(ApiErrorCode.USER_NOT_FOUND, "User not found"))
                : resolveByPhone(request.phone());

        if (other.getId().equals(callerId)) {
            throw ApiException.of(
                    ApiErrorCode.VALIDATION_FAILED, "Cannot open a direct conversation with yourself");
        }

        User caller = userRepository.findById(callerId)
                .orElseThrow(() -> ApiException.of(ApiErrorCode.USER_NOT_FOUND, "User not found"));

        String directKey = directKey(callerId, other.getId());
        Conversation conversation = conversationRepository.findByDirectKey(directKey).orElse(null);
        boolean created = false;

        if (conversation == null) {
            try {
                conversation = directConversationCreator.createDirect(caller, other, directKey);
                created = true;
                log.info("Direct conversation {} created by {} with {}",
                        conversation.getId(), callerId, other.getId());
            } catch (DataIntegrityViolationException e) {
                // Lost the direct_key race — the winner's row is committed; re-read it.
                conversation = conversationRepository.findByDirectKey(directKey)
                        .orElseThrow(() -> ApiException.of(
                                ApiErrorCode.INTERNAL_ERROR,
                                "Direct conversation insert raced but no winner row found"));
            }
        }

        List<ConversationMemberView> members = memberRepository.findMemberViews(conversation.getId());
        return new OpenDirectResult(
                new ConversationResponse(
                        conversation.getId(), conversation.getType().name(), members,
                        conversation.getCreatedAt(),
                        aiOptOuts.isOptedOut(conversation.getId(), callerId)),
                created);
    }

    /**
     * Canonical pair key: {@code "lowerUuid:higherUuid"} on the lowercase
     * string form. String compare, NOT {@code UUID.compareTo} — UUID ordering
     * uses signed 64-bit halves, which disagrees with lexicographic ordering
     * whenever the most-significant bit differs (e.g. 7fff… vs 8000…).
     */
    static String directKey(UUID a, UUID b) {
        String first = a.toString();
        String second = b.toString();
        return first.compareTo(second) < 0 ? first + ':' + second : second + ':' + first;
    }

    private User resolveByPhone(String rawPhone) {
        final String phone;
        try {
            phone = phoneService.toE164(rawPhone);
        } catch (IllegalArgumentException e) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "Phone number is not valid");
        }
        return userRepository.findByPhone(phone)
                .orElseThrow(() -> ApiException.of(
                        ApiErrorCode.USER_NOT_FOUND, "No user registered with that phone number"));
    }
}
