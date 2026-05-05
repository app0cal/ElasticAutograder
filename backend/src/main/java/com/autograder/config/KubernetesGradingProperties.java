package com.autograder.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Spring properties that control Kubernetes grader job orchestration.
 */
@ConfigurationProperties(prefix = "grading.kubernetes")
public class KubernetesGradingProperties {

    private String namespace = "elastic-grading";
    private Integer jobTtlSeconds = 300;
    private Duration pollInterval = Duration.ofSeconds(1);
    private Integer maxActiveJobs = 8;
    private boolean cleanupJobs = false;

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public Integer getJobTtlSeconds() {
        return jobTtlSeconds;
    }

    public void setJobTtlSeconds(Integer jobTtlSeconds) {
        this.jobTtlSeconds = jobTtlSeconds;
    }

    public Duration getPollInterval() {
        return pollInterval;
    }

    public void setPollInterval(Duration pollInterval) {
        this.pollInterval = pollInterval;
    }

    public Integer getMaxActiveJobs() {
        return maxActiveJobs;
    }

    public void setMaxActiveJobs(Integer maxActiveJobs) {
        this.maxActiveJobs = maxActiveJobs;
    }

    public boolean isCleanupJobs() {
        return cleanupJobs;
    }

    public void setCleanupJobs(boolean cleanupJobs) {
        this.cleanupJobs = cleanupJobs;
    }
}
