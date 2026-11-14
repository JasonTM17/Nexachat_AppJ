package com.nexachat.feature.encryption;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import com.nexachat.core.crypto.E2eeSession;
import com.nexachat.core.crypto.SafetyCode;
import com.nexachat.core.network.dto.E2eeDeviceDtos.ConversationE2eeDto;
import com.nexachat.core.network.dto.E2eeDeviceDtos.MemberDeviceDto;
import dagger.hilt.android.lifecycle.HiltViewModel;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;

/**
 * Backs the encryption screen: loads the conversation's e2ee flag and member
 * device list, computes the safety code, and applies the toggle through
 * {@link E2eeSession}. Server error codes are surfaced verbatim so the
 * fragment can map them to localized copy.
 */
@HiltViewModel
public final class EncryptionViewModel extends ViewModel {

    public static final class UiState {
        public final boolean loading;
        public final boolean enabled;
        public final boolean busy;
        @Nullable public final String errorCode;
        @NonNull public final List<MemberDeviceDto> devices;
        @Nullable public final String safetyCode;

        UiState(boolean loading, boolean enabled, boolean busy, @Nullable String errorCode,
                @NonNull List<MemberDeviceDto> devices, @Nullable String safetyCode) {
            this.loading = loading;
            this.enabled = enabled;
            this.busy = busy;
            this.errorCode = errorCode;
            this.devices = devices;
            this.safetyCode = safetyCode;
        }

        static UiState loading() {
            return new UiState(true, false, false, null, List.of(), null);
        }

        UiState withError(@Nullable String code) {
            return new UiState(false, enabled, false, code, devices, safetyCode);
        }

        UiState withState(@NonNull ConversationE2eeDto dto) {
            return new UiState(false, dto.isEnabled(), false, null, dto.getDevices(),
                    computeSafetyCode(dto.getDevices()));
        }

        UiState busy(boolean value) {
            return new UiState(false, enabled, value, errorCode, devices, safetyCode);
        }
    }

    private final E2eeSession session;
    private final CompositeDisposable disposables = new CompositeDisposable();
    private final MutableLiveData<UiState> uiState = new MutableLiveData<>(UiState.loading());
    private String conversationId = "";

    @Inject
    public EncryptionViewModel(@NonNull E2eeSession session) {
        this.session = session;
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
        this.conversationId = conversationId;
        disposables.add(session.e2eeState(conversationId)
                .subscribe(
                        dto -> uiState.postValue(current().withState(dto)),
                        e -> uiState.postValue(current().withError(code(e)))));
    }

    public void setEnabled(boolean enabled) {
        UiState snapshot = current();
        uiState.setValue(snapshot.busy(true));
        disposables.add(session.setE2ee(conversationId, enabled)
                .subscribe(
                        dto -> uiState.postValue(current().withState(dto)),
                        e -> uiState.postValue(current().withError(code(e)))));
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

    @Nullable
    private static String computeSafetyCode(@NonNull List<MemberDeviceDto> devices) {
        List<SafetyCode.DeviceIdentity> ids = new ArrayList<>(devices.size());
        for (MemberDeviceDto d : devices) {
            if (d.getUserId() == null || d.getDeviceId() == null || d.getPublicKey() == null) {
                continue;
            }
            ids.add(new SafetyCode.DeviceIdentity(d.getUserId(), d.getDeviceId(), d.getPublicKey()));
        }
        return ids.size() < 2 ? null : SafetyCode.compute(ids);
    }

    @Override
    protected void onCleared() {
        disposables.dispose();
    }
}
