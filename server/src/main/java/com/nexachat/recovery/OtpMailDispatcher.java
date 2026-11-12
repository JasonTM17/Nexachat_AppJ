package com.nexachat.recovery;

import com.nexachat.mail.MailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
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
    public void dispatchPublicRequest(OtpPurpose purpose,
                                      Supplier<Optional<OtpMail>> prepareRequest,
                                      Consumer<OtpMail> onFailure) {
        dispatch(purpose, prepareRequest, onFailure);
    }

    /** Sends a deletion code to the authenticated account's own verified email. */
    public void dispatchAuthenticatedDeletion(Supplier<Optional<OtpMail>> prepareRequest,
                                             Consumer<OtpMail> onFailure) {
        dispatch(OtpPurpose.DELETE_ACCOUNT, prepareRequest, onFailure);
    }

    /** Defers email ownership checks and delivery so the response does not reveal address ownership. */
    public void dispatchEmailVerification(Supplier<Optional<OtpMail>> prepareRequest,
                                         Consumer<OtpMail> onFailure) {
        dispatch(OtpPurpose.VERIFY_EMAIL, prepareRequest, onFailure);
    }

    private void dispatch(OtpPurpose purpose,
                          Supplier<Optional<OtpMail>> prepareRequest,
                          Consumer<OtpMail> onFailure) {
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
                    mail.ifPresent(value -> failDelivery(value, onFailure));
                    log.warn("OTP mail delivery is unavailable");
                    return;
                }
                mail.ifPresent(value -> deliver(value, onFailure));
            });
        } catch (RejectedExecutionException exception) {
            log.warn("OTP request queue is unavailable");
        }
    }

    private void deliver(OtpMail mail, Consumer<OtpMail> onFailure) {
        try {
            mailSender.sendOtp(mail.recipient(), mail.purpose(), mail.code());
        } catch (RuntimeException exception) {
            failDelivery(mail, onFailure);
            log.warn("OTP mail delivery failed for purpose {}", mail.purpose());
        }
    }

    private void failDelivery(OtpMail mail, Consumer<OtpMail> onFailure) {
        try {
            onFailure.accept(mail);
        } catch (RuntimeException exception) {
            log.warn("OTP state cleanup failed for purpose {}", mail.purpose());
        }
    }
}
