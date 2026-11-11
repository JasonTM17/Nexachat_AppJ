package com.nexachat.reminder;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables reminder scheduling locally so the worker does not depend on another
 * feature package being present. Spring tolerates duplicate
 * {@code @EnableScheduling} declarations.
 */
@Configuration
@EnableScheduling
public class ReminderConfiguration {
}
