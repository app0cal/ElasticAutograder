package com.autograder.service.submission;

/**
 * Logical reference to a staged submission file.
 *
 * The key is intentionally storage-relative so job services do not depend on
 * the local filesystem path layout.
 */
public record StoredSubmission(String key, String originalFileName) {
}
