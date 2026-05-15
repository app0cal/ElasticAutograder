package com.autograder.service;

import tools.jackson.databind.JsonNode;

import com.autograder.model.SubmissionKind;

/**
 * Abstraction for running a grading job for a submission.
 * 
 * This allows backend to depend on a grading contract rather than 
 * directly depending on one single implementation.
 * 
 */
public interface GradingOrchestrator {
    JsonNode runJobInKubernetes(
            Long jobId,
            String fileName,
            SubmissionKind submissionKind,
            String graderType,
            String institutionId
    ) throws Exception;
}
