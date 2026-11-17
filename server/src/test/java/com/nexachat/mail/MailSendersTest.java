package com.nexachat.mail;

import com.nexachat.recovery.OtpPurpose;
import jakarta.mail.Session;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class MailSendersTest {

    private static final String ENDPOINT = "https://api.resend.com/emails";
    private static final String FROM = "NexaChat <no-reply@example.com>";
    private static final Locale VIETNAMESE = Locale.forLanguageTag("vi-VN");

    @Test
    void resendSendsOneMessageInTheRequestLanguageWithTheCodeAndNoLinks() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ResendMailSender sender = new ResendMailSender(builder.build(), "test-api-key", FROM, Duration.ZERO);

        server.expect(requestTo(ENDPOINT))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-api-key"))
                .andExpect(header("Idempotency-Key", "otp-abc"))
                .andExpect(content().string(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("\"to\":[\"owner@example.com\"]"),
                        org.hamcrest.Matchers.containsString("123456"),
                        org.hamcrest.Matchers.containsString("10 phút"),
                        org.hamcrest.Matchers.containsString("\"Auto-Submitted\":\"auto-generated\""),
                        org.hamcrest.Matchers.containsString("\"value\":\"reset_password\""),
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("10 minutes")),
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("http")))))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        sender.sendOtp(new OtpEmail("owner@example.com", OtpPurpose.RESET_PASSWORD, "123456", VIETNAMESE, "otp-abc"));
        server.verify();
    }

    @Test
    void resendRetriesOnceAfterAServerErrorWithTheSameIdempotencyKey() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ResendMailSender sender = new ResendMailSender(builder.build(), "test-api-key", FROM, Duration.ZERO);
        server.expect(requestTo(ENDPOINT)).andExpect(header("Idempotency-Key", "otp-1"))
                .andRespond(withServerError());
        server.expect(requestTo(ENDPOINT)).andExpect(header("Idempotency-Key", "otp-1"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        sender.sendOtp(new OtpEmail("owner@example.com", OtpPurpose.REGISTER_EMAIL, "654321",
                Locale.ENGLISH, "otp-1"));
        server.verify();
    }

    @Test
    void resendRetriesARateLimitOnceThenGivesUp() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ResendMailSender sender = new ResendMailSender(builder.build(), "test-api-key", FROM, Duration.ZERO);
        server.expect(requestTo(ENDPOINT)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(requestTo(ENDPOINT)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> sender.sendOtp(new OtpEmail("owner@example.com", OtpPurpose.DELETE_ACCOUNT,
                "654321", Locale.ENGLISH, "otp-2")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Resend delivery failed with status 429");
        server.verify();
    }

    @Test
    void resendDoesNotRetryARejectedMessageAndNeverLeaksTheCodeOrRecipient() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ResendMailSender sender = new ResendMailSender(builder.build(), "test-api-key", FROM, Duration.ZERO);
        server.expect(requestTo(ENDPOINT)).andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"name\":\"validation_error\",\"message\":\"owner@example.com 654321\"}"));

        assertThatThrownBy(() -> sender.sendOtp(new OtpEmail("owner@example.com", OtpPurpose.VERIFY_EMAIL,
                "654321", Locale.ENGLISH, "otp-3")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Resend delivery failed with status 422")
                .satisfies(thrown -> assertThat(String.valueOf(thrown)).doesNotContain("654321", "owner@example.com"));
        server.verify();
    }

    @Test
    void otpEmailKeepsTheCodeOutOfItsStringForm() {
        OtpEmail email = new OtpEmail("owner@example.com", OtpPurpose.RESET_PASSWORD, "123456",
                Locale.ENGLISH, "otp-4");

        assertThat(email.toString()).doesNotContain("123456", "owner@example.com");
    }

    @Test
    void smtpCreatesUtf8PlainAndHtmlBodiesInTheRequestLanguage() throws Exception {
        JavaMailSender javaMailSender = mock(JavaMailSender.class);
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        when(javaMailSender.createMimeMessage()).thenReturn(message);
        MailProperties properties = new MailProperties("smtp", "",
                new MailProperties.Smtp("smtp.gmail.com", 587, "account", "app-password",
                        "NexaChat <mail@example.com>"),
                new MailProperties.Resend(""));
        SmtpMailSender sender = new SmtpMailSender(javaMailSender, properties);

        assertThat(sender.isAvailable()).isTrue();
        sender.sendOtp(new OtpEmail("owner@example.com", OtpPurpose.VERIFY_EMAIL, "123456", VIETNAMESE, null));
        verify(javaMailSender).send(message);

        message.saveChanges();
        List<String> bodies = new ArrayList<>();
        collectTextParts(message, bodies);
        assertThat(bodies).hasSize(2);
        for (String body : bodies) {
            assertThat(body).contains("123456", "Xác minh email khôi phục", "10 phút");
            assertThat(body).doesNotContain("http", "10 minutes");
        }
        assertThat(message.getHeader("Auto-Submitted")).containsExactly("auto-generated");

        MailProperties unconfigured = new MailProperties("smtp", "", null, null);
        assertThat(new SmtpMailSender(javaMailSender, unconfigured).isAvailable()).isFalse();
        assertThat(new ResendMailSender(RestClient.builder().build(), "", "").isAvailable()).isFalse();
    }

    private static void collectTextParts(Part part, List<String> output) throws Exception {
        if (part.isMimeType("text/plain") || part.isMimeType("text/html")) {
            output.add(part.getContent().toString());
            return;
        }
        Object content = part.getContent();
        if (content instanceof jakarta.mail.Multipart multipart) {
            for (int index = 0; index < multipart.getCount(); index++) {
                collectTextParts(multipart.getBodyPart(index), output);
            }
        }
    }
}
