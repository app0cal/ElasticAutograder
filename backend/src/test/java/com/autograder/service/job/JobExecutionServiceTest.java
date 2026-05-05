package com.autograder.service.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.OffsetDateTime;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.autograder.model.FailureReason;
import com.autograder.model.Job;
import com.autograder.model.JobStatus;
import com.autograder.repository.JobRepository;
import com.autograder.service.GradingFailureException;
import com.autograder.service.LocalGraderSetupStatus;
import com.autograder.service.dispatch.JobDispatcher;
import com.autograder.service.identity.RequestIdentity;
import com.autograder.service.submission.SubmissionStorageService;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class JobExecutionServiceTest {

    private JobRepository jobRepository;
    private SubmissionStorageService submissionStorageService;
    private JobDispatcher jobDispatcher;
    private JobQueueService jobQueueService;
    private JobExecutionService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        jobRepository = Mockito.mock(JobRepository.class);
        submissionStorageService = Mockito.mock(SubmissionStorageService.class);
        jobDispatcher = Mockito.mock(JobDispatcher.class);
        jobQueueService = Mockito.mock(JobQueueService.class);
        service = new JobExecutionService(
                jobRepository,
                new LocalGraderSetupStatus(),
                submissionStorageService,
                jobDispatcher,
                new JobResultMapper(),
                jobQueueService
        );

        when(jobRepository.saveAndFlush(any(Job.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void enqueueJob_queuedJob_returnsAcceptedAndSchedulesWork() throws Exception {
        Job job = job(11L);
        when(jobRepository.findById(11L)).thenReturn(Optional.of(job));

        JobEnqueueResponse response = service.enqueueJob(11L, RequestIdentity.localAnonymous());

        assertEquals(202, response.status().value());
        assertEquals("Job 11 queued.", response.body().asText());
        verify(jobQueueService).enqueue(job, RequestIdentity.localAnonymous());
    }

    @Test
    void enqueueJob_nonQueuedJob_returnsBadRequest() throws Exception {
        Job job = job(12L);
        job.setStatus(JobStatus.RUNNING);
        when(jobRepository.findById(12L)).thenReturn(Optional.of(job));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> service.enqueueJob(12L, RequestIdentity.localAnonymous())
        );

        assertEquals("Job 12 is not queued.", exception.getMessage());
    }

    @Test
    void executeQueuedJob_success_persistsResultAndRetainsSubmission() throws Exception {
        Job job = job(11L);
        job.setSubmissionPath("db:2ee63863-c9ec-4a1f-8ce9-d4db05cc7a5c");
        when(jobRepository.findById(11L)).thenReturn(Optional.of(job));
        when(submissionStorageService.resolveSubmissionKey("db:2ee63863-c9ec-4a1f-8ce9-d4db05cc7a5c", null))
                .thenReturn("db:2ee63863-c9ec-4a1f-8ce9-d4db05cc7a5c");

        ObjectNode result = objectMapper.createObjectNode();
        result.put("status", "SUCCEEDED");
        result.put("tests_passed", 2);
        result.put("tests_total", 2);
        result.putArray("results").addObject().put("name", "case_1").put("passed", true);
        when(jobDispatcher.dispatch(11L, "db:2ee63863-c9ec-4a1f-8ce9-d4db05cc7a5c", "fib")).thenReturn(result);

        service.executeQueuedJob(11L);

        assertEquals(JobStatus.SUCCEEDED, job.getStatus());
        assertEquals(2, job.getTestsPassed());
        assertEquals("grading-job-11", job.getK8sJobName());
        verify(submissionStorageService, never()).deleteIfExists(any());
    }

    @Test
    void enqueueJob_missingJob_throwsNotFound() {
        when(jobRepository.findById(404L)).thenReturn(Optional.empty());

        JobNotFoundException exception = assertThrows(
                JobNotFoundException.class,
                () -> service.enqueueJob(404L, RequestIdentity.localAnonymous())
        );

        assertEquals("Unable to find job object for id 404", exception.getMessage());
    }

    @Test
    void executeQueuedJob_invalidSubmissionPath_persistsConfigError() throws Exception {
        Job job = job(16L);
        job.setSubmissionPath("../secret.py");
        when(jobRepository.findById(16L)).thenReturn(Optional.of(job));
        when(submissionStorageService.resolveSubmissionKey("../secret.py", null))
                .thenThrow(new IllegalArgumentException("Invalid file name."));

        service.executeQueuedJob(16L);

        assertEquals(JobStatus.FAILED, job.getStatus());
        assertEquals(FailureReason.CONFIG_ERROR, job.getFailureReason());
        assertEquals("Invalid file name.", job.getFailureMessage());
    }

    @Test
    void executeQueuedJob_gradingFailure_persistsStructuredFailure() throws Exception {
        Job job = job(13L);
        job.setSubmissionPath("submission.py");
        when(jobRepository.findById(13L)).thenReturn(Optional.of(job));
        when(submissionStorageService.resolveSubmissionKey("submission.py", null))
                .thenReturn("submission.py");
        when(jobDispatcher.dispatch(13L, "submission.py", "fib"))
                .thenThrow(new GradingFailureException(FailureReason.TIMEOUT, "Timed out"));

        service.executeQueuedJob(13L);

        assertEquals(JobStatus.FAILED, job.getStatus());
        assertEquals(FailureReason.TIMEOUT, job.getFailureReason());
        assertEquals("Timed out", job.getFailureMessage());
    }

    @Test
    void updateJob_existingJob_appliesResults() throws Exception {
        Job job = job(18L);
        when(jobRepository.findById(18L)).thenReturn(Optional.of(job));

        ObjectNode result = objectMapper.createObjectNode();
        result.put("status", "PARTIAL");
        result.put("tests_passed", 1);
        result.put("tests_total", 2);
        result.putArray("results").addObject().put("name", "case_1").put("passed", false);

        service.updateJob(18L, result);

        assertEquals(JobStatus.PARTIAL, job.getStatus());
        assertEquals(1, job.getTestsPassed());
        assertTrue(job.getResultJson().contains("case_1"));
    }

    private Job job(Long id) throws Exception {
        Job job = new Job("submission.py", "fib", OffsetDateTime.now(), JobStatus.QUEUED);
        Field idField = Job.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(job, id);
        return job;
    }
}
