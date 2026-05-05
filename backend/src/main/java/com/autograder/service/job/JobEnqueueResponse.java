package com.autograder.service.job;

import org.springframework.http.HttpStatus;

import tools.jackson.databind.JsonNode;

/**
 * API-compatible response for requests that enqueue grading work.
 */
public record JobEnqueueResponse(HttpStatus status, JsonNode body) {
}
