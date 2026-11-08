package com.nexachat.observability;

import com.nexachat.message.MessageService;
import com.nexachat.message.dto.MessageResponse;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * Counts messages accepted by the send path and the outbox replays (a
 * re-sent {@code clientMessageId} that deduplicated to the stored row).
 */
@Aspect
@Component
public class MessageMetricsAspect {

    private final ObservabilityMetrics metrics;

    public MessageMetricsAspect(ObservabilityMetrics metrics) {
        this.metrics = metrics;
    }

    @AfterReturning(pointcut = "execution(* com.nexachat.message.MessageService.send(..))",
            returning = "result")
    public void recordSend(MessageService.SendResult result) {
        metrics.recordMessageSent(result.message().type(), result.created());
        if (!result.created()) {
            metrics.recordOutboxReplay();
        }
    }

    @AfterReturning(pointcut = "execution(* com.nexachat.message.MessageService.postSystemMessage(..))",
            returning = "result")
    public void recordSystemMessage(MessageResponse result) {
        metrics.recordSystemMessage();
    }
}
