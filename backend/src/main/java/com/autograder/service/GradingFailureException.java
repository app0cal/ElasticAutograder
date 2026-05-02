package com.autograder.service;

import com.autograder.model.FailureReason;

/**
 * Runtime exception used when a grading job fails during execution.
 *
 * This allows the grading/orchestration layer to throw a structured failure
 * that includes both:
 * - a human-readable message 
 * - a FailureReason enum value,
 * so the controller can store consistent failure details in the Job record.
 */
public class GradingFailureException extends RuntimeException {

    // Structured category associated with the grading error
    private final FailureReason failureReason;

    /**
     * Creates a grading failure exception with a failure reason and message.
     *
     * @param failureReason structured failure category
     * @param message readable description of what went wrong
     */
    public GradingFailureException(FailureReason failureReason, String message) {
        super(message);
        this.failureReason = failureReason;
    }

    /**
     * Creates a grading failure exception with a failure reason, message,
     * and original underlying cause.
     *
     * @param failureReason structured failure category
     * @param message readable description of what went wrong
     * @param cause original exception that triggered this grading failure
     */
    public GradingFailureException(FailureReason failureReason, String message, Throwable cause) {
        super(message, cause);
        this.failureReason = failureReason;
    }

    // Getter for failureReason 
    public FailureReason getFailureReason() {
        return failureReason;
    }
}