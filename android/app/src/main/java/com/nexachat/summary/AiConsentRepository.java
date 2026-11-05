package com.nexachat.summary;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.Executor;
import javax.inject.Inject;
import javax.inject.Named;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/** Account-level consent state with optimistic updates and rollback on failure. */
public final class AiConsentRepository {
    private final AiConsentApi api;
    private final Executor mainExecutor;
    private final MutableLiveData<Boolean> consent = new MutableLiveData<>(false);
    private final MutableLiveData<SummaryError> lastError = new MutableLiveData<>();

    private volatile boolean confirmedConsent;
    private volatile long generation;
    private volatile Call<AiSummaryConsentResponse> activeCall;

    @Inject
    public AiConsentRepository(
            AiConsentApi api,
            @Named("summary_main") Executor mainExecutor) {
        this.api = Objects.requireNonNull(api, "api");
        this.mainExecutor = Objects.requireNonNull(mainExecutor, "mainExecutor");
    }

    AiConsentRepository(AiConsentApi api) {
        this(api, Runnable::run);
    }

    public LiveData<Boolean> aiSummaryConsent() {
        return consent;
    }

    public LiveData<SummaryError> error() {
        return lastError;
    }

    public void refresh() {
        long token = ++generation;
        cancelActiveCall();
        final Call<AiSummaryConsentResponse> call;
        try {
            call = api.getSettings();
        } catch (RuntimeException failure) {
            publish(token, confirmedConsent, SummaryErrorMapper.fromFailure(failure));
            return;
        }
        activeCall = call;
        call.enqueue(new Callback<>() {
            @Override
            public void onResponse(
                    Call<AiSummaryConsentResponse> ignored,
                    Response<AiSummaryConsentResponse> response) {
                if (token != generation) {
                    return;
                }
                if (response.isSuccessful() && response.body() != null
                        && response.body().getAiSummaryConsent() != null) {
                    confirmedConsent = response.body().getAiSummaryConsent();
                    publish(token, confirmedConsent, null);
                    activeCall = null;
                    return;
                }
                publish(token, confirmedConsent, mapResponseError(response));
                activeCall = null;
            }

            @Override
            public void onFailure(Call<AiSummaryConsentResponse> ignored, Throwable failure) {
                if (token == generation) {
                    publish(token, confirmedConsent, SummaryErrorMapper.fromFailure(failure));
                    activeCall = null;
                }
            }
        });
    }

    public void setConsent(boolean value) {
        Boolean current = consent.getValue();
        if (current != null && current == value && activeCall == null) {
            return;
        }

        long token = ++generation;
        cancelActiveCall();
        publish(token, value, null);
        final Call<AiSummaryConsentResponse> call;
        try {
            call = api.updateSettings(new AiSummaryConsentRequest(value));
        } catch (RuntimeException failure) {
            publish(token, confirmedConsent, SummaryErrorMapper.fromFailure(failure));
            return;
        }
        activeCall = call;
        call.enqueue(new Callback<>() {
            @Override
            public void onResponse(
                    Call<AiSummaryConsentResponse> ignored,
                    Response<AiSummaryConsentResponse> response) {
                if (token != generation) {
                    return;
                }
                if (response.isSuccessful() && response.body() != null
                        && response.body().getAiSummaryConsent() != null) {
                    confirmedConsent = response.body().getAiSummaryConsent();
                    publish(token, confirmedConsent, null);
                    activeCall = null;
                    return;
                }
                publish(token, confirmedConsent, mapResponseError(response));
                activeCall = null;
            }

            @Override
            public void onFailure(Call<AiSummaryConsentResponse> ignored, Throwable failure) {
                if (token == generation) {
                    publish(token, confirmedConsent, SummaryErrorMapper.fromFailure(failure));
                    activeCall = null;
                }
            }
        });
    }

    private void publish(long token, boolean value, SummaryError error) {
        mainExecutor.execute(() -> {
            if (token != generation) {
                return;
            }
            consent.setValue(value);
            lastError.setValue(error);
        });
    }

    private void cancelActiveCall() {
        if (activeCall != null) {
            activeCall.cancel();
            activeCall = null;
        }
    }

    private static SummaryError mapResponseError(Response<?> response) {
        if (response.isSuccessful()) {
            return SummaryError.UNKNOWN;
        }
        String body = null;
        if (response.errorBody() != null) {
            try {
                body = response.errorBody().string();
            } catch (IOException ignored) {
                body = null;
            }
        }
        return SummaryErrorMapper.fromHttp(response.code(), body);
    }
}
