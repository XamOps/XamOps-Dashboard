package com.xammer.cloud.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables and configures scheduled tasks for the application.
 * This is necessary for features like periodic cache eviction.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
    // This class is intentionally left blank.
    // The @EnableScheduling annotation is all that's needed to activate the feature.
}