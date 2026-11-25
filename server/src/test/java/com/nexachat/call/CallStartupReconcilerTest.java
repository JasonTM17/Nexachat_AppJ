package com.nexachat.call;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CallStartupReconcilerTest {

    @Test
    void leavesHistoryUntouchedWhenNothingIsOrphaned() {
        CallRecordRepository repository = mock(CallRecordRepository.class);
        when(repository.findByStateIn(any())).thenReturn(List.of());
        CallStartupReconciler reconciler = new CallStartupReconciler(repository);

        assertThat(reconciler.closeOrphanedCalls()).isZero();
        verify(repository, never()).saveAll(any());
    }

    @Test
    void closesOrphanedRowsThroughTheEntityTerminalFields() {
        CallSession session = mock(CallSession.class);
        when(session.callId()).thenReturn(java.util.UUID.randomUUID());
        when(session.conversationId()).thenReturn(java.util.UUID.randomUUID());
        when(session.callerId()).thenReturn(java.util.UUID.randomUUID());
        when(session.calleeId()).thenReturn(java.util.UUID.randomUUID());
        when(session.mediaType()).thenReturn(CallMediaType.AUDIO);
        when(session.state()).thenReturn(CallState.ACTIVE);
        when(session.answeredAt()).thenReturn(java.time.Instant.now().minusSeconds(90));
        when(session.startedAt()).thenReturn(java.time.Instant.now().minusSeconds(120));
        CallRecord orphaned = CallRecord.from(session);

        CallRecordRepository repository = mock(CallRecordRepository.class);
        when(repository.findByStateIn(any())).thenReturn(List.of(orphaned));
        CallStartupReconciler reconciler = new CallStartupReconciler(repository);

        int closed = reconciler.closeOrphanedCalls();

        assertThat(closed).isEqualTo(1);
        assertThat(orphaned.getState()).isEqualTo(CallState.ENDED);
        assertThat(orphaned.getEndReason()).isEqualTo(CallEndReason.FAILED);
        assertThat(orphaned.getEndedAt()).isNotNull();
        assertThat(orphaned.getDurationSeconds()).isNotNull().isGreaterThanOrEqualTo(0L);
        verify(repository).saveAll(List.of(orphaned));
    }
}
