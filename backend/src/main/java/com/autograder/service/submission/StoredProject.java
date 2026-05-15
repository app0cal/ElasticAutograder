package com.autograder.service.submission;

import java.util.List;

public record StoredProject(String key, String originalFileName, List<StoredProjectFile> files) {
}
