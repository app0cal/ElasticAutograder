package com.autograder.service.job;

/**
 * Pretty-printed result JSON prepared for the result download endpoint.
 */
public record DownloadedJobResult(String body, boolean attachment) {
}
