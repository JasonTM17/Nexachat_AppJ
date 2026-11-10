package com.nexachat.config;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

import java.util.Locale;

/**
 * The development JWT secret is checked into {@code application.yml}. Production
 * must not boot when that value, or a secret with almost no diversity, is configured.
 */
@Configuration(proxyBeanMethods = false)
@Profile("prod")
public class ProductionJwtSecretGuard {

    /** Exact default from {@code application.yml}. Public, so it must not sign production tokens. */
    static final String DEV_DEFAULT =
            "dev-secret-change-me-at-least-64-chars-long-0123456789";

    @Bean
    static BeanFactoryPostProcessor rejectKnownDevelopmentJwtSecret(Environment environment) {
        return beanFactory -> {
            final String secret;
            try {
                secret = environment.getProperty("nexachat.jwt.secret");
            } catch (IllegalArgumentException unresolved) {
                // Missing ${JWT_SECRET} still fails in JwtService with the short-key error.
                return;
            }
            if (secret == null || secret.isBlank() || secret.startsWith("${")) {
                return;
            }
            if (isKnownOrWeak(secret)) {
                throw new IllegalStateException(
                        "nexachat.jwt.secret must not be a known development or low-diversity value in the prod profile");
            }
        };
    }

    static boolean isKnownOrWeak(String secret) {
        if (secret.equals(DEV_DEFAULT)
                || secret.toLowerCase(Locale.ROOT).contains("dev-secret-change-me")) {
            return true;
        }
        return secret.codePoints().distinct().count() < 8;
    }
}
