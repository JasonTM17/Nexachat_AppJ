package com.nexachat.reminder;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables the polling schedule locally so the reminder worker does not depend
 * on another feature package being present. Spring tolerates the duplicate
 * {@code @EnableScheduling} if another configuration also enables it.
 */
@Configuration
@EnableScheduling
public class ReminderConfiguration {
}
