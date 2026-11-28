package com.nexachat.config;

import com.nexachat.auth.RateLimitInterceptor;
import com.nexachat.auth.SensitiveCredentialRateLimitInterceptor;
import com.nexachat.contact.ContactAddRateLimitInterceptor;
import com.nexachat.user.UserSearchRateLimitInterceptor;
import com.nexachat.message.MessageRateLimitInterceptor;
import com.nexachat.social.SocialFeatureInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * MVC wiring: registers the Bucket4j rate-limit interceptors. The auth
 * interceptor covers the credential endpoints; the search interceptor adds a
 * per-user bucket on /api/v1/users/search. Both run inside the
 * DispatcherServlet, so their {@code ApiException}s keep the standard error
 * envelope via {@code GlobalExceptionHandler}.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final RateLimitInterceptor rateLimitInterceptor;
    private final UserSearchRateLimitInterceptor userSearchRateLimitInterceptor;
    private final ContactAddRateLimitInterceptor contactAddRateLimitInterceptor;
    private final MessageRateLimitInterceptor messageRateLimitInterceptor;
    private final SensitiveCredentialRateLimitInterceptor sensitiveCredentialRateLimitInterceptor;
    private final SocialFeatureInterceptor socialFeatureInterceptor;

    public WebConfig(RateLimitInterceptor rateLimitInterceptor,
                     UserSearchRateLimitInterceptor userSearchRateLimitInterceptor,
                     ContactAddRateLimitInterceptor contactAddRateLimitInterceptor,
                     MessageRateLimitInterceptor messageRateLimitInterceptor,
                     SensitiveCredentialRateLimitInterceptor sensitiveCredentialRateLimitInterceptor,
                     SocialFeatureInterceptor socialFeatureInterceptor) {
        this.rateLimitInterceptor = rateLimitInterceptor;
        this.userSearchRateLimitInterceptor = userSearchRateLimitInterceptor;
        this.contactAddRateLimitInterceptor = contactAddRateLimitInterceptor;
        this.messageRateLimitInterceptor = messageRateLimitInterceptor;
        this.sensitiveCredentialRateLimitInterceptor = sensitiveCredentialRateLimitInterceptor;
        this.socialFeatureInterceptor = socialFeatureInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/api/v1/auth/**", "/api/v1/account-deletion/**",
                        "/api/v1/users/me/email", "/api/v1/users/me/email/verify");
        registry.addInterceptor(userSearchRateLimitInterceptor)
                .addPathPatterns("/api/v1/users/search");
        registry.addInterceptor(contactAddRateLimitInterceptor)
                .addPathPatterns("/api/v1/contacts");
        registry.addInterceptor(messageRateLimitInterceptor)
                .addPathPatterns("/api/v1/conversations/**");
        registry.addInterceptor(sensitiveCredentialRateLimitInterceptor)
                .addPathPatterns("/api/v1/auth/password", "/api/v1/users/me");
        // The web alias kill switch covers every Social feed route; the
        // public config bootstrap stays reachable so the page can learn the
        // surface is disabled. /api/v1/feed is deliberately not listed.
        registry.addInterceptor(socialFeatureInterceptor)
                .addPathPatterns("/api/social/v1/**")
                .excludePathPatterns("/api/social/v1/config");
    }
}
