package com.nexachat.message;

import com.nexachat.conversation.ConcurrentDatabaseITSupport;
import com.nexachat.conversation.dto.AddMemberRequest;
import com.nexachat.invite.InviteService;
import com.nexachat.invite.dto.CreateInviteLinkRequest;
import com.nexachat.message.dto.MessageResponse;
import com.nexachat.message.dto.SendMessageRequest;
import com.nexachat.message.dto.SyncRequest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

/** Positive membership boundaries with untouched timestamps from actual application writes. */
class SyncMembershipTimestampIT extends ConcurrentDatabaseITSupport {
    @Autowired private MessageService messages;
    @Autowired private InviteService invites;
    @Autowired private PlatformTransactionManager transactions;
    enum Admission { CREATE, ADD, APPROVE }

    @ParameterizedTest
    @CsvSource({"1,CREATE", "2,CREATE", "1,ADD", "2,ADD", "1,APPROVE", "2,APPROVE"})
    void aNewMemberCanSyncTheSystemMessageWrittenByItsAdmission(int version, Admission admission) {
        var owner = user("Owner");
        var reader = user("Reader");
        UUID conversation;
        if (admission == Admission.CREATE) {
            conversation = group(owner, reader);
        } else {
            conversation = group(owner, user("Existing member"));
            if (admission == Admission.ADD) {
                groups.addMembers(owner.getId(), conversation, new AddMemberRequest(List.of(reader.getId())));
            } else {
                var link = invites.createOrRotateInviteLink(owner.getId(), conversation,
                        new CreateInviteLinkRequest(null, 1, true));
                var pending = invites.join(reader.getId(), link.token(), clientIp);
                invites.approveJoinRequest(owner.getId(), conversation, pending.requestId());
            }
        }
        UUID admissionMessage = jdbc.queryForObject(
                "SELECT id FROM messages WHERE conversation_id=? ORDER BY seq DESC LIMIT 1", UUID.class, conversation);
        assertThat(sync(reader.getId(), conversation, version)).extracting(MessageResponse::id).contains(admissionMessage);
    }

    @ParameterizedTest
    @CsvSource({"1,false", "2,false", "1,true", "2,true"})
    void aTransactionStartedBeforeRejoinTimestampsTheSendAfterAdmission(int version, boolean mentionAll) throws Exception {
        var owner = user("Owner");
        var reader = user("Reader");
        UUID conversation = group(owner, reader);
        groups.removeMember(owner.getId(), conversation, reader.getId());
        CountDownLatch transactionStarted = new CountDownLatch(1);
        CountDownLatch rejoined = new CountDownLatch(1);
        try (Workers workers = new Workers()) {
            var waitingSend = workers.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                // Establish the old PostgreSQL transaction timestamp without taking any membership lock.
                jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", Timestamp.class);
                transactionStarted.countDown();
                await(rejoined);
                return messages.send(owner.getId(), conversation, new SendMessageRequest(UUID.randomUUID(),
                        "TEXT", "Written after rejoin", null, List.of(), List.of(), mentionAll)).message();
            }));
            try {
                await(transactionStarted);
                groups.addMembers(owner.getId(), conversation, new AddMemberRequest(List.of(reader.getId())));
                rejoined.countDown();
                MessageResponse sent = result(waitingSend);
                Timestamp joined = jdbc.queryForObject(
                        "SELECT joined_at FROM conversation_members WHERE conversation_id=? AND user_id=?",
                        Timestamp.class, conversation, reader.getId());
                assertThat(sent.createdAt()).isAfterOrEqualTo(joined.toInstant());
                assertThat(sync(reader.getId(), conversation, version)).extracting(MessageResponse::id).contains(sent.id());
            } finally {
                rejoined.countDown();
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"1,-60", "2,-60", "1,60", "2,60"})
    void aSkewedApplicationClockCannotExposeTheMembershipGap(int version, long clockOffsetSeconds) {
        var owner = user("Owner");
        var reader = user("Reader");
        UUID conversation = group(owner, reader);
        groups.removeMember(owner.getId(), conversation, reader.getId());
        MessageResponse hidden = messages.send(owner.getId(), conversation, new SendMessageRequest(
                UUID.randomUUID(), "TEXT", "Private during removal", null, List.of())).message();

        Instant skewed = Instant.now().plusSeconds(clockOffsetSeconds);
        // Change only application time during real admission; PostgreSQL and message timestamps stay real.
        try (var applicationClock = mockStatic(Instant.class, CALLS_REAL_METHODS)) {
            applicationClock.when(Instant::now).thenReturn(skewed);
            groups.addMembers(owner.getId(), conversation, new AddMemberRequest(List.of(reader.getId())));
        }
        UUID admissionMessage = jdbc.queryForObject(
                "SELECT id FROM messages WHERE conversation_id=? ORDER BY seq DESC LIMIT 1", UUID.class, conversation);
        MessageResponse visible = messages.send(owner.getId(), conversation, new SendMessageRequest(
                UUID.randomUUID(), "TEXT", "Visible after rejoin", null, List.of())).message();

        assertThat(sync(reader.getId(), conversation, version)).extracting(MessageResponse::id)
                .contains(admissionMessage, visible.id()).doesNotContain(hidden.id());
    }

    private List<MessageResponse> sync(UUID user, UUID conversation, int version) {
        var delta = messages.syncDelta(user, new SyncRequest(List.of(new SyncRequest.ConversationWatermark(
                conversation, 0L, null, version == 2 ? 0L : null, null)), 50, null, version)).deltas().getFirst();
        return version == 2 ? delta.changes() : delta.newMessages();
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(15, TimeUnit.SECONDS)).as("explicit transaction/admission interleaving reached").isTrue();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }
}
