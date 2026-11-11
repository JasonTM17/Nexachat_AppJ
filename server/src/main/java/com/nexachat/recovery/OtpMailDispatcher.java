package com.nexachat.recovery;

import com.nexachat.mail.MailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

@Component
public class OtpMailDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OtpMailDispatcher.class);
    private final MailSender mailSender;
    private final TaskExecutor taskExecutor;

    public OtpMailDispatcher(MailSender mailSender,
                             @Qualifier("otpMailTaskExecutor") TaskExecutor taskExecutor) {
        this.mailSender = mailSender;
        this.taskExecutor = taskExecutor;
    }

    /** Defers phone lookup, OTP issuance, and delivery until after the public request returns. */
    public void dispatchPublicRequest(OtpPurpose purpose, Supplier<Optional<OtpMail>> prepareRequest) {
        try {
            taskExecutor.execute(() -> {
                Optional<OtpMail> mail;
                try {
                    mail = prepareRequest.get();
                } catch (RuntimeException exception) {
                    log.warn("OTP request processing failed for purpose {}", purpose);
                    return;
                }
                if (!mailSender.isAvailable()) {
                    log.warn("OTP mail delivery is unavailable");
                    return;
                }
                mail.ifPresent(this::deliver);
            });
        } catch (RejectedExecutionException exception) {
            log.warn("OTP request queue is unavailable");
        }
    }

    private void deliver(OtpMail mail) {
        try {
            mailSender.sendOtp(mail.recipient(), mail.purpose(), mail.code());
        } catch (RuntimeException exception) {
            log.warn("OTP mail delivery failed for purpose {}", mail.purpose());
        }
    }
}
