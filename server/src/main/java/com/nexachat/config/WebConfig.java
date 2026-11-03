package com.nexachat.config;

import com.nexachat.auth.RateLimitInterceptor;
import com.nexachat.contact.ContactAddRateLimitInterceptor;
import com.nexachat.user.UserSearchRateLimitInterceptor;
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

    public WebConfig(RateLimitInterceptor rateLimitInterceptor,
                     UserSearchRateLimitInterceptor userSearchRateLimitInterceptor,
                     ContactAddRateLimitInterceptor contactAddRateLimitInterceptor) {
        this.rateLimitInterceptor = rateLimitInterceptor;
        this.userSearchRateLimitInterceptor = userSearchRateLimitInterceptor;
        this.contactAddRateLimitInterceptor = contactAddRateLimitInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/api/v1/auth/**");
        registry.addInterceptor(userSearchRateLimitInterceptor)
                .addPathPatterns("/api/v1/users/search");
        registry.addInterceptor(contactAddRateLimitInterceptor)
                .addPathPatterns("/api/v1/contacts");
    }
}
