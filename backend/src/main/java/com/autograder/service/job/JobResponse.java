package com.autograder.service.job;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import com.autograder.model.FailureReason;
import com.autograder.model.Job;
import com.autograder.model.JobStatus;
import com.autograder.model.SubmissionKind;

/**
 * Frontend-safe job payload that hides internal submission storage keys.
 */
public record JobResponse(
        Long id,
        String graderType,
        String originalFilename,
        SubmissionKind submissionKind,
        String graderImage,
        String institutionId,
        String submittedBy,
        JobStatus status,
        FailureReason failureReason,
        String failureMessage,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        OffsetDateTime queuedAt,
        Integer attemptCount,
        Integer maxAttempts,
        OffsetDateTime lastAttemptAt,
        String queueMessageId,
        String workerId,
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
                job.getSubmissionKind(),
                job.getGraderImage(),
                job.getInstitutionId(),
                job.getSubmittedBy(),
                job.getStatus(),
                job.getFailureReason(),
                job.getFailureMessage(),
                job.getCreatedAt(),
                job.getUpdatedAt(),
                job.getStartedAt(),
                job.getFinishedAt(),
                job.getQueuedAt(),
                job.getAttemptCount(),
                job.getMaxAttempts(),
                job.getLastAttemptAt(),
                job.getQueueMessageId(),
                job.getWorkerId(),
                job.getScore(),
                job.getTestsPassed(),
                job.getTestsTotal(),
                job.getResultJson(),
                job.getK8sJobName()
        );
    }
}
