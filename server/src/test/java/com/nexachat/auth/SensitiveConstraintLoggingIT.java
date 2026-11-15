package com.nexachat.auth;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.nexachat.common.error.ConstraintNames;
import com.nexachat.user.User;
import com.nexachat.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A lost unique-constraint race must not write the offending column values
 * (verified emails, phones) into application logs. Hibernate reports the
 * Postgres DETAIL line through its JDBC exception logger, so the captured
 * events — formatted messages and exception text — are scanned for the
 * contested address.
 */
class SensitiveConstraintLoggingIT extends SecurityITBase {

    private static final String VERIFIED_EMAIL_INDEX = "users_verified_email_active_key";

    @Autowired
    private UserRepository userRepository;

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
    void verifiedEmailConflictOmitsTheAddressFromCapturedLogs() {
        String contestedEmail = "contested-" + UUID.randomUUID() + "@example.test";
        userRepository.saveAndFlush(User.createGoogleOnly(
                "Owner", contestedEmail, Instant.now()));
        User challenger = userRepository.saveAndFlush(User.createGoogleOnly(
                "Challenger", "challenger-" + UUID.randomUUID() + "@example.test", Instant.now()));

        challenger.changeEmail(contestedEmail);
        challenger.verifyEmail(Instant.now());

        assertThatThrownBy(() -> userRepository.saveAndFlush(challenger))
                .isInstanceOfSatisfying(DataIntegrityViolationException.class, violation ->
                        assertThat(ConstraintNames.of(violation)).isEqualTo(VERIFIED_EMAIL_INDEX));

        assertThat(capturedLogText()).doesNotContain(contestedEmail);
    }

    private String capturedLogText() {
        return logAppender.list.stream()
                .map(SensitiveConstraintLoggingIT::eventText)
                .collect(Collectors.joining("\n"));
    }

    private static String eventText(ILoggingEvent event) {
        StringBuilder text = new StringBuilder(event.getFormattedMessage());
        IThrowableProxy proxy = event.getThrowableProxy();
        while (proxy != null) {
            text.append('\n').append(proxy.getClassName());
            if (proxy.getMessage() != null) {
                text.append(": ").append(proxy.getMessage());
            }
            proxy = proxy.getCause();
        }
        return text.toString();
    }
}
