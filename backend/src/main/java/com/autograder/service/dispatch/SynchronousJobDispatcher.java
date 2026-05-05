package com.autograder.service.dispatch;

import org.springframework.stereotype.Service;

import com.autograder.service.GradingOrchestrator;

import tools.jackson.databind.JsonNode;

/**
 * Immediate dispatcher that preserves the current request-driven grading flow.
 */
@Service
public class SynchronousJobDispatcher implements JobDispatcher {

    private final GradingOrchestrator gradingOrchestrator;

    public SynchronousJobDispatcher(GradingOrchestrator gradingOrchestrator) {
        this.gradingOrchestrator = gradingOrchestrator;
    }

    @Override
    public JsonNode dispatch(Long jobId, String submissionKey, String graderType, String institutionId) throws Exception {
        return gradingOrchestrator.runJobInKubernetes(jobId, submissionKey, graderType, institutionId);
    }
}
