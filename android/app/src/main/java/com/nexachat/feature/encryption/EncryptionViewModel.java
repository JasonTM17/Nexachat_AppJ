package com.nexachat.feature.encryption;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import com.nexachat.BuildConfig;
import com.nexachat.core.crypto.E2eeSession;
import com.nexachat.core.crypto.SafetyCode;
import com.nexachat.core.network.dto.E2eeDeviceDtos.ConversationE2eeDto;
import com.nexachat.core.network.dto.E2eeDeviceDtos.MemberDeviceDto;
import dagger.hilt.android.lifecycle.HiltViewModel;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Scheduler;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;

/**
 * Backs the encryption screen: loads the conversation's e2ee flag and member
 * device list, computes the safety code, and applies the toggle through
 * {@link E2eeSession}. Server error codes are surfaced verbatim so the
 * fragment can map them to localized copy.
 *
 * <p>Experimental v1 activation is gated twice: the compiled client flag and
 * the server-reported capability must both allow it. Turning an already
 * enabled chat off never requires the allowance.
 */
@HiltViewModel
public final class EncryptionViewModel extends ViewModel {

    static final String ACTIVATION_DISABLED = "E2EE_V1_ACTIVATION_DISABLED";

    public static final class UiState {
        public final boolean loading;
        public final boolean enabled;
        public final boolean busy;
        /** Effective allowance: compiled client flag AND server capability. */
        public final boolean activationAllowed;
        @Nullable public final String errorCode;
        @NonNull public final List<MemberDeviceDto> devices;
        @Nullable public final String safetyCode;

        UiState(boolean loading, boolean enabled, boolean busy, boolean activationAllowed,
                @Nullable String errorCode, @NonNull List<MemberDeviceDto> devices,
                @Nullable String safetyCode) {
            this.loading = loading;
            this.enabled = enabled;
            this.busy = busy;
            this.activationAllowed = activationAllowed;
            this.errorCode = errorCode;
            this.devices = devices;
            this.safetyCode = safetyCode;
        }

        static UiState loading() {
            return new UiState(true, false, false, false, null, List.of(), null);
        }

        UiState withError(@Nullable String code) {
            return new UiState(false, enabled, false, activationAllowed, code, devices,
                    safetyCode);
        }

        UiState withState(@NonNull ConversationE2eeDto dto, boolean activationAllowed) {
            List<MemberDeviceDto> display = displayableDevices(dto.getDevices());
            return new UiState(false, dto.isEnabled(), false, activationAllowed, null,
                    display, directoryComplete(dto.getDevices())
                            ? computeSafetyCode(display) : null);
        }

        UiState busy(boolean value) {
            return new UiState(false, enabled, value, activationAllowed, errorCode, devices,
                    safetyCode);
        }

        /** A server policy denial retires the cached capability for this session. */
        UiState withActivationDisallowed() {
            return new UiState(loading, enabled, busy, false, errorCode, devices, safetyCode);
        }
    }

    private final E2eeSession session;
    private final boolean clientActivationAllowed;
    /** Confines every state transition to the UI thread; tests inject a trampoline. */
    private final Scheduler uiScheduler;
    private final CompositeDisposable disposables = new CompositeDisposable();
    private final MutableLiveData<UiState> uiState = new MutableLiveData<>(UiState.loading());
    private String conversationId = "";
    /** Bumped on conversation switch and on each policy denial; stale callbacks discard. */
    private long policyGeneration;
    /** Sticky per-conversation latch: a server policy denial retires activation
     *  for this screen until a different conversation is loaded. */
    private boolean activationDenied;

    @Inject
    public EncryptionViewModel(@NonNull E2eeSession session) {
        this(session, BuildConfig.E2EE_V1_ACTIVATION_ALLOWED,
                AndroidSchedulers.mainThread());
    }

    /**
     * Deterministic test seam: the client-side allowance and the UI scheduler
     * are passed explicitly.
     */
    EncryptionViewModel(@NonNull E2eeSession session, boolean clientActivationAllowed,
            @NonNull Scheduler uiScheduler) {
        this.session = session;
        this.clientActivationAllowed = clientActivationAllowed;
        this.uiScheduler = uiScheduler;
    }

    @NonNull public LiveData<UiState> getUiState() {
        return uiState;
    }

    @Nullable public String currentDeviceId() {
        return session.currentDeviceId();
    }

    @Nullable public String currentUserId() {
        return session.currentUserId();
    }

    public void load(@NonNull String conversationId) {
        if (!conversationId.equals(this.conversationId)) {
            policyGeneration++;
            activationDenied = false;
            this.conversationId = conversationId;
        }
        final String conv = conversationId;
        final long gen = policyGeneration;
        disposables.add(session.e2eeState(conv)
                .observeOn(uiScheduler)
                .subscribe(
                        dto -> {
                            if (conv.equals(this.conversationId) && gen == policyGeneration) {
                                uiState.setValue(
                                        current().withState(dto, effectiveAllowed(dto)));
                            }
                        },
                        e -> {
                            if (conv.equals(this.conversationId) && gen == policyGeneration) {
                                uiState.setValue(current().withError(code(e)));
                            }
                        }));
    }

    public void setEnabled(boolean enabled) {
        UiState snapshot = current();
        if (enabled && !snapshot.activationAllowed) {
            // New activation is gated client-side too; no network call is
            // made. A stale snapshot that still shows enabled cannot bypass
            // this: only explicit disable requests reach the API.
            uiState.setValue(snapshot.withError(ACTIVATION_DISABLED));
            return;
        }
        uiState.setValue(snapshot.busy(true));
        final String conv = conversationId;
        final long gen = policyGeneration;
        disposables.add(session.setE2ee(conv, enabled)
                .observeOn(uiScheduler)
                .subscribe(
                        dto -> {
                            if (conv.equals(conversationId) && gen == policyGeneration) {
                                uiState.setValue(
                                        current().withState(dto, effectiveAllowed(dto)));
                            }
                        },
                        e -> onToggleError(e, conv, gen)));
    }

    /**
     * Maps a toggle failure into state on the UI scheduler. A policy denial is
     * the authoritative retirement event: it latches {@link #activationDenied},
     * bumps the generation (so every queued pre-denial callback is discarded),
     * invalidates the cached device snapshot and publishes the error, but
     * never rewrites the enabled flag, wipes keys or falls back to plaintext.
     * Any other stale callback is ignored.
     */
    private void onToggleError(@NonNull Throwable e, @NonNull String conv, long gen) {
        String errorCode = code(e);
        if (conv.equals(conversationId) && ACTIVATION_DISABLED.equals(errorCode)) {
            activationDenied = true;
            policyGeneration++;
            session.invalidateDevices(conversationId);
            uiState.setValue(current().withError(errorCode).withActivationDisallowed());
            return;
        }
        if (!conv.equals(conversationId) || gen != policyGeneration) {
            return;
        }
        uiState.setValue(current().withError(errorCode));
    }

    private boolean effectiveAllowed(@NonNull ConversationE2eeDto dto) {
        return clientActivationAllowed && dto.isExperimentalV1ActivationAllowed()
                && !activationDenied;
    }

    @NonNull
    private UiState current() {
        UiState s = uiState.getValue();
        return s == null ? UiState.loading() : s;
    }

    @NonNull
    private static String code(@NonNull Throwable e) {
        return e instanceof E2eeSession.E2eeException && e.getMessage() != null
                ? e.getMessage() : "E2EE_NETWORK";
    }

    /** A row is complete only when all three identity fields are non-blank. */
    private static boolean isCompleteDevice(@Nullable MemberDeviceDto d) {
        return d != null && d.getUserId() != null && !d.getUserId().isBlank()
                && d.getDeviceId() != null && !d.getDeviceId().isBlank()
                && d.getPublicKey() != null && !d.getPublicKey().isBlank();
    }

    /** Malformed rows are excluded from display. */
    @NonNull
    private static List<MemberDeviceDto> displayableDevices(
            @NonNull List<MemberDeviceDto> devices) {
        List<MemberDeviceDto> valid = new ArrayList<>(devices.size());
        for (MemberDeviceDto d : devices) {
            if (isCompleteDevice(d)) {
                valid.add(d);
            }
        }
        return valid;
    }

    /** True only when every directory row is complete. */
    private static boolean directoryComplete(@NonNull List<MemberDeviceDto> devices) {
        for (MemberDeviceDto d : devices) {
            if (!isCompleteDevice(d)) {
                return false;
            }
        }
        return true;
    }

    @Nullable
    private static String computeSafetyCode(@NonNull List<MemberDeviceDto> devices) {
        List<SafetyCode.DeviceIdentity> ids = new ArrayList<>(devices.size());
        for (MemberDeviceDto d : devices) {
            if (!isCompleteDevice(d)) {
                continue;
            }
            ids.add(new SafetyCode.DeviceIdentity(d.getUserId(), d.getDeviceId(),
                    d.getPublicKey()));
        }
        return ids.size() < 2 ? null : SafetyCode.compute(ids);
    }

    @Override
    protected void onCleared() {
        disposables.dispose();
    }
}
