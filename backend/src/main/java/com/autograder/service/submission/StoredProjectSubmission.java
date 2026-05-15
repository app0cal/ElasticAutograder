package com.autograder.service.submission;

public record StoredProjectSubmission(Long projectId, String key, String originalFileName, int fileCount) {
}
