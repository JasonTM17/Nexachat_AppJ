package com.nexachat.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sends security codes through the Resend HTTP API.
 *
 * <p>A send is attempted twice at most, and only after a rate limit, a server error or a network
 * failure. The idempotency key makes the second attempt safe: Resend delivers one message per key.
 * Logs carry the status and Resend's error name, never the recipient, the code or the request body.
 */
public final class ResendMailSender implements MailSender {

    private static final Logger log = LoggerFactory.getLogger(ResendMailSender.class);
    private static final String ENDPOINT = "https://api.resend.com/emails";
    private static final Duration DEFAULT_RETRY_DELAY = Duration.ofMillis(500);
    private static final Pattern ERROR_NAME = Pattern.compile("\"name\"\\s*:\\s*\"([a-z_]{1,64})\"");

    private final RestClient restClient;
    private final String apiKey;
    private final String from;
    private final Duration retryDelay;

    public ResendMailSender(RestClient restClient, String apiKey, String from) {
        this(restClient, apiKey, from, DEFAULT_RETRY_DELAY);
    }

    ResendMailSender(RestClient restClient, String apiKey, String from, Duration retryDelay) {
        this.restClient = restClient;
        this.apiKey = apiKey;
        this.from = from;
        this.retryDelay = retryDelay;
    }

    @Override
    public boolean isAvailable() {
        return apiKey != null && !apiKey.isBlank() && from != null && !from.isBlank();
    }

    @Override
    public void sendOtp(OtpEmail email) {
        if (!isAvailable()) {
            throw new IllegalStateException("Resend mail is not configured");
        }
        OtpMailContent content = OtpMailContent.forCode(email.purpose(), email.code(), email.locale());
        Map<String, Object> body = Map.of(
                "from", from,
                "to", List.of(email.recipient()),
                "subject", content.subject(),
                "text", content.text(),
                "html", content.html(),
                "headers", Map.of(
                        "Auto-Submitted", "auto-generated",
                        "X-Auto-Response-Suppress", "All"),
                "tags", List.of(
                        Map.of("name", "category", "value", "otp"),
                        Map.of("name", "purpose", "value", email.purpose().name().toLowerCase(Locale.ROOT))));
        for (int attempt = 1; ; attempt++) {
            try {
                post(body, email.idempotencyKey());
                return;
            } catch (RestClientResponseException exception) {
                HttpStatusCode status = exception.getStatusCode();
                boolean transientFailure = status.value() == 429 || status.is5xxServerError();
                log.warn("Resend rejected an OTP message: status={} error={} attempt={}",
                        status.value(), errorName(exception), attempt);
                if (!transientFailure || attempt >= 2) {
                    throw new IllegalStateException("Resend delivery failed with status " + status.value());
                }
            } catch (ResourceAccessException exception) {
                log.warn("Resend could not be reached for an OTP message: attempt={}", attempt);
                if (attempt >= 2) {
                    throw new IllegalStateException("Resend delivery failed: provider unreachable");
                }
            }
            pause();
        }
    }

    private void post(Map<String, Object> body, String idempotencyKey) {
        RestClient.RequestBodySpec request = restClient.post()
                .uri(ENDPOINT)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON);
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            request = request.header("Idempotency-Key", idempotencyKey);
        }
        request.body(body).retrieve().toBodilessEntity();
    }

    private void pause() {
        try {
            Thread.sleep(retryDelay.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Resend delivery interrupted");
        }
    }

    private static String errorName(RestClientResponseException exception) {
        Matcher matcher = ERROR_NAME.matcher(exception.getResponseBodyAsString());
        return matcher.find() ? matcher.group(1) : "unknown";
    }

    static RestClient createClient() {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        org.springframework.http.client.JdkClientHttpRequestFactory requestFactory =
                new org.springframework.http.client.JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(8));
        return RestClient.builder().requestFactory(requestFactory).build();
    }
}
