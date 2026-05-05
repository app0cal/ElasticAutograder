package com.autograder.service.job;

/**
 * Small response DTO for one job created from an upload.
 */
public record UploadedJobSummary(Long id, String fileName) {
}
