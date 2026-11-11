package com.nexachat.mail;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "nexachat.mail")
public record MailProperties(String provider, String from, Smtp smtp, Resend resend) {

    public record Smtp(String host, int port, String username, String password, String from) {
    }

    public record Resend(String apiKey) {
    }
}
