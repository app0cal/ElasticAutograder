package com.autograder.service.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.mock.web.MockMultipartFile;

import com.autograder.model.GraderDefinition;
import com.autograder.model.Job;
import com.autograder.model.JobStatus;
import com.autograder.repository.JobRepository;
import com.autograder.service.GraderRegistry;
import com.autograder.service.LocalGraderSetupStatus;
import com.autograder.service.identity.RequestIdentity;
import com.autograder.service.submission.StoredSubmission;
import com.autograder.service.submission.SubmissionStorageService;

class JobSubmissionServiceTest {

    private JobRepository jobRepository;
    private GraderRegistry graderRegistry;
    private SubmissionStorageService submissionStorageService;
    private JobExecutionService jobExecutionService;
    private JobSubmissionService service;

    @BeforeEach
    void setUp() {
        jobRepository = Mockito.mock(JobRepository.class);
        graderRegistry = Mockito.mock(GraderRegistry.class);
        submissionStorageService = Mockito.mock(SubmissionStorageService.class);
        jobExecutionService = Mockito.mock(JobExecutionService.class);
        service = new JobSubmissionService(
                jobRepository,
                graderRegistry,
                new LocalGraderSetupStatus(),
                submissionStorageService,
                jobExecutionService
        );

        when(graderRegistry.getRequired("fib")).thenReturn(grader());
        when(jobRepository.save(any(Job.class))).thenAnswer(invocation -> {
            Job job = invocation.getArgument(0);
            setJobId(job, 1L);
            return job;
        });
    }

    @Test
    void upload_singleFile_createsQueuedJob() throws Exception {
        when(submissionStorageService.isZipUpload(any())).thenReturn(false);
        when(submissionStorageService.storeSingle(any()))
                .thenReturn(new StoredSubmission(44L, "db:2ee63863-c9ec-4a1f-8ce9-d4db05cc7a5c", "submission.py"));

        UploadJobResponse response = service.upload(file(), "fib", RequestIdentity.localAnonymous());

        assertEquals("Successfully queued file.", response.message());
        assertEquals(1, response.jobs().size());

        ArgumentCaptor<Job> savedJob = ArgumentCaptor.forClass(Job.class);
        verify(jobRepository).save(savedJob.capture());
        assertEquals("db:2ee63863-c9ec-4a1f-8ce9-d4db05cc7a5c", savedJob.getValue().getSubmissionPath());
        assertEquals(44L, savedJob.getValue().getSubmissionId());
        assertEquals("fib", savedJob.getValue().getGraderType());
        assertEquals(JobStatus.QUEUED, savedJob.getValue().getStatus());
        assertEquals("ea-grader-fib:v1", savedJob.getValue().getGraderImage());
        verify(jobExecutionService).enqueueJob(1L, RequestIdentity.localAnonymous());
    }

    @Test
    void upload_zipBatch_createsOneJobPerSubmission() throws Exception {
        when(submissionStorageService.isZipUpload(any())).thenReturn(true);
        when(submissionStorageService.storeZip(any())).thenReturn(List.of(
                new StoredSubmission(51L, "db:0884c63f-9d50-4055-9a1a-69ec4507ba2e", "alpha.py"),
                new StoredSubmission(52L, "db:d2bddbb4-479d-43f7-aee2-e3bb4d818ff1", "beta.py")
        ));

        UploadJobResponse response = service.upload(file(), "fib", RequestIdentity.localAnonymous());

        assertEquals("Successfully queued batch.", response.message());
        assertEquals(2, response.jobs().size());
        verify(jobRepository, times(2)).save(any(Job.class));
        verify(jobExecutionService, times(2)).enqueueJob(any(), any());
    }

    @Test
    void upload_unknownGrader_rejectsBeforeStorage() throws Exception {
        when(graderRegistry.getRequired("missing"))
                .thenThrow(new IllegalArgumentException("Unknown grader key: missing"));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> service.upload(file(), "missing", RequestIdentity.localAnonymous())
        );

        assertEquals("Unknown grader key: missing", exception.getMessage());
        verify(submissionStorageService, times(0)).storeSingle(any());
        verify(submissionStorageService, times(0)).storeZip(any());
    }

    @Test
    void upload_saveFailure_cleansStoredSubmissions() throws Exception {
        when(submissionStorageService.isZipUpload(any())).thenReturn(false);
        when(submissionStorageService.storeSingle(any()))
                .thenReturn(new StoredSubmission(44L, "db:2ee63863-c9ec-4a1f-8ce9-d4db05cc7a5c", "submission.py"));
        when(jobRepository.save(any(Job.class))).thenThrow(new RuntimeException("database down"));

        assertThrows(
                RuntimeException.class,
                () -> service.upload(file(), "fib", RequestIdentity.localAnonymous())
        );

        verify(submissionStorageService).deleteIfExists("db:2ee63863-c9ec-4a1f-8ce9-d4db05cc7a5c");
    }

    @Test
    void upload_blankGraderType_rejectsRequest() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> service.upload(file(), "   ", RequestIdentity.localAnonymous())
        );

        assertEquals("graderType is required.", exception.getMessage());
    }

    private MockMultipartFile file() {
        return new MockMultipartFile("file", "submission.py", "text/plain", "print('hello')".getBytes());
    }

    private GraderDefinition grader() {
        return new GraderDefinition("fib", "Fibonacci", "ea-grader-fib:v1", "/app/grader/manifest.json");
    }

    private void setJobId(Job job, Long id) throws Exception {
        var idField = Job.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(job, id);
    }
}
