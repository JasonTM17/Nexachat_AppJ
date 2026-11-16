package com.nexachat.feature.encryption;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import com.nexachat.BuildConfig;
import com.nexachat.core.crypto.E2eeSession;
import com.nexachat.core.crypto.KeysetCheck;
import com.nexachat.core.crypto.KeysetTrustStore;
import com.nexachat.core.network.dto.E2eeDeviceDtos.ConversationE2eeDto;
import com.nexachat.core.network.dto.E2eeDeviceDtos.MemberDeviceDto;
import dagger.hilt.android.lifecycle.HiltViewModel;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Scheduler;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.inject.Inject;

/**
 * Backs the encryption screen: loads the conversation's e2ee flag and member
 * device list, shows the NC1 keyset-verification code for direct chats, and
 * applies the toggle through {@link E2eeSession}. Server error codes are
 * surfaced verbatim so the fragment can map them to localized copy.
 *
 * <p>Experimental v1 activation is gated twice: the compiled client flag and
 * the server-reported capability must both allow it. Turning an already
 * enabled chat off never requires the allowance.
 *
 * <p>The verification card binds the exact transcript bytes the user saw:
 * "mark as verified" pins those bytes, never a fresh fetch, and a roster that
 * changed between display and confirm refreshes the code instead of verifying.
 */
@HiltViewModel
public final class EncryptionViewModel extends ViewModel {

    static final String ACTIVATION_DISABLED = "E2EE_V1_ACTIVATION_DISABLED";
    /** Surfaced when the roster moved between display and confirm. */
    static final String KEYSET_RACE = "E2EE_KEYSET_CHANGED";

    /**
     * The durable verification verdict shown by the state row. UNAVAILABLE is
     * not a verdict: the trust store could not be read, so no claim either way
     * is rendered.
     */
    public enum Trust { NOT_VERIFIED, VERIFIED, CHANGED, UNAVAILABLE }

    public static final class UiState {
        public final boolean loading;
        public final boolean enabled;
        public final boolean busy;
        /** Effective allowance: compiled client flag AND server capability. */
        public final boolean activationAllowed;
        @Nullable public final String errorCode;
        @NonNull public final List<MemberDeviceDto> devices;
        /** The NC1 code over the currently displayed transcript; null hides the card. */
        @Nullable public final String verificationCode;
        @NonNull public final Trust trust;
        public final boolean verifyBusy;

        UiState(boolean loading, boolean enabled, boolean busy, boolean activationAllowed,
                @Nullable String errorCode, @NonNull List<MemberDeviceDto> devices,
                @Nullable String verificationCode, @NonNull Trust trust, boolean verifyBusy) {
            this.loading = loading;
            this.enabled = enabled;
            this.busy = busy;
            this.activationAllowed = activationAllowed;
            this.errorCode = errorCode;
            this.devices = devices;
            this.verificationCode = verificationCode;
            this.trust = trust;
            this.verifyBusy = verifyBusy;
        }

        static UiState loading() {
            return new UiState(true, false, false, false, null, List.of(), null,
                    Trust.NOT_VERIFIED, false);
        }

        UiState withError(@Nullable String code) {
            return new UiState(false, enabled, false, activationAllowed, code, devices,
                    verificationCode, trust, verifyBusy);
        }

        UiState withState(@NonNull ConversationE2eeDto dto, boolean activationAllowed) {
            List<MemberDeviceDto> display = displayableDevices(dto.getDevices());
            // Verification needs a live direct roster; when the chat is off or
            // the flag flipped off there is nothing to compare.
            boolean verify = dto.isEnabled();
            return new UiState(false, dto.isEnabled(), false, activationAllowed, null,
                    display, verify ? verificationCode : null,
                    verify ? trust : Trust.NOT_VERIFIED, verifyBusy);
        }

        UiState busy(boolean value) {
            return new UiState(false, enabled, value, activationAllowed, errorCode, devices,
                    verificationCode, trust, verifyBusy);
        }

        /** A server policy denial retires the cached capability for this session. */
        UiState withActivationDisallowed() {
            return new UiState(loading, enabled, busy, false, errorCode, devices,
                    verificationCode, trust, verifyBusy);
        }

        UiState withVerification(@Nullable String code, @NonNull Trust nextTrust) {
            return new UiState(loading, enabled, busy, activationAllowed, errorCode, devices,
                    code, nextTrust, verifyBusy);
        }

        UiState withVerifyBusy(boolean value) {
            return new UiState(loading, enabled, busy, activationAllowed, errorCode, devices,
                    verificationCode, trust, value);
        }
    }

    private final E2eeSession session;
    private final KeysetTrustStore trustStore;
    private final boolean clientActivationAllowed;
    /** Confines every state transition to the UI thread; tests inject a trampoline. */
    private final Scheduler uiScheduler;
    private final CompositeDisposable disposables = new CompositeDisposable();
    private final MutableLiveData<UiState> uiState = new MutableLiveData<>(UiState.loading());
    private String conversationId = "";
    /** Bumped on conversation switch and on each policy denial; stale callbacks discard. */
    private long policyGeneration;
    /** Bumped per verify attempt so a stale confirm chain cannot write state. */
    private long verifyGeneration;
    /** Sticky per-conversation latch: a server policy denial retires activation
     *  for this screen until a different conversation is loaded. */
    private boolean activationDenied;
    /** The exact transcript bytes the NC1 code on screen commits to. */
    @Nullable private byte[] displayedTranscript;

    @Inject
    public EncryptionViewModel(@NonNull E2eeSession session,
            @NonNull KeysetTrustStore trustStore) {
        this(session, trustStore, BuildConfig.E2EE_V1_ACTIVATION_ALLOWED,
                AndroidSchedulers.mainThread());
    }

    /**
     * Deterministic test seam: the client-side allowance and the UI scheduler
     * are passed explicitly.
     */
    EncryptionViewModel(@NonNull E2eeSession session, @NonNull KeysetTrustStore trustStore,
            boolean clientActivationAllowed, @NonNull Scheduler uiScheduler) {
        this.session = session;
        this.trustStore = trustStore;
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
            displayedTranscript = null;
            this.conversationId = conversationId;
        }
        // A verification surface must compare the live roster, not a snapshot
        // held by the device cache TTL.
        session.invalidateDevices(conversationId);
        final String conv = conversationId;
        final long gen = policyGeneration;
        disposables.add(session.e2eeState(conv)
                .observeOn(uiScheduler)
                .subscribe(
                        dto -> {
                            if (conv.equals(this.conversationId) && gen == policyGeneration) {
                                uiState.setValue(
                                        current().withState(dto, effectiveAllowed(dto)));
                                if (dto.isEnabled()) {
                                    loadVerification(conv, gen);
                                } else {
                                    displayedTranscript = null;
                                }
                            }
                        },
                        e -> {
                            if (conv.equals(this.conversationId) && gen == policyGeneration) {
                                uiState.setValue(current().withError(code(e)));
                            }
                        }));
    }

    /**
     * Loads the canonical transcript for the NC1 code together with its trust
     * verdict. Any roster that cannot produce a direct transcript — groups,
     * malformed rows, an off flag — is "nothing to verify" and simply hides
     * the card rather than failing the screen. A trust-store read failure is
     * not an empty record: the card keeps its code but reports the unreadable
     * state, and verification stays disabled rather than letting a missing
     * read stand in for a real verdict.
     */
    private void loadVerification(@NonNull String conv, long gen) {
        String owner = session.currentUserId();
        disposables.add(session.currentTranscript(conv)
                .flatMap(transcript -> trustForDisplayedKeyset(owner, conv, transcript)
                        .map(trust -> new VerificationSnapshot(transcript, trust))
                        .onErrorReturn(readError -> new VerificationSnapshot(
                                transcript, Trust.UNAVAILABLE)))
                .observeOn(uiScheduler)
                .subscribe(
                        snapshot -> {
                            if (!conv.equals(this.conversationId) || gen != policyGeneration) {
                                return;
                            }
                            displayedTranscript = snapshot.transcript.clone();
                            uiState.setValue(current().withVerification(
                                    KeysetCheck.displayCode(
                                            KeysetCheck.digest(snapshot.transcript)),
                                    snapshot.trust));
                        },
                        e -> {
                            if (!conv.equals(this.conversationId) || gen != policyGeneration) {
                                return;
                            }
                            displayedTranscript = null;
                            uiState.setValue(current().withVerification(null, Trust.NOT_VERIFIED));
                        }));
    }

    /**
     * The verdict for the roster about to be displayed, computed by the same
     * comparison the encrypt path runs: a participant's key rotation since
     * the last verification flips the durable record to CHANGED, so the state
     * row — and the chat notice that follows the same record — reports the
     * change instead of standing "verified" next to keys never compared.
     */
    @NonNull
    private Single<Trust> trustForDisplayedKeyset(@Nullable String owner,
            @NonNull String conv, @NonNull byte[] transcript) {
        if (owner == null) {
            return Single.just(Trust.NOT_VERIFIED);
        }
        return trustStore.checkBeforeEncrypt(owner, conv, transcript)
                .map(result -> switch (result) {
                    case VERIFIED_MATCH -> Trust.VERIFIED;
                    case UNVERIFIED -> Trust.NOT_VERIFIED;
                    case CHANGED -> Trust.CHANGED;
                });
    }

    /**
     * Confirms the displayed code: re-reads the roster (bypassing the device
     * cache) and, only when it still produces the exact displayed transcript,
     * pins those bytes through {@link E2eeSession#markVerified}. A roster that
     * changed in between refreshes the shown code and reports
     * {@link #KEYSET_RACE} — the bytes the user never saw are never verified.
     */
    public void confirmVerified() {
        byte[] displayed = displayedTranscript;
        if (displayed == null || current().verifyBusy
                || current().trust == Trust.UNAVAILABLE) {
            // An unreadable trust store cannot reflect a verdict, so it
            // must not accept one either.
            return;
        }
        uiState.setValue(current().withVerifyBusy(true));
        final String conv = conversationId;
        final long gen = ++verifyGeneration;
        session.invalidateDevices(conv);
        disposables.add(session.currentTranscript(conv)
                .observeOn(uiScheduler)
                .subscribe(
                        fresh -> {
                            if (!conv.equals(conversationId) || gen != verifyGeneration) {
                                return;
                            }
                            if (!Arrays.equals(fresh, displayed)) {
                                uiState.setValue(current().withVerifyBusy(false)
                                        .withError(KEYSET_RACE));
                                loadVerification(conv, policyGeneration);
                                return;
                            }
                            disposables.add(session.markVerified(conv, displayed)
                                    .observeOn(uiScheduler)
                                    .subscribe(
                                            () -> {
                                                if (!conv.equals(conversationId)
                                                        || gen != verifyGeneration) {
                                                    return;
                                                }
                                                uiState.setValue(current()
                                                        .withVerification(
                                                                current().verificationCode,
                                                                Trust.VERIFIED)
                                                        .withVerifyBusy(false));
                                            },
                                            e -> {
                                                if (!conv.equals(conversationId)
                                                        || gen != verifyGeneration) {
                                                    return;
                                                }
                                                uiState.setValue(current()
                                                        .withVerifyBusy(false)
                                                        .withError(code(e)));
                                            }));
                        },
                        e -> {
                            if (!conv.equals(conversationId) || gen != verifyGeneration) {
                                return;
                            }
                            uiState.setValue(current().withVerifyBusy(false)
                                    .withError(code(e)));
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
                                if (dto.isEnabled()) {
                                    loadVerification(conv, gen);
                                } else {
                                    displayedTranscript = null;
                                }
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

    /** The transcript shown on screen plus the verdict computed alongside it. */
    private record VerificationSnapshot(@NonNull byte[] transcript,
            @NonNull Trust trust) { }

    @Override
    protected void onCleared() {
        disposables.dispose();
    }
}
