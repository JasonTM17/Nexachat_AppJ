package com.nexachat.call;

import com.nexachat.auth.RateLimitService;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.conversation.Conversation;
import com.nexachat.conversation.ConversationMemberRepository;
import com.nexachat.conversation.ConversationRepository;
import com.nexachat.message.CallSystemMessageWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Service
public class CallService {

    private static final long SDP_LIMIT = 16 * 1024;
    private static final long ICE_LIMIT = 1024;
    private static final int HISTORY_MAX_LIMIT = 100;

    private final CallRegistry registry;
    private final CallRecordRepository recordRepository;
    private final ConversationRepository conversationRepository;
    private final ConversationMemberRepository memberRepository;
    private final RateLimitService rateLimitService;
    private final CallSystemMessageWriter systemMessageWriter;
    private final ApplicationEventPublisher eventPublisher;
    private final CallIceConfig iceConfig;
    private final ReentrantReadWriteLock registryAccessLock = new ReentrantReadWriteLock(true);
    private final ThreadLocal<MutationScope> mutationScope = new ThreadLocal<>();

    public CallService(CallRegistry registry,
                       @Lazy CallRecordRepository recordRepository,
                       ConversationRepository conversationRepository,
                       ConversationMemberRepository memberRepository,
                       RateLimitService rateLimitService,
                       CallSystemMessageWriter systemMessageWriter,
                       ApplicationEventPublisher eventPublisher,
                       @Value("${nexachat.calls.ice-servers:stun:stun.l.google.com:19302}") String iceServers) {
        this.registry = registry;
        this.recordRepository = recordRepository;
        this.conversationRepository = conversationRepository;
        this.memberRepository = memberRepository;
        this.rateLimitService = rateLimitService;
        this.systemMessageWriter = systemMessageWriter;
        this.eventPublisher = eventPublisher;
        List<String> urls = List.of(iceServers.split(",")).stream()
                .map(String::trim).filter(url -> !url.isEmpty()).toList();
        if (urls.isEmpty()) {
            throw new IllegalArgumentException("At least one ICE server URL is required");
        }
        this.iceConfig = new CallIceConfig(List.of(new CallIceConfig.IceServer(urls)), 3600);
    }

    @Transactional
    public void signal(UUID conversationId, UUID actorId, CallSignal signal) {
        validateEnvelope(signal);
        if (signal.type() == CallSignal.Type.SDP || signal.type() == CallSignal.Type.ICE) {
            withRegistryRead(() -> relay(conversationId, actorId, signal));
            return;
        }
        withRegistryMutation(() -> {
            switch (signal.type()) {
                case INVITE -> invite(conversationId, actorId, signal);
                case ACCEPT -> accept(conversationId, actorId, signal);
                case REJECT -> terminate(conversationId, actorId, signal, CallEndReason.REJECTED,
                        "CALL_REJECTED");
                case CANCEL -> terminate(conversationId, actorId, signal, CallEndReason.CANCELLED,
                        "CALL_CANCELLED");
                case END -> terminate(conversationId, actorId, signal, CallEndReason.COMPLETED,
                        "CALL_ENDED");
                case SDP, ICE -> throw new IllegalStateException("Media frame entered the mutation path");
            }
        });
    }

    private void invite(UUID conversationId, UUID callerId, CallSignal signal) {
        UUID calleeId = directPeer(conversationId, callerId);
        consume(callerId, "invite", 20);
        CallRegistry.Invitation invitation = registry.invite(signal.callId(), conversationId,
                callerId, calleeId, registry.now());
        recordRollback(() -> registry.rollback(invitation));
        CallSession call = invitation.session();
        if (invitation.busy()) {
            persistTerminal(call);
            deliver(callerId, event("CALL_BUSY", call, calleeId, signal,
                    CallEndReason.BUSY, call.durationSeconds()));
            return;
        }
        persist(call);
        if (signal.sdp() != null) {
            registry.saveOffer(signal.callId(), signal.sdp());
        }
        eventPublisher.publishEvent(new CallRingingEvent(call.callId()));
        deliver(call.calleeId(), event("CALL_INVITE", call, call.callerId(), signal, null, null));
    }

    private void accept(UUID conversationId, UUID actorId, CallSignal signal) {
        CallRegistry.Transition transition = registry.accept(
                signal.callId(), conversationId, actorId, registry.now());
        recordRollback(() -> registry.rollback(transition));
        CallSession call = transition.session();
        if (transition.timedOut()) {
            finish(call, "CALL_TIMEOUT", call.callerId());
            return;
        }
        persist(call);
        eventPublisher.publishEvent(new CallTerminalEvent(call.callId()));
        deliver(call.callerId(), event("CALL_ACCEPTED", call, call.calleeId(), signal, null, null));
        deliver(call.calleeId(), event("CALL_ACCEPTED", call, call.callerId(), signal, null, null));
    }

    private void terminate(UUID conversationId, UUID actorId, CallSignal signal,
                           CallEndReason reason, String eventType) {
        CallRegistry.Transition transition = switch (reason) {
            case REJECTED -> registry.reject(signal.callId(), conversationId, actorId, registry.now());
            case CANCELLED -> registry.cancel(signal.callId(), conversationId, actorId, registry.now());
            case COMPLETED -> registry.end(signal.callId(), conversationId, actorId, registry.now());
            default -> throw new IllegalStateException("Unsupported call action");
        };
        recordRollback(() -> registry.rollback(transition));
        CallSession call = transition.session();
        finish(call, eventType, actorId);
    }

    private void relay(UUID conversationId, UUID actorId, CallSignal signal) {
        CallSession call = registry.requireParticipant(signal.callId(), conversationId, actorId);
        UUID peerId = registry.mediaPeer(signal.callId(), conversationId, actorId);
        if (signal.type() == CallSignal.Type.SDP) {
            consume(actorId, "media", 300);
            if (signal.sdp() == null || signal.sdp().type() == null
                    || !("offer".equals(signal.sdp().type()) || "answer".equals(signal.sdp().type()))
                    || signal.sdp().description() == null || signal.sdp().description().isBlank()
                    || exceedsUtf8Limit(signal.sdp().description(), SDP_LIMIT)) {
                throw CallException.validation("Invalid or oversized SDP payload");
            }
        } else {
            consume(actorId, "media", 300);
            if (signal.candidate() == null || signal.candidate().candidate() == null
                    || signal.candidate().candidate().isBlank()
                    || exceedsUtf8Limit(signal.candidate().candidate(), ICE_LIMIT)
                    || (signal.candidate().sdpMid() != null && signal.candidate().sdpMid().length() > 256)
                    || (signal.candidate().sdpMLineIndex() != null
                    && signal.candidate().sdpMLineIndex() < 0)) {
                throw CallException.validation("Invalid or oversized ICE candidate");
            }
        }
        String eventType = signal.type() == CallSignal.Type.SDP ? "CALL_SDP" : "CALL_ICE";
        deliver(peerId, event(eventType, call, actorId, signal, null, null));
    }

    @Transactional
    public void timeout(UUID callId) {
        withRegistryMutation(() -> registry.timeout(callId, registry.now()).ifPresent(transition -> {
            recordRollback(() -> registry.rollback(transition));
            finish(transition.session(), "CALL_TIMEOUT", transition.session().callerId());
        }));
    }

    @Transactional
    public void disconnect(UUID userId) {
        withRegistryMutation(() -> registry.failForUser(userId, registry.now()).ifPresent(transition -> {
            recordRollback(() -> registry.rollback(transition));
            finish(transition.session(), "CALL_ENDED", userId);
        }));
    }

    public CallIceConfig iceConfig() {
        return iceConfig;
    }

    @Transactional(readOnly = true)
    public CallHistoryPage history(UUID userId, UUID conversationId, int limit, String rawCursor) {
        if (limit < 1 || limit > HISTORY_MAX_LIMIT) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "limit must be between 1 and 100");
        }
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> ApiException.of(ApiErrorCode.CONVERSATION_NOT_FOUND, "Conversation not found"));
        if (!memberRepository.existsByConversation_IdAndUser_Id(conversationId, userId)) {
            throw ApiException.of(ApiErrorCode.NOT_MEMBER, "Conversation member required");
        }
        if (conversation.getType() != Conversation.Type.DIRECT) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "Call history is available for direct conversations");
        }

        CallHistoryCursor cursor = rawCursor == null || rawCursor.isBlank()
                ? null : decodeCursor(rawCursor);
        var records = cursor == null
                ? recordRepository.findConversationPage(conversationId, PageRequest.of(0, limit + 1))
                : recordRepository.findConversationPageAfter(conversationId,
                        cursor.startedAt(), cursor.callId(), PageRequest.of(0, limit + 1));
        boolean hasMore = records.size() > limit;
        List<CallRecord> page = hasMore ? records.subList(0, limit) : records;
        String nextCursor = hasMore ? CallHistoryCursor.encode(page.get(page.size() - 1)) : null;
        return new CallHistoryPage(page.stream().map(CallHistoryItem::from).toList(), nextCursor);
    }

    @Transactional(readOnly = true)
    public CallOfferResponse getOffer(UUID userId, UUID callId) {
        CallSession session = registry.find(callId)
                .orElseThrow(CallException::notFound);
        if (session.peerOf(userId) == null) {
            throw CallException.notMember();
        }
        CallSignal.Sdp sdp = registry.findOffer(callId)
                .orElseThrow(CallException::notFound);
        return new CallOfferResponse(callId, sdp, iceConfig);
    }

    private CallHistoryCursor decodeCursor(String cursor) {
        try {
            return CallHistoryCursor.decode(cursor);
        } catch (CallException exception) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "Invalid call history cursor");
        }
    }

    private void finish(CallSession call, String eventType, UUID fromUserId) {
        persistTerminal(call);
        CallEvent callEvent = event(eventType, call, fromUserId, null, call.endReason(), call.durationSeconds());
        deliver(call.callerId(), callEvent);
        if (!call.calleeId().equals(call.callerId())) {
            deliver(call.calleeId(), callEvent);
        }
    }

    private void persistTerminal(CallSession call) {
        persist(call);
        systemMessageWriter.write(call);
        eventPublisher.publishEvent(new CallTerminalEvent(call.callId()));
    }

    private void persist(CallSession call) {
        recordRepository.save(CallRecord.from(call));
    }

    private CallEvent event(String eventType, CallSession call, UUID fromUserId, CallSignal signal,
                            CallEndReason reason, Long durationSeconds) {
        CallEvent.Payload payload = new CallEvent.Payload(
                signal == null ? null : signal.type().name(), call.callId(),
                signal == null ? null : signal.sdp(), signal == null ? null : signal.candidate(),
                reason == null ? null : reason.name(), durationSeconds);
        return new CallEvent(eventType, call.callId(), call.conversationId(), fromUserId,
                registry.now(), payload);
    }

    private void deliver(UUID recipientId, CallEvent event) {
        eventPublisher.publishEvent(new CallDelivery(recipientId, event));
    }

    private void withRegistryMutation(Runnable operation) {
        MutationLease lease = beginMutationScope();
        try {
            operation.run();
        } catch (RuntimeException | Error failure) {
            if (!lease.transactionManaged) {
                lease.scope.rollback();
            }
            throw failure;
        } finally {
            if (lease.releaseOnExit) {
                if (lease.removeOnExit) {
                    mutationScope.remove();
                }
                registryAccessLock.writeLock().unlock();
            }
        }
    }

    private void withRegistryRead(Runnable operation) {
        Lock lock = registryAccessLock.readLock();
        lock.lock();
        boolean transactionManaged = deferUnlockUntilCompletion(lock, null);
        try {
            operation.run();
        } finally {
            if (!transactionManaged) {
                lock.unlock();
            }
        }
    }

    private MutationLease beginMutationScope() {
        registryAccessLock.writeLock().lock();
        MutationScope existing = mutationScope.get();
        if (existing != null) {
            return new MutationLease(existing, existing.transactionManaged, true, false);
        }
        boolean transactionManaged = TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive();
        MutationScope scope = new MutationScope(transactionManaged);
        mutationScope.set(scope);
        if (transactionManaged) {
            try {
                deferUnlockUntilCompletion(registryAccessLock.writeLock(), scope);
            } catch (RuntimeException | Error failure) {
                mutationScope.remove();
                throw failure;
            }
            return new MutationLease(scope, true, false, false);
        }
        return new MutationLease(scope, false, true, true);
    }

    private boolean deferUnlockUntilCompletion(Lock lock, MutationScope scope) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()
                || !TransactionSynchronizationManager.isActualTransactionActive()) {
            return false;
        }
        try {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    try {
                        if (scope != null && status != STATUS_COMMITTED) {
                            scope.rollback();
                        }
                    } finally {
                        if (scope != null && mutationScope.get() == scope) {
                            mutationScope.remove();
                        }
                        lock.unlock();
                    }
                }
            });
        } catch (RuntimeException | Error failure) {
            lock.unlock();
            throw failure;
        }
        return true;
    }

    private void recordRollback(Runnable rollback) {
        MutationScope scope = mutationScope.get();
        if (scope == null) {
            throw new IllegalStateException("Call registry mutation scope is missing");
        }
        scope.rollbacks.add(rollback);
    }

    private UUID directPeer(UUID conversationId, UUID callerId) {
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(CallException::notMember);
        if (conversation.getType() != Conversation.Type.DIRECT) {
            throw CallException.notMember();
        }
        List<UUID> memberIds = memberRepository.findMemberUserIds(conversationId);
        if (memberIds.size() != 2 || !memberIds.contains(callerId)) {
            throw CallException.notMember();
        }
        UUID peerId = memberIds.get(0).equals(callerId) ? memberIds.get(1) : memberIds.get(0);
        if (callerId.equals(peerId)) {
            throw CallException.validation("Cannot invite yourself");
        }
        return peerId;
    }

    private void consume(UUID userId, String action, long capacity) {
        if (!rateLimitService.tryConsume("call:" + action + ":" + userId,
                capacity, Duration.ofMinutes(1)).allowed()) {
            throw CallException.rateLimited();
        }
    }

    private static void validateEnvelope(CallSignal signal) {
        if (signal == null || signal.type() == null || signal.callId() == null) {
            throw CallException.validation("type and callId are required");
        }
        if (signal.type() == CallSignal.Type.INVITE) {
            if (signal.sdp() != null || signal.candidate() != null) {
                throw CallException.validation("INVITE cannot include SDP or ICE data");
            }
        } else if (signal.type() == CallSignal.Type.SDP && signal.candidate() != null) {
            throw CallException.validation("SDP cannot include an ICE candidate");
        } else if (signal.type() == CallSignal.Type.ICE && signal.sdp() != null) {
            throw CallException.validation("ICE cannot include SDP");
        } else if (signal.type() != CallSignal.Type.SDP && signal.type() != CallSignal.Type.ICE
                && (signal.sdp() != null || signal.candidate() != null)) {
            throw CallException.validation("Call control frames cannot include SDP or ICE");
        }
    }

    private static boolean exceedsUtf8Limit(String value, long limit) {
        return value.length() > limit || value.getBytes(StandardCharsets.UTF_8).length > limit;
    }

    private static final class MutationScope {
        private final boolean transactionManaged;
        private final List<Runnable> rollbacks = new ArrayList<>();
        private boolean rolledBack;

        private MutationScope(boolean transactionManaged) {
            this.transactionManaged = transactionManaged;
        }

        private void rollback() {
            if (rolledBack) {
                return;
            }
            rolledBack = true;
            for (int index = rollbacks.size() - 1; index >= 0; index--) {
                rollbacks.get(index).run();
            }
        }
    }

    private record MutationLease(MutationScope scope, boolean transactionManaged,
                                 boolean releaseOnExit, boolean removeOnExit) {
    }
}
