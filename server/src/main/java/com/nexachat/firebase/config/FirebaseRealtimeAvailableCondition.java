package com.nexachat.firebase.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

/** Firebase bridge beans exist only when both the opt-in flag and RTDB URL are set. */
public final class FirebaseRealtimeAvailableCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        boolean enabled = context.getEnvironment()
                .getProperty("nexachat.firebase.realtime-enabled", Boolean.class, false);
        String databaseUrl = context.getEnvironment().getProperty("nexachat.firebase.database-url");
        return enabled && StringUtils.hasText(databaseUrl);
    }
}
