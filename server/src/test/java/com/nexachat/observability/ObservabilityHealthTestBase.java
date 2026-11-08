package com.nexachat.observability;

import com.nexachat.auth.JwtService;
import com.nexachat.contact.ContactRepository;
import com.nexachat.conversation.ConversationMemberRepository;
import com.nexachat.conversation.ConversationRepository;
import com.nexachat.message.MessageAttachmentRepository;
import com.nexachat.message.MessageReactionRepository;
import com.nexachat.message.MessageRepository;
import com.nexachat.message.PinnedMessageRepository;
import com.nexachat.message.ReactionEventRepository;
import com.nexachat.message.RequestIdempotencyRepository;
import com.nexachat.notification.UserDeviceTokenRepository;
import com.nexachat.user.User;
import com.nexachat.user.UserRepository;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Shared wiring for the observability web tests. The {@code test} profile
 * excludes JPA and Flyway, so the repositories are mocked exactly as in
 * {@code NexaChatApplicationTests}.
 */
@SpringBootTest
abstract class ObservabilityHealthTestBase {

    @MockitoBean
    protected UserRepository userRepository;
    @MockitoBean
    protected com.nexachat.auth.RefreshTokenRepository refreshTokenRepository;
    @MockitoBean
    protected ContactRepository contactRepository;
    @MockitoBean
    protected ConversationRepository conversationRepository;
    @MockitoBean
    protected ConversationMemberRepository conversationMemberRepository;
    @MockitoBean
    protected MessageRepository messageRepository;
    @MockitoBean
    protected MessageAttachmentRepository messageAttachmentRepository;
    @MockitoBean
    protected MessageReactionRepository messageReactionRepository;
    @MockitoBean
    protected ReactionEventRepository reactionEventRepository;
    @MockitoBean
    protected RequestIdempotencyRepository requestIdempotencyRepository;
    @MockitoBean
    protected PinnedMessageRepository pinnedMessageRepository;
    @MockitoBean
    protected UserDeviceTokenRepository userDeviceTokenRepository;

    protected static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not allocate a management port", exception);
        }
    }

    protected static Path tempStorageRoot() {
        try {
            return Files.createTempDirectory("nexachat-obs-health-");
        } catch (IOException exception) {
            throw new IllegalStateException("Could not create a storage root", exception);
        }
    }

    protected static String url(int port, String path) {
        return "http://127.0.0.1:" + port + path;
    }

    /** A valid access token for {@code userId}; the mocked repository accepts it. */
    protected String accessToken(JwtService jwtService, UUID userId) {
        when(userRepository.existsById(userId)).thenReturn(true);
        User user = mock(User.class);
        when(user.getId()).thenReturn(userId);
        return jwtService.generateAccessToken(user);
    }
}
