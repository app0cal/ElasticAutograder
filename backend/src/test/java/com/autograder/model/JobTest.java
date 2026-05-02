package com.autograder.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;

class JobTest {

    /**
     * Verifies that the Job convenience constructor initializes a queued job correctly.
     * Expected behavior: created/updated timestamps match and failure fields start in a clean state.
     */
    @Test
    void constructor_setsInitialJobState() {
        OffsetDateTime createdAt = OffsetDateTime.now();

        Job job = new Job("submission.py", "fib", createdAt, JobStatus.QUEUED);

        assertEquals("submission.py", job.getOriginalFilename());
        assertEquals("fib", job.getGraderType());
        assertEquals(createdAt, job.getCreatedAt());
        assertEquals(createdAt, job.getUpdatedAt());
        assertEquals(JobStatus.QUEUED, job.getStatus());
        assertEquals(FailureReason.NONE, job.getFailureReason());
        assertNull(job.getFailureMessage());
    }

    /**
     * Verifies that mutable Job fields round-trip through their setters and getters.
     * Expected behavior: persisted job metadata, status, timing, score, result JSON, and Kubernetes name are retained.
     */
    @Test
    void settersAndGetters_roundTripMutableFields() {
        Job job = new Job();
        OffsetDateTime now = OffsetDateTime.now();

        job.setOriginalFilename("submission.py");
        job.setGraderType("fib");
        job.setSubmissionPath("batch/submission.py");
        job.setGraderImage("ea-grader-fibbonaci:v1");
        job.setStatus(JobStatus.SUCCEEDED);
        job.setFailureReason(FailureReason.NONE);
        job.setFailureMessage(null);
        job.setUpdatedAt(now);
        job.setStartedAt(now.minusSeconds(2));
        job.setFinishedAt(now);
        job.setScore(new BigDecimal("100.0"));
        job.setTestsPassed(2);
        job.setTestsTotal(2);
        job.setResultJson("[{\"passed\":true}]");
        job.setK8sJobName("grading-job-1");

        assertEquals("submission.py", job.getOriginalFilename());
        assertEquals("fib", job.getGraderType());
        assertEquals("batch/submission.py", job.getSubmissionPath());
        assertEquals("ea-grader-fibbonaci:v1", job.getGraderImage());
        assertEquals(JobStatus.SUCCEEDED, job.getStatus());
        assertEquals(FailureReason.NONE, job.getFailureReason());
        assertNull(job.getFailureMessage());
        assertEquals(now, job.getUpdatedAt());
        assertEquals(now.minusSeconds(2), job.getStartedAt());
        assertEquals(now, job.getFinishedAt());
        assertEquals(new BigDecimal("100.0"), job.getScore());
        assertEquals(2, job.getTestsPassed());
        assertEquals(2, job.getTestsTotal());
        assertEquals("[{\"passed\":true}]", job.getResultJson());
        assertEquals("grading-job-1", job.getK8sJobName());
    }
}
