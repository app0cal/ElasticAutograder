package com.autograder.service.submission;

public record StoredProjectFile(String relativePath, String content, String contentType, long sizeBytes) {
}
