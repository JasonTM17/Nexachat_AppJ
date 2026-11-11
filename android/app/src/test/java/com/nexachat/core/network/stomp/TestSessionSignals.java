package com.nexachat.core.network.stomp;

import com.nexachat.core.network.TokenRefresher;
import io.reactivex.rxjava3.core.Flowable;
import java.util.concurrent.atomic.AtomicLong;
import static org.mockito.Mockito.when;

/** Adapts legacy mock flows to the epoch-tagged production session signal contract. */
public final class TestSessionSignals {

    private TestSessionSignals() {
    }

    public static void stub(TokenRefresher refresher) {
        when(refresher.sessionSignals()).thenReturn(from(refresher));
    }

    public static Flowable<TokenRefresher.SessionSignal> from(TokenRefresher refresher) {
        return Flowable.defer(() -> {
            AtomicLong epoch = new AtomicLong(refresher.sessionEpoch());
            Flowable<TokenRefresher.SessionSignal> epochs = refresher.sessionEpochChanges()
                    .map(value -> {
                        epoch.set(value);
                        return new TokenRefresher.SessionSignal.EpochChanged(value);
                    });
            Flowable<TokenRefresher.SessionSignal> admissions = refresher.sessionAdmission()
                    .map(value -> new TokenRefresher.SessionSignal.AdmissionChanged(
                            epoch.get(), value));
            return Flowable.merge(epochs, admissions);
        });
    }
}
