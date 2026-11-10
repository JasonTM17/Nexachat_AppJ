package com.nexachat.summary;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.OkHttpClient;
import org.junit.Test;
import retrofit2.Retrofit;
import retrofit2.converter.moshi.MoshiConverterFactory;
import com.squareup.moshi.Moshi;

public final class AiConsentRepositoryTest {
    @Test
    public void refreshReadsConsentAndUpdateIsOptimisticUntilResponse() throws Exception {
        try (LiveDataTestExecutor ignored = new LiveDataTestExecutor()) {
            CountDownLatch patchReceived = new CountDownLatch(1);
            CountDownLatch releasePatch = new CountDownLatch(1);
            AtomicReference<String> requestBody = new AtomicReference<>();
            try (SummaryTestServer server = new SummaryTestServer(exchange -> {
                if ("GET".equals(exchange.getRequestMethod())) {
                    SummaryTestServer.respond(exchange, 200,
                            "{\"showPresence\":true,\"aiSummaryConsent\":true}");
                    return;
                }
                requestBody.set(SummaryTestServer.requestBody(exchange));
                patchReceived.countDown();
                try {
                    releasePatch.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                SummaryTestServer.respond(exchange, 200,
                        "{\"showPresence\":true,\"aiSummaryConsent\":false}");
            })) {
                AiConsentRepository repository = new AiConsentRepository(consentApi(server.baseUrl()));
                assertFalse(repository.aiSummaryConsent().getValue());

                repository.refresh();
                awaitValue(repository, true);
                repository.setConsent(false);
                assertFalse(repository.aiSummaryConsent().getValue());
                assertTrue(patchReceived.await(5, TimeUnit.SECONDS));
                assertTrue(requestBody.get().contains("\"aiSummaryConsent\":false"));
                releasePatch.countDown();
                awaitValue(repository, false);
                assertEquals(null, repository.error().getValue());
            } finally {
                releasePatch.countDown();
            }
        }
    }

    @Test
    public void failedOptimisticUpdateRollsBackToConfirmedConsent() throws Exception {
        try (LiveDataTestExecutor ignored = new LiveDataTestExecutor()) {
            try (SummaryTestServer server = new SummaryTestServer(exchange -> {
                if ("GET".equals(exchange.getRequestMethod())) {
                    SummaryTestServer.respond(exchange, 200,
                            "{\"aiSummaryConsent\":true}");
                    return;
                }
                SummaryTestServer.requestBody(exchange);
                SummaryTestServer.respond(exchange, 500,
                        "{\"error\":{\"code\":\"INTERNAL_ERROR\",\"message\":\"synthetic\"}}");
            })) {
                AiConsentRepository repository = new AiConsentRepository(consentApi(server.baseUrl()));
                repository.refresh();
                awaitValue(repository, true);

                repository.setConsent(false);
                assertFalse(repository.aiSummaryConsent().getValue());
                awaitError(repository, SummaryError.UNKNOWN);

                assertTrue(repository.aiSummaryConsent().getValue());
                assertEquals(SummaryError.UNKNOWN, repository.error().getValue());
            }
        }
    }

    private static AiConsentApi consentApi(String baseUrl) {
        return new Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(new OkHttpClient())
                .addConverterFactory(MoshiConverterFactory.create(new Moshi.Builder().build()))
                .build()
                .create(AiConsentApi.class);
    }

    private static void awaitValue(AiConsentRepository repository, boolean value)
            throws InterruptedException {
        LiveDataTestSupport.awaitBoolean(repository.aiSummaryConsent(), value);
    }

    private static void awaitError(AiConsentRepository repository, SummaryError error)
            throws InterruptedException {
        LiveDataTestSupport.awaitError(repository.error(), error);
    }
}
