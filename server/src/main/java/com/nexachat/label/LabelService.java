package com.nexachat.label;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.conversation.ConversationMemberRepository;
import com.nexachat.conversation.ConversationRepository;
import com.nexachat.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Business rules for caller-private conversation labels and assignments. */
@Service
public class LabelService {

    static final int MAX_LABELS_PER_USER = 10;
    static final int MAX_LABELS_PER_CONVERSATION = 3;
    private static final Set<String> COLORS = Set.of(
            "INDIGO", "BLUE", "CYAN", "TEAL", "EMERALD", "AMBER", "ROSE", "PURPLE");

    private final LabelRepository labelRepository;
    private final UserRepository userRepository;
    private final ConversationRepository conversationRepository;
    private final ConversationMemberRepository memberRepository;
    private final LabelRateLimiter rateLimiter;

    public LabelService(LabelRepository labelRepository,
                        UserRepository userRepository,
                        ConversationRepository conversationRepository,
                        ConversationMemberRepository memberRepository,
                        LabelRateLimiter rateLimiter) {
        this.labelRepository = labelRepository;
        this.userRepository = userRepository;
        this.conversationRepository = conversationRepository;
        this.memberRepository = memberRepository;
        this.rateLimiter = rateLimiter;
    }

    @Transactional(readOnly = true)
    public List<LabelResponse> list(UUID userId) {
        return labelRepository.findAllByUserId(userId);
    }

    @Transactional
    public LabelResponse create(UUID userId, CreateLabelRequest request) {
        rateLimiter.checkWrite(userId);
        String name = normalizeName(request == null ? null : request.name());
        String colorId = validateColor(request == null ? null : request.colorId());
        lockUser(userId);
        if (labelRepository.countByUserId(userId) >= MAX_LABELS_PER_USER) {
            throw validation("At most " + MAX_LABELS_PER_USER + " labels per user");
        }
        if (labelRepository.existsName(userId, name)) {
            throw validation("A label with that name already exists");
        }
        UUID id = UUID.randomUUID();
        labelRepository.insert(id, userId, name, colorId);
        return labelRepository.findByIdAndUserId(id, userId).orElseThrow();
    }

    @Transactional
    public LabelResponse patch(UUID userId, UUID labelId, PatchLabelRequest request) {
        rateLimiter.checkWrite(userId);
        lockUser(userId);
        LabelResponse current = requireOwnedLabel(userId, labelId);
        String name = request == null || request.name() == null
                ? current.name() : normalizeName(request.name());
        String colorId = request == null || request.colorId() == null
                ? current.colorId() : validateColor(request.colorId());
        if (labelRepository.existsNameOtherThan(userId, name, labelId)) {
            throw validation("A label with that name already exists");
        }
        labelRepository.update(labelId, userId, name, colorId);
        return labelRepository.findByIdAndUserId(labelId, userId).orElseThrow();
    }

    @Transactional
    public void delete(UUID userId, UUID labelId) {
        rateLimiter.checkWrite(userId);
        lockUser(userId);
        // A foreign or missing id is deliberately indistinguishable and idempotent.
        labelRepository.delete(labelId, userId);
    }

    @Transactional
    public void assign(UUID userId, UUID conversationId, UUID labelId) {
        rateLimiter.checkWrite(userId);
        requireConversation(conversationId);
        lockMember(conversationId, userId);
        requireOwnedLabelForAssignment(userId, labelId);
        if (labelRepository.hasAssignment(conversationId, userId, labelId)) {
            return;
        }
        if (labelRepository.countAssignments(conversationId, userId)
                >= MAX_LABELS_PER_CONVERSATION) {
            throw validation("At most " + MAX_LABELS_PER_CONVERSATION
                    + " labels per conversation");
        }
        labelRepository.insertAssignment(conversationId, userId, labelId);
    }

    @Transactional
    public void unassign(UUID userId, UUID conversationId, UUID labelId) {
        rateLimiter.checkWrite(userId);
        requireConversation(conversationId);
        lockMember(conversationId, userId);
        requireOwnedLabelForAssignment(userId, labelId);
        labelRepository.deleteAssignment(conversationId, userId, labelId);
    }

    private void lockUser(UUID userId) {
        userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> ApiException.of(ApiErrorCode.USER_NOT_FOUND, "User not found"));
    }

    private void requireConversation(UUID conversationId) {
        if (!conversationRepository.existsById(conversationId)) {
            throw ApiException.of(ApiErrorCode.CONVERSATION_NOT_FOUND, "Conversation not found");
        }
    }

    private void lockMember(UUID conversationId, UUID userId) {
        memberRepository.findByConversation_IdAndUser_IdForUpdate(conversationId, userId)
                .orElseThrow(() -> ApiException.of(
                        ApiErrorCode.NOT_MEMBER, "Not a member of this conversation"));
    }

    private LabelResponse requireOwnedLabel(UUID userId, UUID labelId) {
        return labelRepository.findByIdAndUserId(labelId, userId)
                .orElseThrow(() -> ApiException.of(ApiErrorCode.NOT_FOUND, "Label not found"));
    }

    private LabelResponse requireOwnedLabelForAssignment(UUID userId, UUID labelId) {
        return labelRepository.findByIdAndUserIdForKeyShare(labelId, userId)
                .orElseThrow(() -> ApiException.of(ApiErrorCode.NOT_FOUND, "Label not found"));
    }

    private static String normalizeName(String value) {
        String normalized = value == null ? "" : value.strip();
        int length = normalized.codePointCount(0, normalized.length());
        if (length < 1 || length > 30) {
            throw validation("Label name must contain 1 to 30 characters");
        }
        return normalized;
    }

    private static String validateColor(String colorId) {
        if (colorId == null || !COLORS.contains(colorId)) {
            throw validation("colorId must be one of the supported palette IDs");
        }
        return colorId;
    }

    private static ApiException validation(String message) {
        return ApiException.of(ApiErrorCode.VALIDATION_FAILED, message);
    }
}
