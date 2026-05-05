package com.autograder.service.job;

/**
 * Redis queue payload for one grading job execution request.
 */
public record GradingJobMessage(
        Long jobId,
        String submissionKey,
        String graderType,
        String institutionId,
        String requestedBy,
        int attempt
) {
}
