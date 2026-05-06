package com.autograder.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Spring properties for lightweight queue/system health checks.
 */
@ConfigurationProperties(prefix = "grading.health")
public class QueueHealthProperties {

    private Duration staleRunningThreshold = Duration.ofMinutes(5);

    public Duration getStaleRunningThreshold() {
        return staleRunningThreshold;
    }

    public void setStaleRunningThreshold(Duration staleRunningThreshold) {
        this.staleRunningThreshold = staleRunningThreshold;
    }
}
