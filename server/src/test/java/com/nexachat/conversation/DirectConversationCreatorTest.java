package com.nexachat.conversation;

import com.nexachat.user.User;
import com.nexachat.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DirectConversationCreatorTest {

    @Test
    void newDirectConversationPublishesPostCommitProjectionTrigger() {
        UUID callerId = UUID.fromString("11111111-1111-4111-8111-111111111111");
        UUID peerId = UUID.fromString("22222222-2222-4222-8222-222222222222");
        UUID conversationId = UUID.fromString("33333333-3333-4333-8333-333333333333");
        ConversationRepository conversations = mock(ConversationRepository.class);
        ConversationMemberRepository members = mock(ConversationMemberRepository.class);
        UserRepository users = mock(UserRepository.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        User caller = User.create("+84901110001", "hash", "Caller");
        User peer = User.create("+84901110002", "hash", "Peer");
        ReflectionTestUtils.setField(caller, "id", callerId);
        ReflectionTestUtils.setField(peer, "id", peerId);
        when(users.findActiveUsersByIdsForUpdate(List.of(callerId, peerId))).thenReturn(List.of(caller, peer));
        when(conversations.saveAndFlush(any(Conversation.class))).thenAnswer(invocation -> {
            Conversation saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", conversationId);
            return saved;
        });

        new DirectConversationCreator(conversations, members, users, publisher)
                .createDirect(callerId, peerId, callerId + ":" + peerId);

        verify(publisher).publishEvent(new ConversationMembershipChangedEvent(conversationId, List.of()));
    }
}
