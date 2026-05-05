package com.autograder.service.submission;

/**
 * Logical reference to stored submission content.
 *
 * The key is storage-owned so job services do not depend on local files or
 * distributed storage internals.
 */
public record StoredSubmission(Long submissionId, String key, String originalFileName) {

    public StoredSubmission(String key, String originalFileName) {
        this(null, key, originalFileName);
    }
}
