package com.nexachat.user;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.conversation.ConversationMemberRepository;
import com.nexachat.presence.PresenceSessionState;
import com.nexachat.presence.UserPresenceChangedEvent;
import com.nexachat.user.dto.UserSettingsResponse;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class UserSettingsService {

    private final UserRepository userRepository;
    private final ConversationMemberRepository memberRepository;
    private final PresenceSessionState sessionState;
    private final ApplicationEventPublisher eventPublisher;

    public UserSettingsService(UserRepository userRepository,
                               ConversationMemberRepository memberRepository,
                               PresenceSessionState sessionState,
                               ApplicationEventPublisher eventPublisher) {
        this.userRepository = userRepository;
        this.memberRepository = memberRepository;
        this.sessionState = sessionState;
        this.eventPublisher = eventPublisher;
    }

    @Transactional(readOnly = true)
    public UserSettingsResponse get(UUID userId) {
        return toResponse(findUser(userId));
    }

    @Transactional
    public UserSettingsResponse setShowPresence(UUID userId, boolean showPresence) {
        return updateSettings(userId, showPresence, null, null);
    }

    @Transactional
    public UserSettingsResponse updateSettings(
            UUID userId,
            Boolean showPresence,
            Boolean showSeen,
            Boolean aiSummaryConsent) {
        User user = findUser(userId);
        if (showPresence != null) {
            boolean changed = user.isShowPresence() != showPresence;
            user.changeShowPresence(showPresence);
            // A repeated hide is also a retry: the previous after-commit RTDB
            // deny may have failed after this SQL preference was committed.
            if (changed || !showPresence) {
                eventPublisher.publishEvent(new UserPresenceVisibilityChangedEvent(userId, showPresence));
            }
            if (changed && sessionState.isOnline(userId)) {
                eventPublisher.publishEvent(new UserPresenceChangedEvent(
                        userId, showPresence, null, memberRepository.findDirectPeerUserIds(userId)));
            }
        }
        if (showSeen != null) {
            user.changeShowSeen(showSeen);
        }
        if (aiSummaryConsent != null) {
            user.changeAiSummaryConsent(aiSummaryConsent);
        }
        return toResponse(user);
    }

    private User findUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> ApiException.of(ApiErrorCode.USER_NOT_FOUND, "User not found"));
    }

    private static UserSettingsResponse toResponse(User user) {
        return new UserSettingsResponse(user.isShowPresence(), user.isShowSeen(),
                user.isAiSummaryConsent());
    }
}
