package com.autograder.service.job;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import com.autograder.model.FailureReason;
import com.autograder.model.Job;
import com.autograder.model.JobStatus;

/**
 * Frontend-safe job payload that hides internal submission storage keys.
 */
public record JobResponse(
        Long id,
        String graderType,
        String originalFilename,
        String graderImage,
        JobStatus status,
        FailureReason failureReason,
        String failureMessage,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        BigDecimal score,
        Integer testsPassed,
        Integer testsTotal,
        String resultJson,
        String k8sJobName
) {

    public static JobResponse fromJob(Job job) {
        return new JobResponse(
                job.getId(),
                job.getGraderType(),
                job.getOriginalFilename(),
                job.getGraderImage(),
                job.getStatus(),
                job.getFailureReason(),
                job.getFailureMessage(),
                job.getCreatedAt(),
                job.getUpdatedAt(),
                job.getStartedAt(),
                job.getFinishedAt(),
                job.getScore(),
                job.getTestsPassed(),
                job.getTestsTotal(),
                job.getResultJson(),
                job.getK8sJobName()
        );
    }
}
