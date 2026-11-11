package com.nexachat.mail;

import com.nexachat.recovery.OtpPurpose;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

import java.nio.charset.StandardCharsets;

public final class SmtpMailSender implements MailSender {

    private final JavaMailSender javaMailSender;
    private final MailProperties properties;

    public SmtpMailSender(JavaMailSender javaMailSender, MailProperties properties) {
        this.javaMailSender = javaMailSender;
        this.properties = properties;
    }

    @Override
    public boolean isAvailable() {
        MailProperties.Smtp smtp = properties.smtp();
        return "smtp".equals(properties.provider())
                && smtp != null && smtp.from() != null && !smtp.from().isBlank()
                && smtp.username() != null && !smtp.username().isBlank()
                && smtp.password() != null && !smtp.password().isBlank();
    }

    @Override
    public void sendOtp(String recipient, OtpPurpose purpose, String code) {
        if (!isAvailable()) {
            throw new IllegalStateException("SMTP mail is not configured");
        }
        OtpMailContent content = OtpMailContent.forCode(purpose, code);
        MimeMessage message = javaMailSender.createMimeMessage();
        try {
            MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setFrom(properties.smtp().from());
            helper.setTo(recipient);
            helper.setSubject(content.subject());
            helper.setText(content.text(), content.html());
            javaMailSender.send(message);
        } catch (MessagingException | MailException ex) {
            throw new IllegalStateException("SMTP delivery failed", ex);
        }
    }
}
