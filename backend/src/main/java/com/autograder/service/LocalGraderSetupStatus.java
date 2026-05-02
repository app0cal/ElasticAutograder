package com.autograder.service;

import org.springframework.stereotype.Component;

/**
 * Tracks whether local grader images are ready for job submission.
 */
@Component
public class LocalGraderSetupStatus {

    private volatile boolean setupInProgress;
    private volatile boolean setupFailed;
    private volatile String message = "Grader setup is ready.";

    public void markSetupRequired() {
        setupInProgress = true;
        setupFailed = false;
        message = "Local grader setup is still running. Please wait for backend startup to finish.";
    }

    public void markReady() {
        setupInProgress = false;
        setupFailed = false;
        message = "Grader setup is ready.";
    }

    public void markFailed(String message) {
        setupInProgress = false;
        setupFailed = true;
        this.message = message;
    }

    public boolean isAcceptingJobs() {
        return !setupInProgress && !setupFailed;
    }

    public String getMessage() {
        return message;
    }
}
