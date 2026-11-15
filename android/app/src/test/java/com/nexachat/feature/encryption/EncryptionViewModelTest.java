package com.nexachat.feature.encryption;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import com.nexachat.core.crypto.E2eeSession;
import com.nexachat.core.crypto.KeysetCheck;
import com.nexachat.core.crypto.KeysetTrustStore;
import com.nexachat.core.network.dto.E2eeDeviceDtos.ConversationE2eeDto;
import com.nexachat.core.network.dto.E2eeDeviceDtos.MemberDeviceDto;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.schedulers.TestScheduler;
import io.reactivex.rxjava3.subjects.SingleSubject;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/**
 * The experimental v1 activation contract: effective allowance is the AND of
 * the compiled client flag and the server capability, blocked activation never
 * reaches the API, explicit disable never requires the allowance, and a policy
 * denial retires the capability without touching the enabled flag.
 *
 * <p>Also the verification contract: the NC1 code renders the transcript the
 * session produces, the trust row mirrors the durable record, and "mark as
 * verified" pins exactly the displayed bytes — a roster that changed between
 * display and confirm refreshes the code instead of verifying unseen bytes.
 */
public final class EncryptionViewModelTest {

    @Rule public final InstantTaskExecutorRule instantTaskExecutorRule =
            new InstantTaskExecutorRule();

    private static final byte[] TRANSCRIPT_A = new byte[] {1, 2, 3, 4, 5};
    private static final byte[] TRANSCRIPT_B = new byte[] {9, 8, 7};

    private E2eeSession session;
    private KeysetTrustStore trustStore;

    @Before
    public void setUp() {
        session = mock(E2eeSession.class);
        trustStore = mock(KeysetTrustStore.class);
        when(session.currentUserId()).thenReturn("me-1");
        when(session.currentDeviceId()).thenReturn("dev-a");
        // Default: the roster yields no direct transcript, so no code shows.
        when(session.currentTranscript(anyString())).thenReturn(Single.error(
                new E2eeSession.E2eeException("E2EE_UNSUPPORTED_CONVERSATION")));
        when(trustStore.state(anyString(), anyString()))
                .thenReturn(Single.just(Optional.empty()));
    }

    private EncryptionViewModel viewModel(boolean clientAllowed) {
        return new EncryptionViewModel(
                session, trustStore, clientAllowed, Schedulers.trampoline());
    }

    private static MemberDeviceDto device(String userId, String deviceId) {
        return new MemberDeviceDto(userId, deviceId, "a2V5");
    }

    private static ConversationE2eeDto state(boolean enabled, boolean serverAllowed,
            List<MemberDeviceDto> devices) {
        return new ConversationE2eeDto(enabled, devices, serverAllowed);
    }

    private static KeysetTrustStore.Record record(
            KeysetTrustStore.TrustState state, byte[] transcript) {
        return new KeysetTrustStore.Record(
                state, "00", transcript, null, 1L);
    }

    private void stubLoad(ConversationE2eeDto dto) {
        when(session.e2eeState("c1")).thenReturn(Single.just(dto));
    }

    @Test
    public void activationAllowedOnlyWhenClientAndServerAllow() {
        for (boolean client : new boolean[] {false, true}) {
            for (boolean server : new boolean[] {false, true}) {
                EncryptionViewModel vm = viewModel(client);
                stubLoad(state(false, server, List.of()));
                vm.load("c1");
                EncryptionViewModel.UiState ui = vm.getUiState().getValue();
                assertNotNull(ui);
                assertEquals("client=" + client + " server=" + server,
                        client && server, ui.activationAllowed);
                assertFalse(ui.enabled);
            }
        }
    }

    @Test
    public void blockedActivationIssuesNoNetworkCall() {
        EncryptionViewModel vm = viewModel(false);
        stubLoad(state(false, true, List.of()));
        vm.load("c1");

        vm.setEnabled(true);

        verify(session, never()).setE2ee(anyString(), anyBoolean());
        EncryptionViewModel.UiState ui = vm.getUiState().getValue();
        assertNotNull(ui);
        assertEquals("E2EE_V1_ACTIVATION_DISABLED", ui.errorCode);
        assertFalse(ui.enabled);
        assertFalse(ui.busy);
    }

    @Test
    public void serverFalseCapabilityAlsoBlocksClientSide() {
        EncryptionViewModel vm = viewModel(true);
        stubLoad(state(false, false, List.of()));
        vm.load("c1");

        vm.setEnabled(true);

        verify(session, never()).setE2ee(anyString(), anyBoolean());
        EncryptionViewModel.UiState ui = vm.getUiState().getValue();
        assertNotNull(ui);
        assertFalse(ui.activationAllowed);
    }

    @Test
    public void allowedActivationCallsApiOnce() {
        ConversationE2eeDto enabled = state(true, true, List.of());
        when(session.setE2ee("c1", true)).thenReturn(Single.just(enabled));
        EncryptionViewModel vm = viewModel(true);
        stubLoad(state(false, true, List.of()));
        vm.load("c1");

        vm.setEnabled(true);

        verify(session).setE2ee("c1", true);
        EncryptionViewModel.UiState ui = vm.getUiState().getValue();
        assertNotNull(ui);
        assertTrue(ui.enabled);
        assertNull(ui.errorCode);
    }

    @Test
    public void explicitDisableNeverRequiresAllowance() {
        when(session.setE2ee("c1", false)).thenReturn(Single.just(state(false, false, List.of())));
        EncryptionViewModel vm = viewModel(false);
        stubLoad(state(true, false, List.of()));
        vm.load("c1");
        assertFalse(vm.getUiState().getValue().activationAllowed);

        vm.setEnabled(false);

        verify(session).setE2ee("c1", false);
        EncryptionViewModel.UiState ui = vm.getUiState().getValue();
        assertNotNull(ui);
        assertFalse(ui.enabled);
        assertNull(ui.errorCode);
    }

    @Test
    public void staleEnabledSnapshotCannotBypassClientFlag() {
        // Stale UI: the peer disabled the chat remotely but this client still
        // shows enabled. A redundant enable must fail closed, not hit the API.
        EncryptionViewModel vm = viewModel(false);
        stubLoad(state(true, true, List.of()));
        vm.load("c1");

        vm.setEnabled(true);

        verify(session, never()).setE2ee(anyString(), anyBoolean());
        EncryptionViewModel.UiState ui = vm.getUiState().getValue();
        assertNotNull(ui);
        assertTrue(ui.enabled);
        assertFalse(ui.activationAllowed);
        assertEquals("E2EE_V1_ACTIVATION_DISABLED", ui.errorCode);
    }

    @Test
    public void staleEnabledSnapshotCannotBypassServerCapability() {
        EncryptionViewModel vm = viewModel(true);
        stubLoad(state(true, false, List.of()));
        vm.load("c1");

        vm.setEnabled(true);

        verify(session, never()).setE2ee(anyString(), anyBoolean());
        EncryptionViewModel.UiState ui = vm.getUiState().getValue();
        assertNotNull(ui);
        assertTrue(ui.enabled);
        assertFalse(ui.activationAllowed);
        assertEquals("E2EE_V1_ACTIVATION_DISABLED", ui.errorCode);
    }

    @Test
    public void policyDenialPreservesEnabledClearsCapabilityAndCache() {
        when(session.setE2ee("c1", true)).thenReturn(Single.error(
                new E2eeSession.E2eeException("E2EE_V1_ACTIVATION_DISABLED")));
        EncryptionViewModel vm = viewModel(true);
        stubLoad(state(false, true, List.of()));
        vm.load("c1");
        assertTrue(vm.getUiState().getValue().activationAllowed);

        vm.setEnabled(true);

        // load() invalidates once; the policy denial invalidates again.
        verify(session, times(2)).invalidateDevices("c1");
        EncryptionViewModel.UiState ui = vm.getUiState().getValue();
        assertNotNull(ui);
        assertEquals("E2EE_V1_ACTIVATION_DISABLED", ui.errorCode);
        assertFalse(ui.activationAllowed);
        assertFalse(ui.enabled);
    }

    @Test
    public void policyDenialNeverForcesEnabledFalse() {
        when(session.setE2ee("c1", true)).thenReturn(Single.error(
                new E2eeSession.E2eeException("E2EE_V1_ACTIVATION_DISABLED")));
        EncryptionViewModel vm = viewModel(true);
        // Stale UI: the chat is already enabled but a redundant enable is sent.
        stubLoad(state(true, true, List.of()));
        vm.load("c1");

        vm.setEnabled(true);

        EncryptionViewModel.UiState ui = vm.getUiState().getValue();
        assertNotNull(ui);
        assertTrue(ui.enabled);
        assertFalse(ui.activationAllowed);
    }

    @Test
    public void busyAndErrorCopiesPreserveActivationAllowed() {
        SingleSubject<ConversationE2eeDto> pending = SingleSubject.create();
        when(session.setE2ee("c1", true)).thenReturn(pending);
        EncryptionViewModel vm = viewModel(true);
        stubLoad(state(false, true, List.of()));
        vm.load("c1");

        vm.setEnabled(true);
        EncryptionViewModel.UiState busyState = vm.getUiState().getValue();
        assertNotNull(busyState);
        assertTrue(busyState.busy);
        assertTrue(busyState.activationAllowed);

        pending.onSuccess(state(true, true, List.of()));
        EncryptionViewModel.UiState done = vm.getUiState().getValue();
        assertNotNull(done);
        assertTrue(done.enabled);
        assertTrue(done.activationAllowed);

        // A non-policy failure keeps the capability and the flag; only a
        // policy denial retires them and invalidates the device cache.
        when(session.setE2ee("c1", true)).thenReturn(Single.error(
                new E2eeSession.E2eeException("E2EE_NETWORK")));
        vm.setEnabled(true);
        // Only the load() invalidation; a non-policy failure adds none.
        verify(session, times(1)).invalidateDevices(anyString());
        EncryptionViewModel.UiState failed = vm.getUiState().getValue();
        assertNotNull(failed);
        assertEquals("E2EE_NETWORK", failed.errorCode);
        assertTrue(failed.activationAllowed);
        assertTrue(failed.enabled);
    }

    @Test
    public void malformedDirectoryRowsAreExcluded() {
        List<MemberDeviceDto> devices = new ArrayList<>();
        devices.add(null);
        devices.add(device("u1", "dev-a"));
        devices.add(new MemberDeviceDto("u2", null, "a2V5"));
        devices.add(device("u2", "dev-b"));
        EncryptionViewModel vm = viewModel(true);
        stubLoad(state(true, true, devices));
        vm.load("c1");

        EncryptionViewModel.UiState ui = vm.getUiState().getValue();
        assertNotNull(ui);
        assertEquals(2, ui.devices.size());
    }

    @Test
    public void staleLoadCompletionAfterDenialCannotRestoreCapability() {
        // A load started before the denial must not resurrect the capability:
        // the denial bumps the policy generation, so its delivery is discarded.
        ConversationE2eeDto off = state(false, true, List.of());
        SingleSubject<ConversationE2eeDto> staleLoad = SingleSubject.create();
        when(session.e2eeState("c1")).thenReturn(Single.just(off)).thenReturn(staleLoad);
        when(session.setE2ee("c1", true)).thenReturn(Single.error(
                new E2eeSession.E2eeException("E2EE_V1_ACTIVATION_DISABLED")));
        EncryptionViewModel vm = viewModel(true);
        vm.load("c1");
        assertTrue(vm.getUiState().getValue().activationAllowed);
        vm.load("c1");                       // still in flight when the denial lands
        vm.setEnabled(true);

        // Two loads + the policy denial each invalidate the device cache.
        verify(session, times(3)).invalidateDevices("c1");
        staleLoad.onSuccess(off);            // stale completion arrives afterwards

        EncryptionViewModel.UiState ui = vm.getUiState().getValue();
        assertNotNull(ui);
        assertFalse(ui.activationAllowed);
        assertEquals("E2EE_V1_ACTIVATION_DISABLED", ui.errorCode);
        assertFalse(ui.enabled);
        // Follow-up activation is still blocked locally: only the original call.
        vm.setEnabled(true);
        verify(session).setE2ee(anyString(), anyBoolean());
    }

    @Test
    public void queuedPreDenialCallbacksDiscardOnUiSchedulerDelivery() {
        // The fence lives at state publication on the UI scheduler, not at the
        // upstream emission: a stale success queued behind the denial is dropped.
        TestScheduler ui = new TestScheduler();
        EncryptionViewModel vm = new EncryptionViewModel(session, trustStore, true, ui);
        ConversationE2eeDto off = state(false, true, List.of());
        SingleSubject<ConversationE2eeDto> staleLoad = SingleSubject.create();
        SingleSubject<ConversationE2eeDto> toggle = SingleSubject.create();
        when(session.e2eeState("c1")).thenReturn(Single.just(off)).thenReturn(staleLoad);
        when(session.setE2ee("c1", true)).thenReturn(toggle);

        vm.load("c1");
        ui.triggerActions();                 // deliver: cap=true established
        assertTrue(vm.getUiState().getValue().activationAllowed);
        vm.load("c1");                       // pending second load
        vm.setEnabled(true);                 // pending toggle
        toggle.onError(new E2eeSession.E2eeException("E2EE_V1_ACTIVATION_DISABLED"));
        staleLoad.onSuccess(off);            // queued behind the denial
        ui.triggerActions();                 // flush UI queue

        // Two loads + the policy denial each invalidate the device cache.
        verify(session, times(3)).invalidateDevices("c1");
        EncryptionViewModel.UiState state = vm.getUiState().getValue();
        assertNotNull(state);
        assertFalse(state.activationAllowed);
        assertEquals("E2EE_V1_ACTIVATION_DISABLED", state.errorCode);
        assertFalse(state.enabled);
        vm.setEnabled(true);
        verify(session).setE2ee(anyString(), anyBoolean());
    }

    @Test
    public void denialLatchSurvivesFreshCacheHitAndDisableStaysAllowed() {
        // A fresh same-conversation load can hit the stale session cache that
        // still reports cap=true. The denial latch must win; enabled survives.
        ConversationE2eeDto off = state(false, true, List.of());
        SingleSubject<ConversationE2eeDto> freshLoad = SingleSubject.create();
        when(session.e2eeState("c1")).thenReturn(Single.just(off)).thenReturn(freshLoad);
        when(session.setE2ee("c1", true)).thenReturn(Single.error(
                new E2eeSession.E2eeException("E2EE_V1_ACTIVATION_DISABLED")));
        when(session.setE2ee("c1", false)).thenReturn(Single.just(off));
        EncryptionViewModel vm = viewModel(true);
        vm.load("c1");
        vm.setEnabled(true);                 // server policy denial

        vm.load("c1");                       // fresh same-conversation load
        freshLoad.onSuccess(state(true, true, List.of()));
        EncryptionViewModel.UiState ui = vm.getUiState().getValue();
        assertNotNull(ui);
        assertTrue(ui.enabled);              // enabled comes from the DTO
        assertFalse(ui.activationAllowed);   // ...but the latch keeps cap off

        vm.setEnabled(false);                // explicit disable never gated
        verify(session).setE2ee("c1", false);
    }

    @Test
    public void differentConversationResetsLatchAndDropsStaleCallbacks() {
        ConversationE2eeDto off = state(false, true, List.of());
        SingleSubject<ConversationE2eeDto> pendingC1 = SingleSubject.create();
        when(session.e2eeState("c1")).thenReturn(Single.just(off)).thenReturn(pendingC1);
        when(session.e2eeState("c2")).thenReturn(Single.just(off));
        when(session.setE2ee("c1", true)).thenReturn(Single.error(
                new E2eeSession.E2eeException("E2EE_V1_ACTIVATION_DISABLED")));
        EncryptionViewModel vm = viewModel(true);
        vm.load("c1");
        vm.load("c1");                       // pending load for c1
        vm.setEnabled(true);                 // denial on c1
        assertFalse(vm.getUiState().getValue().activationAllowed);

        vm.load("c2");                       // different conversation resets
        EncryptionViewModel.UiState ui = vm.getUiState().getValue();
        assertNotNull(ui);
        assertTrue(ui.activationAllowed);

        pendingC1.onSuccess(state(true, true, List.of()));
        EncryptionViewModel.UiState after = vm.getUiState().getValue();
        assertNotNull(after);
        assertTrue(after.activationAllowed); // stale c1 callback cannot touch c2
        assertFalse(after.enabled);
    }

    @Test
    public void blankDirectoryFieldsAreMalformedAndExcluded() {
        List<MemberDeviceDto> devices = new ArrayList<>();
        devices.add(new MemberDeviceDto(" ", "dev-x", "a2V5"));
        devices.add(new MemberDeviceDto("u1", "\t", "a2V5"));
        devices.add(new MemberDeviceDto("u2", "dev-y", ""));
        devices.add(device("u1", "dev-a"));
        devices.add(device("u2", "dev-b"));
        EncryptionViewModel vm = viewModel(true);
        stubLoad(state(true, true, devices));
        vm.load("c1");

        EncryptionViewModel.UiState ui = vm.getUiState().getValue();
        assertNotNull(ui);
        assertEquals(2, ui.devices.size());
    }

    @Test
    public void noTranscriptMeansNoVerificationCard() {
        EncryptionViewModel vm = viewModel(true);
        stubLoad(state(true, true, List.of(device("u1", "dev-a"), device("u2", "dev-b"))));
        vm.load("c1");

        EncryptionViewModel.UiState ui = vm.getUiState().getValue();
        assertNotNull(ui);
        assertNull(ui.verificationCode);
        assertEquals(EncryptionViewModel.Trust.NOT_VERIFIED, ui.trust);
    }

    @Test
    public void displayedCodeIsTheNc1CodeOverTheTranscript() {
        when(session.currentTranscript("c1")).thenReturn(Single.just(TRANSCRIPT_A));
        EncryptionViewModel vm = viewModel(true);
        stubLoad(state(true, true, List.of(device("u1", "dev-a"), device("u2", "dev-b"))));
        vm.load("c1");

        EncryptionViewModel.UiState ui = vm.getUiState().getValue();
        assertNotNull(ui);
        assertEquals(KeysetCheck.displayCode(KeysetCheck.digest(TRANSCRIPT_A)),
                ui.verificationCode);
        assertEquals(EncryptionViewModel.Trust.NOT_VERIFIED, ui.trust);
    }

    @Test
    public void verifiedRecordMapsToVerifiedState() {
        when(session.currentTranscript("c1")).thenReturn(Single.just(TRANSCRIPT_A));
        when(trustStore.state("me-1", "c1")).thenReturn(Single.just(
                Optional.of(record(KeysetTrustStore.TrustState.VERIFIED, TRANSCRIPT_A))));
        EncryptionViewModel vm = viewModel(true);
        stubLoad(state(true, true, List.of(device("u1", "dev-a"), device("u2", "dev-b"))));
        vm.load("c1");

        EncryptionViewModel.UiState ui = vm.getUiState().getValue();
        assertNotNull(ui);
        assertEquals(EncryptionViewModel.Trust.VERIFIED, ui.trust);
    }

    @Test
    public void changedRecordMapsToChangedStateEvenWithoutChangedDigest() {
        // A roster-shape change records CHANGED with a null changed digest;
        // the state row must still say "compare again".
        when(session.currentTranscript("c1")).thenReturn(Single.just(TRANSCRIPT_A));
        when(trustStore.state("me-1", "c1")).thenReturn(Single.just(
                Optional.of(record(KeysetTrustStore.TrustState.CHANGED, TRANSCRIPT_B))));
        EncryptionViewModel vm = viewModel(true);
        stubLoad(state(true, true, List.of(device("u1", "dev-a"), device("u2", "dev-b"))));
        vm.load("c1");

        EncryptionViewModel.UiState ui = vm.getUiState().getValue();
        assertNotNull(ui);
        assertEquals(EncryptionViewModel.Trust.CHANGED, ui.trust);
    }

    @Test
    public void markVerifiedPinsExactlyTheDisplayedTranscript() {
        when(session.currentTranscript("c1")).thenReturn(Single.just(TRANSCRIPT_A));
        when(session.markVerified(eq("c1"), aryEq(TRANSCRIPT_A)))
                .thenReturn(Completable.complete());
        EncryptionViewModel vm = viewModel(true);
        stubLoad(state(true, true, List.of(device("u1", "dev-a"), device("u2", "dev-b"))));
        vm.load("c1");
        assertNotNull(vm.getUiState().getValue().verificationCode);

        vm.confirmVerified();

        // The bytes pinned are the displayed ones — the re-read only guards
        // that the roster has not moved; it is never the verified value.
        verify(session).markVerified(eq("c1"), aryEq(TRANSCRIPT_A));
        EncryptionViewModel.UiState ui = vm.getUiState().getValue();
        assertNotNull(ui);
        assertEquals(EncryptionViewModel.Trust.VERIFIED, ui.trust);
        assertFalse(ui.verifyBusy);
    }

    @Test
    public void rosterChangeBeforeConfirmRefreshesAndNeverVerifies() {
        // Display commits to TRANSCRIPT_A; the roster produces B at confirm.
        when(session.currentTranscript("c1"))
                .thenReturn(Single.just(TRANSCRIPT_A))
                .thenReturn(Single.just(TRANSCRIPT_B));
        EncryptionViewModel vm = viewModel(true);
        stubLoad(state(true, true, List.of(device("u1", "dev-a"), device("u2", "dev-b"))));
        vm.load("c1");
        String shown = vm.getUiState().getValue().verificationCode;
        assertEquals(KeysetCheck.displayCode(KeysetCheck.digest(TRANSCRIPT_A)), shown);

        vm.confirmVerified();

        verify(session, never()).markVerified(anyString(), any(byte[].class));
        EncryptionViewModel.UiState ui = vm.getUiState().getValue();
        assertNotNull(ui);
        assertEquals("E2EE_KEYSET_CHANGED", ui.errorCode);
        assertFalse(ui.verifyBusy);
        // The refreshed code commits to the roster now in force, not to A.
        assertEquals(KeysetCheck.displayCode(KeysetCheck.digest(TRANSCRIPT_B)),
                ui.verificationCode);
    }

    @Test
    public void confirmWithoutADisplayedCodeIsANoOp() {
        EncryptionViewModel vm = viewModel(true);
        stubLoad(state(true, true, List.of(device("u1", "dev-a"), device("u2", "dev-b"))));
        vm.load("c1");
        assertNull(vm.getUiState().getValue().verificationCode);

        vm.confirmVerified();

        verify(session, never()).markVerified(anyString(), any(byte[].class));
    }

    @Test
    public void unreadableTrustStoreRendersUnavailableNotAVerdict() {
        // A DAO failure is not an empty record: the code still renders but the
        // state row reports the unreadable state rather than "not verified".
        when(session.currentTranscript("c1")).thenReturn(Single.just(TRANSCRIPT_A));
        when(trustStore.state("me-1", "c1"))
                .thenReturn(Single.error(new RuntimeException("dao down")));
        EncryptionViewModel vm = viewModel(true);
        stubLoad(state(true, true, List.of(device("u1", "dev-a"), device("u2", "dev-b"))));
        vm.load("c1");

        EncryptionViewModel.UiState ui = vm.getUiState().getValue();
        assertNotNull(ui);
        assertEquals(EncryptionViewModel.Trust.UNAVAILABLE, ui.trust);
        assertEquals(KeysetCheck.displayCode(KeysetCheck.digest(TRANSCRIPT_A)),
                ui.verificationCode);
    }

    @Test
    public void confirmVerifiedIsRefusedWhileTrustStateUnreadable() {
        // A DAO failure must not become "not verified" and accept a verdict.
        when(session.currentTranscript("c1")).thenReturn(Single.just(TRANSCRIPT_A));
        when(trustStore.state("me-1", "c1"))
                .thenReturn(Single.error(new RuntimeException("dao down")));
        EncryptionViewModel vm = viewModel(true);
        stubLoad(state(true, true, List.of(device("u1", "dev-a"), device("u2", "dev-b"))));
        vm.load("c1");
        assertEquals(EncryptionViewModel.Trust.UNAVAILABLE,
                vm.getUiState().getValue().trust);

        vm.confirmVerified();

        verify(session, never()).markVerified(anyString(), any(byte[].class));
        EncryptionViewModel.UiState ui = vm.getUiState().getValue();
        assertNotNull(ui);
        assertFalse(ui.verifyBusy);
    }
}
