package com.nexachat.mail;

import com.nexachat.recovery.OtpPurpose;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public final class ResendMailSender implements MailSender {

    private final RestClient restClient;
    private final String apiKey;
    private final String from;

    public ResendMailSender(RestClient restClient, String apiKey, String from) {
        this.restClient = restClient;
        this.apiKey = apiKey;
        this.from = from;
    }

    @Override
    public boolean isAvailable() {
        return apiKey != null && !apiKey.isBlank() && from != null && !from.isBlank();
    }

    @Override
    public void sendOtp(String recipient, OtpPurpose purpose, String code) {
        if (!isAvailable()) {
            throw new IllegalStateException("Resend mail is not configured");
        }
        OtpMailContent content = OtpMailContent.forCode(purpose, code);
        restClient.post()
                .uri("https://api.resend.com/emails")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "from", from,
                        "to", List.of(recipient),
                        "subject", content.subject(),
                        "text", content.text(),
                        "html", content.html()))
                .retrieve()
                .toBodilessEntity();
    }

    static RestClient createClient() {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        org.springframework.http.client.JdkClientHttpRequestFactory requestFactory =
                new org.springframework.http.client.JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        return RestClient.builder().requestFactory(requestFactory).build();
    }
}
