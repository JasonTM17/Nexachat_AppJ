package com.nexachat.user;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.conversation.ConversationMemberRepository;
import com.nexachat.presence.PresenceSessionState;
import com.nexachat.presence.UserPresenceChangedEvent;
import com.nexachat.streak.StreakService;
import com.nexachat.user.dto.UserSettingsResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class UserSettingsService {

    private final UserRepository userRepository;
    private final ConversationMemberRepository memberRepository;
    private final UserBlockRepository blockRepository;
    private final PresenceSessionState sessionState;
    private final ApplicationEventPublisher eventPublisher;
    /** Optional: absent in database-less test profiles and legacy unit-test wiring. */
    private StreakService streakService;

    public UserSettingsService(UserRepository userRepository,
                               ConversationMemberRepository memberRepository,
                               UserBlockRepository blockRepository,
                               PresenceSessionState sessionState,
                               ApplicationEventPublisher eventPublisher) {
        this.userRepository = userRepository;
        this.memberRepository = memberRepository;
        this.blockRepository = blockRepository;
        this.sessionState = sessionState;
        this.eventPublisher = eventPublisher;
    }

    @Autowired(required = false)
    void setStreakService(StreakService streakService) {
        this.streakService = streakService;
    }

    @Transactional(readOnly = true)
    public UserSettingsResponse get(UUID userId) {
        return toResponse(findUser(userId));
    }

    @Transactional
    public UserSettingsResponse setShowPresence(UUID userId, boolean showPresence) {
        return updateSettings(userId, showPresence, null, null, null);
    }

    @Transactional
    public UserSettingsResponse updateSettings(
            UUID userId,
            Boolean showPresence,
            Boolean showSeen,
            Boolean aiSummaryConsent) {
        return updateSettings(userId, showPresence, showSeen, aiSummaryConsent, null);
    }

    @Transactional
    public UserSettingsResponse updateSettings(
            UUID userId,
            Boolean showPresence,
            Boolean showSeen,
            Boolean aiSummaryConsent,
            String translateTargetLang) {
        return updateSettings(userId, showPresence, showSeen, aiSummaryConsent,
                translateTargetLang, null);
    }

    @Transactional
    public UserSettingsResponse updateSettings(
            UUID userId,
            Boolean showPresence,
            Boolean showSeen,
            Boolean aiSummaryConsent,
            String translateTargetLang,
            Boolean streakReminders) {
        User user = findUserForUpdate(userId);
        if (showPresence != null) {
            boolean changed = user.isShowPresence() != showPresence;
            user.changeShowPresence(showPresence);
            // A repeated hide is also a retry: the previous after-commit RTDB
            // deny may have failed after this SQL preference was committed.
            if (changed || !showPresence) {
                eventPublisher.publishEvent(new UserPresenceVisibilityChangedEvent(userId, showPresence));
            }
            if (changed && sessionState.isOnline(userId)) {
                java.util.List<UUID> peers = memberRepository.findDirectPeerUserIds(userId);
                // V38: the visibility-flip broadcast honors the same block
                // audience as connect/disconnect — an edge in either
                // direction removes the peer from the update.
                java.util.Set<UUID> hidden = new java.util.HashSet<>(
                        blockRepository.findRelatedIdsEitherDirection(userId, peers));
                if (!hidden.isEmpty()) {
                    peers = peers.stream().filter(id -> !hidden.contains(id)).toList();
                }
                eventPublisher.publishEvent(new UserPresenceChangedEvent(
                        userId, showPresence, null, peers));
            }
        }
        if (showSeen != null) {
            user.changeShowSeen(showSeen);
        }
        if (aiSummaryConsent != null) {
            user.changeAiSummaryConsent(aiSummaryConsent);
        }
        if (translateTargetLang != null) {
            // Blank clears the override; otherwise the code must be a language
            // the translate feature actually offers.
            String lang = translateTargetLang.isBlank() ? null : translateTargetLang;
            if (lang != null && !com.nexachat.translate.TranslationLanguage.isSupported(lang)) {
                throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                        "Unsupported target language");
            }
            user.changeTranslateTargetLang(lang == null ? null
                    : com.nexachat.translate.TranslationLanguage.of(lang).orElseThrow().code());
        }
        if (streakReminders != null && streakService != null) {
            streakService.setReminderEnabled(userId, streakReminders);
        }
        return toResponse(user);
    }

    @Transactional(readOnly = true)
    User findUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> ApiException.of(ApiErrorCode.USER_NOT_FOUND, "User not found"));
    }

    private User findUserForUpdate(UUID userId) {
        return ActiveUserSupport.requireActiveForUpdate(userRepository, userId);
    }

    private UserSettingsResponse toResponse(User user) {
        boolean streakReminders = streakService != null
                && streakService.reminderEnabled(user.getId());
        return new UserSettingsResponse(user.isShowPresence(), user.isShowSeen(),
                user.isAiSummaryConsent(), user.getTranslateTargetLang(), streakReminders);
    }
}
