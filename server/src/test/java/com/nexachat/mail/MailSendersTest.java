package com.nexachat.mail;

import com.nexachat.recovery.OtpPurpose;
import jakarta.mail.Session;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Properties;
import java.util.ArrayList;
import java.util.List;

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
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class MailSendersTest {

    @Test
    void resendSendsOneBilingualMessageWithTheCodeAndNoCodeLink() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ResendMailSender sender = new ResendMailSender(builder.build(), "test-api-key", "NexaChat <no-reply@example.com>");

        server.expect(requestTo("https://api.resend.com/emails"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-api-key"))
                .andExpect(content().string(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("\"to\":[\"owner@example.com\"]"),
                        org.hamcrest.Matchers.containsString("123456"),
                        org.hamcrest.Matchers.containsString("10 minutes"),
                        org.hamcrest.Matchers.containsString("10 phút"),
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("http")))))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        sender.sendOtp("owner@example.com", OtpPurpose.RESET_PASSWORD, "123456");
        server.verify();
    }

    @Test
    void resendDoesNotRetryProviderErrors() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ResendMailSender sender = new ResendMailSender(builder.build(), "test-api-key", "NexaChat <no-reply@example.com>");
        server.expect(requestTo("https://api.resend.com/emails"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withServerError());

        assertThatThrownBy(() -> sender.sendOtp("owner@example.com", OtpPurpose.DELETE_ACCOUNT, "654321"))
                .isInstanceOf(RuntimeException.class);
        server.verify();
    }

    @Test
    void smtpCreatesUtf8PlainAndHtmlBodiesAndRequiresConfiguredCredentials() throws Exception {
        JavaMailSender javaMailSender = mock(JavaMailSender.class);
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        when(javaMailSender.createMimeMessage()).thenReturn(message);
        MailProperties properties = new MailProperties("smtp", "",
                new MailProperties.Smtp("smtp.gmail.com", 587, "account", "app-password",
                        "NexaChat <mail@example.com>"),
                new MailProperties.Resend(""));
        SmtpMailSender sender = new SmtpMailSender(javaMailSender, properties);

        assertThat(sender.isAvailable()).isTrue();
        sender.sendOtp("owner@example.com", OtpPurpose.VERIFY_EMAIL, "123456");
        verify(javaMailSender).send(message);

        message.saveChanges();
        List<String> bodies = new ArrayList<>();
        collectTextParts(message, bodies);
        assertThat(bodies).hasSize(2);
        String plain = bodies.get(0);
        String html = bodies.get(1);
        assertThat(plain).contains("123456", "verify your recovery email", "xác minh email khôi phục", "10 minutes", "10 phút");
        assertThat(html).contains("123456", "verify your recovery email", "xác minh email khôi phục", "10 minutes", "10 phút");
        assertThat(plain).doesNotContain("http");
        assertThat(html).doesNotContain("http");

        MailProperties unconfigured = new MailProperties("smtp", "", null, null);
        assertThat(new SmtpMailSender(javaMailSender, unconfigured).isAvailable()).isFalse();
        assertThat(new ResendMailSender(builderClient(), "", "").isAvailable()).isFalse();
    }

    private static RestClient builderClient() {
        return RestClient.builder().build();
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
