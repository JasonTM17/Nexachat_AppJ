package com.nexachat.auth;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Smoke check that high-sensitivity request fields never reach application logs. */
class SensitiveRequestLoggingIT extends SecurityITBase {

    private static final byte[] PNG_BYTES = new byte[] {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52
    };

    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void attachLogCapture() {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        logAppender = new ListAppender<>();
        logAppender.start();
        root.addAppender(logAppender);
    }

    @AfterEach
    void detachLogCapture() {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        root.detachAppender(logAppender);
    }

    @Test
    void loginRegisterSendAndUploadOmitPasswordsTokensAndMessageBodies() throws Exception {
        String password = "SecretPass-253!";
        String phone = "+84969990253";
        String messageBody = "ops-log-probe-253-do-not-log";

        Registered alice = register(phone, password);
        Registered bob = register("+84969990254", "OtherPass-253!");

        var direct = postJson("/api/v1/conversations/direct",
                        Map.of("userId", bob.userId().toString()), alice.accessToken())
                .andExpect(status().is2xxSuccessful())
                .andReturn();
        UUID conversationId = UUID.fromString(objectMapper.readTree(direct.getResponse().getContentAsString())
                .get("id").asText());

        postJson("/api/v1/conversations/" + conversationId + "/messages",
                        Map.of(
                                "clientMessageId", UUID.randomUUID().toString(),
                                "type", "TEXT",
                                "content", messageBody),
                        alice.accessToken())
                .andExpect(status().isCreated());

        MockMultipartFile file = new MockMultipartFile("file", "probe.png", "image/png", PNG_BYTES);
        mockMvc.perform(multipart("/api/v1/attachments")
                        .file(file)
                        .header("Authorization", "Bearer " + alice.accessToken()))
                .andExpect(status().isCreated());

        String combined = logAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.joining("\n"));

        assertThat(combined).doesNotContain(password);
        assertThat(combined).doesNotContain(alice.refreshToken());
        assertThat(combined).doesNotContain("Bearer " + alice.accessToken());
        assertThat(combined).doesNotContain(messageBody);
    }
}
