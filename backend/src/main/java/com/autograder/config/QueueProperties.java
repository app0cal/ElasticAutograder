package com.autograder.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Spring properties for Redis queue names.
 */
@ConfigurationProperties(prefix = "grading.queue")
public class QueueProperties {

    private boolean enabled = true;
    private String name = "grading-jobs";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

}
