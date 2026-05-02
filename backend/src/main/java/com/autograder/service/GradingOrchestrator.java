package com.autograder.service;

import tools.jackson.databind.JsonNode;

/**
 * Abstraction for running a grading job for a submission.
 * 
 * This allows backend to depend on a grading contract rather than 
 * directly depending on one single implementation.
 * 
 */
public interface GradingOrchestrator {
    JsonNode runJobInKubernetes(Long jobId, String fileName, String graderType) throws Exception;
}