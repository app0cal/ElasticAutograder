package com.autograder.service.system;

import java.time.OffsetDateTime;

import com.autograder.model.Job;
import com.autograder.model.JobStatus;

public record QueueHealthJobSummary(
        Long id,
        JobStatus status,
        String graderType,
        String institutionId,
        String submittedBy,
        String workerId,
        OffsetDateTime startedAt,
        OffsetDateTime lastAttemptAt,
        Integer attemptCount,
        Integer maxAttempts
) {

    public static QueueHealthJobSummary fromJob(Job job) {
        return new QueueHealthJobSummary(
                job.getId(),
                job.getStatus(),
                job.getGraderType(),
                job.getInstitutionId(),
                job.getSubmittedBy(),
                job.getWorkerId(),
                job.getStartedAt(),
                job.getLastAttemptAt(),
                job.getAttemptCount(),
                job.getMaxAttempts()
        );
    }
}
