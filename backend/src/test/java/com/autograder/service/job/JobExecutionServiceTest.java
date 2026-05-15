package com.autograder.service.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import com.autograder.model.SubmissionKind;
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
    void enqueueJob_differentInstitution_throwsNotFound() throws Exception {
        Job job = job(14L);
        job.setInstitutionId("university-a");
        when(jobRepository.findById(14L)).thenReturn(Optional.of(job));

        JobNotFoundException exception = assertThrows(
                JobNotFoundException.class,
                () -> service.enqueueJob(14L, new RequestIdentity("university-b", "student-1"))
        );

        assertEquals("Unable to find job object for id 14", exception.getMessage());
    }

    @Test
    void enqueueJob_projectZipJob_returnsAcceptedAndSchedulesWork() throws Exception {
        Job job = job(17L);
        job.setSubmissionKind(SubmissionKind.PROJECT_ZIP);
        when(jobRepository.findById(17L)).thenReturn(Optional.of(job));

        JobEnqueueResponse response = service.enqueueJob(17L, RequestIdentity.localAnonymous());

        assertEquals(202, response.status().value());
        assertEquals("Job 17 queued.", response.body().asText());
        verify(jobQueueService).enqueue(job, RequestIdentity.localAnonymous());
    }

    @Test
    void executeQueuedJob_success_persistsResultAndRetainsSubmission() throws Exception {
        Job job = job(11L);
        job.setSubmissionPath("db:2ee63863-c9ec-4a1f-8ce9-d4db05cc7a5c");
        job.setAttemptCount(1);
        when(jobRepository.findById(11L)).thenReturn(Optional.of(job));
        when(jobRepository.claimQueuedJob(11L, "worker-a")).thenReturn(1);
        when(submissionStorageService.resolveSubmissionKey("db:2ee63863-c9ec-4a1f-8ce9-d4db05cc7a5c", null))
                .thenReturn("db:2ee63863-c9ec-4a1f-8ce9-d4db05cc7a5c");

        ObjectNode result = objectMapper.createObjectNode();
        result.put("status", "SUCCEEDED");
        result.put("tests_passed", 2);
        result.put("tests_total", 2);
        result.putArray("results").addObject().put("name", "case_1").put("passed", true);
        when(jobDispatcher.dispatch(11L, "db:2ee63863-c9ec-4a1f-8ce9-d4db05cc7a5c", SubmissionKind.SINGLE_FILE, "fib", "local")).thenReturn(result);

        service.executeQueuedJob(11L, "worker-a");

        assertEquals(JobStatus.SUCCEEDED, job.getStatus());
        assertEquals(2, job.getTestsPassed());
        assertEquals("grading-job-11", job.getK8sJobName());
        assertEquals(null, job.getWorkerId());
        verify(submissionStorageService, never()).deleteIfExists(any());
    }

    @Test
    void executeQueuedJob_duplicateClaim_skipsExecution() throws Exception {
        when(jobRepository.claimQueuedJob(11L, "worker-a")).thenReturn(0);

        service.executeQueuedJob(11L, "worker-a");

        verify(jobDispatcher, never()).dispatch(any(), any(), any(), any(), any());
    }

    @Test
    void executeQueuedJob_duplicateWorkers_onlyClaimedWorkerExecutes() throws Exception {
        Job job = job(21L);
        job.setSubmissionPath("submission.py");
        job.setAttemptCount(1);
        when(jobRepository.claimQueuedJob(21L, "worker-a")).thenReturn(1);
        when(jobRepository.claimQueuedJob(21L, "worker-b")).thenReturn(0);
        when(jobRepository.findById(21L)).thenReturn(Optional.of(job));
        when(submissionStorageService.resolveSubmissionKey("submission.py", null))
                .thenReturn("submission.py");

        ObjectNode result = objectMapper.createObjectNode();
        result.put("status", "SUCCEEDED");
        result.put("tests_passed", 1);
        result.put("tests_total", 1);
        result.putArray("results").addObject().put("name", "case_1").put("passed", true);
        when(jobDispatcher.dispatch(21L, "submission.py", SubmissionKind.SINGLE_FILE, "fib", "local")).thenReturn(result);

        service.executeQueuedJob(21L, "worker-a");
        service.executeQueuedJob(21L, "worker-b");

        assertEquals(JobStatus.SUCCEEDED, job.getStatus());
        verify(jobDispatcher, times(1)).dispatch(21L, "submission.py", SubmissionKind.SINGLE_FILE, "fib", "local");
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
        job.setAttemptCount(1);
        when(jobRepository.findById(16L)).thenReturn(Optional.of(job));
        when(jobRepository.claimQueuedJob(16L, "worker-a")).thenReturn(1);
        when(submissionStorageService.resolveSubmissionKey("../secret.py", null))
                .thenThrow(new IllegalArgumentException("Invalid file name."));

        service.executeQueuedJob(16L, "worker-a");

        assertEquals(JobStatus.FAILED, job.getStatus());
        assertEquals(FailureReason.CONFIG_ERROR, job.getFailureReason());
        assertEquals("Invalid file name.", job.getFailureMessage());
        assertEquals(null, job.getWorkerId());
    }

    @Test
    void executeQueuedJob_projectZipJobDispatchesWithProjectSubmissionKind() throws Exception {
        Job job = job(23L);
        job.setSubmissionKind(SubmissionKind.PROJECT_ZIP);
        job.setSubmissionPath("project:2ee63863-c9ec-4a1f-8ce9-d4db05cc7a5c");
        job.setAttemptCount(1);
        when(jobRepository.findById(23L)).thenReturn(Optional.of(job));
        when(jobRepository.claimQueuedJob(23L, "worker-a")).thenReturn(1);

        ObjectNode result = objectMapper.createObjectNode();
        result.put("status", "SUCCEEDED");
        result.put("tests_passed", 1);
        result.put("tests_total", 1);
        result.putArray("results").addObject().put("name", "case_1").put("passed", true);
        when(jobDispatcher.dispatch(
                23L,
                "project:2ee63863-c9ec-4a1f-8ce9-d4db05cc7a5c",
                SubmissionKind.PROJECT_ZIP,
                "fib",
                "local"
        )).thenReturn(result);

        service.executeQueuedJob(23L, "worker-a");

        assertEquals(JobStatus.SUCCEEDED, job.getStatus());
        verify(submissionStorageService, never()).resolveSubmissionKey(any(), any());
        verify(jobDispatcher).dispatch(
                23L,
                "project:2ee63863-c9ec-4a1f-8ce9-d4db05cc7a5c",
                SubmissionKind.PROJECT_ZIP,
                "fib",
                "local"
        );
    }

    @Test
    void executeQueuedJob_timeoutFailure_persistsFinalFailure() throws Exception {
        Job job = job(13L);
        job.setSubmissionPath("submission.py");
        job.setAttemptCount(1);
        when(jobRepository.findById(13L)).thenReturn(Optional.of(job));
        when(jobRepository.claimQueuedJob(13L, "worker-a")).thenReturn(1);
        when(submissionStorageService.resolveSubmissionKey("submission.py", null))
                .thenReturn("submission.py");
        when(jobDispatcher.dispatch(13L, "submission.py", SubmissionKind.SINGLE_FILE, "fib", "local"))
                .thenThrow(new GradingFailureException(FailureReason.TIMEOUT, "Timed out"));

        service.executeQueuedJob(13L, "worker-a");

        assertEquals(JobStatus.FAILED, job.getStatus());
        assertEquals(FailureReason.TIMEOUT, job.getFailureReason());
        assertEquals("Timed out", job.getFailureMessage());
        verify(jobQueueService, never()).enqueue(any(), any());
    }

    @Test
    void executeQueuedJob_resourceLimitFailure_persistsFinalFailure() throws Exception {
        Job job = job(15L);
        job.setSubmissionPath("submission.py");
        job.setAttemptCount(1);
        when(jobRepository.findById(15L)).thenReturn(Optional.of(job));
        when(jobRepository.claimQueuedJob(15L, "worker-a")).thenReturn(1);
        when(submissionStorageService.resolveSubmissionKey("submission.py", null))
                .thenReturn("submission.py");
        when(jobDispatcher.dispatch(15L, "submission.py", SubmissionKind.SINGLE_FILE, "fib", "local"))
                .thenThrow(new GradingFailureException(FailureReason.RESOURCE_LIMIT, "OOMKilled"));

        service.executeQueuedJob(15L, "worker-a");

        assertEquals(JobStatus.FAILED, job.getStatus());
        assertEquals(FailureReason.RESOURCE_LIMIT, job.getFailureReason());
        verify(jobQueueService, never()).enqueue(any(), any());
    }

    @Test
    void executeQueuedJob_kubernetesFailureUnderMaxAttempts_requeues() throws Exception {
        Job job = job(19L);
        job.setSubmissionPath("submission.py");
        job.setAttemptCount(1);
        job.setMaxAttempts(3);
        when(jobRepository.findById(19L)).thenReturn(Optional.of(job));
        when(jobRepository.claimQueuedJob(19L, "worker-a")).thenReturn(1);
        when(submissionStorageService.resolveSubmissionKey("submission.py", null))
                .thenReturn("submission.py");
        when(jobDispatcher.dispatch(19L, "submission.py", SubmissionKind.SINGLE_FILE, "fib", "local"))
                .thenThrow(new GradingFailureException(FailureReason.KUBERNETES_ERROR, "Cluster unavailable"));

        service.executeQueuedJob(19L, "worker-a");

        assertEquals(JobStatus.QUEUED, job.getStatus());
        assertEquals(FailureReason.KUBERNETES_ERROR, job.getFailureReason());
        assertEquals(null, job.getWorkerId());
        verify(jobQueueService).enqueue(eq(job), eq(RequestIdentity.localAnonymous()));
    }

    @Test
    void executeQueuedJob_kubernetesFailureAtMaxAttempts_deadLetters() throws Exception {
        Job job = job(20L);
        job.setSubmissionPath("submission.py");
        job.setAttemptCount(3);
        job.setMaxAttempts(3);
        when(jobRepository.findById(20L)).thenReturn(Optional.of(job));
        when(jobRepository.claimQueuedJob(20L, "worker-a")).thenReturn(1);
        when(submissionStorageService.resolveSubmissionKey("submission.py", null))
                .thenReturn("submission.py");
        when(jobDispatcher.dispatch(20L, "submission.py", SubmissionKind.SINGLE_FILE, "fib", "local"))
                .thenThrow(new GradingFailureException(FailureReason.KUBERNETES_ERROR, "Cluster unavailable"));

        service.executeQueuedJob(20L, "worker-a");

        assertEquals(JobStatus.DEAD_LETTERED, job.getStatus());
        assertEquals(FailureReason.KUBERNETES_ERROR, job.getFailureReason());
        assertEquals(null, job.getWorkerId());
        verify(jobQueueService, never()).enqueue(any(), any());
    }

    @Test
    void executeQueuedJob_unknownFailureUnderMaxAttempts_requeues() throws Exception {
        Job job = job(22L);
        job.setSubmissionPath("submission.py");
        job.setAttemptCount(1);
        job.setMaxAttempts(3);
        when(jobRepository.findById(22L)).thenReturn(Optional.of(job));
        when(jobRepository.claimQueuedJob(22L, "worker-a")).thenReturn(1);
        when(submissionStorageService.resolveSubmissionKey("submission.py", null))
                .thenReturn("submission.py");
        when(jobDispatcher.dispatch(22L, "submission.py", SubmissionKind.SINGLE_FILE, "fib", "local"))
                .thenThrow(new RuntimeException("Unexpected worker failure"));

        service.executeQueuedJob(22L, "worker-a");

        assertEquals(JobStatus.QUEUED, job.getStatus());
        assertEquals(FailureReason.UNKNOWN, job.getFailureReason());
        assertEquals(null, job.getWorkerId());
        verify(jobQueueService).enqueue(eq(job), eq(RequestIdentity.localAnonymous()));
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
