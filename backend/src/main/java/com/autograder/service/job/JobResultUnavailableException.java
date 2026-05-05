package com.autograder.service.job;

/**
 * Indicates that a job exists but does not have stored result JSON yet.
 */
public class JobResultUnavailableException extends RuntimeException {

    public JobResultUnavailableException(String message) {
        super(message);
    }
}
