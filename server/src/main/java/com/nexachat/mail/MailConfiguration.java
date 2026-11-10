package com.nexachat.mail;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(MailProperties.class)
public class MailConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "nexachat.mail", name = "provider", havingValue = "smtp")
    @ConditionalOnMissingBean(MailSender.class)
    MailSender smtpMailSender(JavaMailSender sender, MailProperties properties) {
        return new SmtpMailSender(sender, properties);
    }

    @Bean
    @ConditionalOnProperty(prefix = "nexachat.mail", name = "provider", havingValue = "resend")
    @ConditionalOnMissingBean(MailSender.class)
    MailSender resendMailSender(MailProperties properties) {
        MailProperties.Resend resend = properties.resend();
        return new ResendMailSender(ResendMailSender.createClient(),
                resend == null ? null : resend.apiKey(),
                properties.from());
    }

    @Bean
    @ConditionalOnProperty(prefix = "nexachat.mail", name = "provider", havingValue = "")
    @ConditionalOnMissingBean(MailSender.class)
    MailSender disabledMailSender() {
        return new DisabledMailSender();
    }
}
