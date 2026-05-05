package com.autograder.service.job;

/**
 * Signals that the backend is temporarily unable to accept job work.
 *
 * Controllers map this to 503 while services can keep setup-gating logic close
 * to the upload and execution workflows.
 */
public class JobIntakeUnavailableException extends RuntimeException {

    public JobIntakeUnavailableException(String message) {
        super(message);
    }
}
