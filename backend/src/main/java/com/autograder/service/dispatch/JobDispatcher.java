package com.autograder.service.dispatch;

import tools.jackson.databind.JsonNode;

/**
 * Dispatch boundary for grading work.
 *
 * The first implementation runs synchronously, while a Redis-backed dispatcher
 * can later enqueue work without changing controllers.
 */
public interface JobDispatcher {

    JsonNode dispatch(Long jobId, String submissionKey, String graderType, String institutionId) throws Exception;
}
