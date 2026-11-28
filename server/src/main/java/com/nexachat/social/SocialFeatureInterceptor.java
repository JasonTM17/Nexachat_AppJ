package com.nexachat.social;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Runtime kill switch for the web Social surface. Registered in
 * {@code WebConfig} for {@code /api/social/v1/**} except the public config
 * bootstrap itself, so a disabled surface answers every feed request with
 * {@code 403 SOCIAL_FEATURE_DISABLED} while still letting the page learn
 * that it is disabled. The legacy {@code /api/v1/feed} alias used by Android
 * is never registered here and is unaffected.
 *
 * <p>The check reads the singleton row on every request — no cache — so an
 * admin flag flip takes effect immediately for new requests. Work already
 * admitted (an in-flight upload or create) is allowed to finish.</p>
 */
@Component
public class SocialFeatureInterceptor implements HandlerInterceptor {

    private final SocialSettingsService settings;

    public SocialFeatureInterceptor(SocialSettingsService settings) {
        this.settings = settings;
    }

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request,
                             @NonNull HttpServletResponse response,
                             @NonNull Object handler) {
        if (!settings.isWebEnabled()) {
            throw ApiException.of(ApiErrorCode.SOCIAL_FEATURE_DISABLED,
                    "Social web is currently disabled");
        }
        return true;
    }
}
