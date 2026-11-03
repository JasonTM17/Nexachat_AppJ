package com.nexachat.config;

import com.nexachat.auth.RateLimitInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * MVC wiring: registers the Bucket4j rate-limit interceptor on the auth
 * endpoints. The interceptor runs inside the DispatcherServlet, so its
 * {@code ApiException}s keep the standard error envelope via
 * {@code GlobalExceptionHandler}.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final RateLimitInterceptor rateLimitInterceptor;

    public WebConfig(RateLimitInterceptor rateLimitInterceptor) {
        this.rateLimitInterceptor = rateLimitInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/api/v1/auth/**");
    }
}
