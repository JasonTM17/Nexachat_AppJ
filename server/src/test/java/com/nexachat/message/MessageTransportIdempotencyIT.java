package com.nexachat.message;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexachat.conversation.ConcurrentDatabaseITSupport;
import com.nexachat.message.dto.SendMessageRequest;
import com.nexachat.ws.EventEnvelope;
import com.nexachat.ws.StompUserPrincipal;
import com.nexachat.ws.WsEventType;
import com.nexachat.ws.WsMessageController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Real REST dispatcher and STOMP handler, real DB; this is not a WebSocket wire test. */
@Import(MessageTransportIdempotencyIT.EventConfiguration.class)
class MessageTransportIdempotencyIT extends ConcurrentDatabaseITSupport {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private WsMessageController stomp;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private CommittedCreations events;
    @MockitoSpyBean private SimpMessagingTemplate messaging;

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void concurrentRestAndStompDuplicatesCreateOneRowAndOneCreationWithAReplayAcknowledgment(boolean restStartsFirst)
            throws Exception {
        var sender = user("Sender");
        UUID conversation = group(sender, user("Peer"));
        var request = request();
        String access = access(sender);
        long revision = count("SELECT change_version FROM conversations WHERE id=?", conversation);
        try (Workers workers = new Workers(); Gate gate = gate("users", sender.getId())) {
            java.util.concurrent.Callable<Integer> rest = () -> rest(conversation, access, request);
            java.util.concurrent.Callable<Integer> ws = () -> {
                stomp.send(conversation, request, new StompUserPrincipal(sender.getId()));
                return 200;
            };
            var first = workers.submit(restStartsFirst ? rest : ws);
            awaitBlocked(gate.pid, 1);
            var second = workers.submit(restStartsFirst ? ws : rest);
            awaitBlocked(gate.pid, 2);
            gate.commit();
            assertThat(List.of(result(first), result(second))).allSatisfy(status -> assertThat(status).isIn(200, 201));
        }
        assertStoredOnce(request);
        assertThat(count("SELECT change_version FROM conversations WHERE id=?", conversation)).isEqualTo(revision + 1);
        var matching = events.forClient(request.clientMessageId());
        assertThat(matching).hasSize(2);
        assertThat(matching.stream().filter(event -> !event.replay())).hasSize(1);
        assertThat(matching.stream().filter(MessageCreatedEvent::replay)).hasSize(1);
        assertThat(matching).extracting(event -> event.message().id()).containsOnly(matching.getFirst().message().id());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void failureAfterInsertBeforeCommitRollsBackAndTheOtherEntryPointCanRetry(boolean restFirst) throws Exception {
        var sender = user("Sender");
        UUID conversation = group(sender, user("Peer"));
        String access = access(sender);
        var request = request();
        long revision = count("SELECT change_version FROM conversations WHERE id=?", conversation);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(status -> {
            try {
                if (restFirst) assertThat(rest(conversation, access, request)).isEqualTo(201);
                else stomp.send(conversation, request, new StompUserPrincipal(sender.getId()));
                assertStoredOnce(request);
                assertThat(events.forClient(request.clientMessageId())).isEmpty();
                throw new BeforeCommitFailure();
            } catch (RuntimeException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
        })).isInstanceOf(BeforeCommitFailure.class);
        assertThat(count("SELECT count(*) FROM messages WHERE client_message_id=?", request.clientMessageId())).isZero();
        assertThat(count("SELECT change_version FROM conversations WHERE id=?", conversation)).isEqualTo(revision);
        assertThat(events.forClient(request.clientMessageId())).isEmpty();
        if (restFirst) stomp.send(conversation, request, new StompUserPrincipal(sender.getId()));
        else assertThat(rest(conversation, access, request)).isEqualTo(201);
        assertStoredOnce(request);
        assertThat(events.forClient(request.clientMessageId())).singleElement().satisfies(event -> assertThat(event.replay()).isFalse());
    }

    @Test
    void brokerFailureAfterCommitLeavesOneMessageAndTheRetryAcknowledgesThatIdentity() throws Exception {
        var sender = user("Sender");
        UUID conversation = group(sender, user("Peer"));
        var request = request();
        AtomicBoolean failOnce = new AtomicBoolean(true);
        AtomicInteger delivered = new AtomicInteger();
        doAnswer(invocation -> {
            Object payload = invocation.getArgument(1);
            if (payload instanceof EventEnvelope envelope && envelope.eventType() == WsEventType.MESSAGE_CREATED
                    && envelope.payload() instanceof com.nexachat.message.dto.MessageResponse message
                    && request.clientMessageId().equals(message.clientMessageId())) {
                if (failOnce.compareAndSet(true, false)) throw new AfterCommitFailure();
                delivered.incrementAndGet();
            }
            return invocation.callRealMethod();
        }).when(messaging).convertAndSend(eq("/topic/conversations." + conversation), any(Object.class));

        // Spring logs AFTER_COMMIT callback failure; the message transaction has already committed.
        stomp.send(conversation, request, new StompUserPrincipal(sender.getId()));
        UUID storedId = jdbc.queryForObject("SELECT id FROM messages WHERE client_message_id=?", UUID.class, request.clientMessageId());
        assertThat(delivered).hasValue(0);
        assertThat(rest(conversation, access(sender), request)).isEqualTo(200);
        assertStoredOnce(request);
        assertThat(delivered).hasValue(1);
        assertThat(events.forClient(request.clientMessageId())).extracting(event -> event.message().id()).containsOnly(storedId);
    }

    private int rest(UUID conversation, String access, SendMessageRequest request) throws Exception {
        return mvc.perform(post("/api/v1/conversations/" + conversation + "/messages")
                        .header("Authorization", "Bearer " + access).contentType("application/json")
                        .content(json.writeValueAsString(request))).andReturn().getResponse().getStatus();
    }

    private SendMessageRequest request() {
        return new SendMessageRequest(UUID.randomUUID(), "TEXT", "Same logical send", null, List.of());
    }

    private void assertStoredOnce(SendMessageRequest request) {
        assertThat(count("SELECT count(*) FROM messages WHERE client_message_id=?", request.clientMessageId())).isEqualTo(1);
    }

    private static final class BeforeCommitFailure extends RuntimeException { }
    private static final class AfterCommitFailure extends RuntimeException { }

    @TestConfiguration(proxyBeanMethods = false)
    static class EventConfiguration {
        @Bean CommittedCreations committedCreations() { return new CommittedCreations(); }
    }

    static class CommittedCreations {
        private final List<MessageCreatedEvent> committed = new CopyOnWriteArrayList<>();
        @TransactionalEventListener public void created(MessageCreatedEvent event) { committed.add(event); }
        List<MessageCreatedEvent> forClient(UUID client) {
            return committed.stream().filter(event -> client.equals(event.message().clientMessageId())).toList();
        }
    }
}
