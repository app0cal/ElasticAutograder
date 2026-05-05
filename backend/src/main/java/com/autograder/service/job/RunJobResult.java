package com.autograder.service.job;

import org.springframework.http.HttpStatus;

import tools.jackson.databind.JsonNode;

/**
 * Outcome of an attempted job run, including the HTTP status expected by the
 * current API contract and the JSON body returned to the frontend.
 */
public record RunJobResult(HttpStatus status, JsonNode body) {
}
