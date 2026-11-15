package com.nexachat.feature.encryption;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import com.nexachat.core.crypto.E2eeSession;
import com.nexachat.core.network.dto.E2eeDeviceDtos.ConversationE2eeDto;
import com.nexachat.core.network.dto.E2eeDeviceDtos.MemberDeviceDto;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.schedulers.TestScheduler;
import io.reactivex.rxjava3.subjects.SingleSubject;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/**
 * The experimental v1 activation contract: effective allowance is the AND of
 * the compiled client flag and the server capability, blocked activation never
 * reaches the API, explicit disable never requires the allowance, and a policy
 * denial retires the capability without touching the enabled flag.
 */
public final class EncryptionViewModelTest {

    @Rule public final InstantTaskExecutorRule instantTaskExecutorRule =
            new InstantTaskExecutorRule();

    private E2eeSession session;

    @Before
    public void setUp() {
        session = mock(E2eeSession.class);
    }

    private EncryptionViewModel viewModel(boolean clientAllowed) {
        return new EncryptionViewModel(session, clientAllowed, Schedulers.trampoline());
    }

    private static MemberDeviceDto device(String userId, String deviceId) {
        return new MemberDeviceDto(userId, deviceId, "a2V5");
    }

    private static ConversationE2eeDto state(boolean enabled, boolean serverAllowed,
            List<MemberDeviceDto> devices) {
        return new ConversationE2eeDto(enabled, devices, serverAllowed);
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

        verify(session).invalidateDevices("c1");
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
        verify(session, never()).invalidateDevices(anyString());
        EncryptionViewModel.UiState failed = vm.getUiState().getValue();
        assertNotNull(failed);
        assertEquals("E2EE_NETWORK", failed.errorCode);
        assertTrue(failed.activationAllowed);
        assertTrue(failed.enabled);
    }

    @Test
    public void malformedDirectoryRowsAreExcludedAndSuppressTheCode() {
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
        // A partial directory yields no reassuring comparison code.
        assertNull(ui.safetyCode);
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

        verify(session).invalidateDevices("c1");
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
        EncryptionViewModel vm = new EncryptionViewModel(session, true, ui);
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

        verify(session).invalidateDevices("c1");
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
    public void blankDirectoryFieldsAreMalformedAndSuppressTheCode() {
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
        assertNull(ui.safetyCode);
    }

    @Test
    public void completeDirectoryStillComputesTheCode() {
        EncryptionViewModel vm = viewModel(true);
        stubLoad(state(true, true, List.of(device("u1", "dev-a"), device("u2", "dev-b"))));
        vm.load("c1");

        EncryptionViewModel.UiState ui = vm.getUiState().getValue();
        assertNotNull(ui);
        assertNotNull(ui.safetyCode);
    }
}
