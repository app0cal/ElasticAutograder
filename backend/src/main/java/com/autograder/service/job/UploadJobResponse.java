package com.autograder.service.job;

import java.util.List;
import java.util.Map;

/**
 * Service-level upload result that preserves the existing frontend response
 * shape while keeping controllers free from job creation details.
 */
public record UploadJobResponse(String message, List<UploadedJobSummary> jobs) {

    public Map<String, Object> toResponseBody() {
        return Map.of(
                "message", message,
                "jobs", jobs.stream()
                        .map(job -> Map.<String, Object>of(
                                "id", job.id(),
                                "fileName", job.fileName()
                        ))
                        .toList()
        );
    }
}
