package com.autograder.controller;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import com.autograder.model.FailureReason;
import com.autograder.model.GraderDefinition;
import com.autograder.model.Job;
import com.autograder.model.JobStatus;
import com.autograder.repository.JobRepository;
import com.autograder.service.Fabric8GradingOrchestrator;
import com.autograder.service.GradingFailureException;
import com.autograder.service.GradingOrchestrator;
import com.autograder.service.GraderRegistry;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.ArrayNode;

public class JobControllerTest {

    private JobRepository jobRepository;
    private JobController jobController;

    // new part of the mock testing 
    private GradingOrchestrator gradingOrchestrator;
    private Fabric8GradingOrchestrator fabric8GradingOrchestrator;
    private GraderRegistry graderRegistry;
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @BeforeEach
    void setUp() throws Exception {
        jobRepository = Mockito.mock(JobRepository.class);
        gradingOrchestrator = Mockito.mock(GradingOrchestrator.class);
        fabric8GradingOrchestrator = Mockito.mock(Fabric8GradingOrchestrator.class);
        graderRegistry = Mockito.mock(GraderRegistry.class);

        jobController = new JobController(
                jobRepository,
                gradingOrchestrator,
                fabric8GradingOrchestrator,
                graderRegistry
        );

        when(graderRegistry.getRequired(any(String.class)))
                .thenReturn(new GraderDefinition("fib", "Fibonacci", "python:3.12", "manifest.json"));
        

        // ensure upload directory starts clean
        Path uploadDir = Path.of("grading/uploads");
        if (Files.exists(uploadDir)) {
            Files.walk(uploadDir)
                    .sorted((a,b) -> b.compareTo(a))
                    .forEach(p -> {
                        try { Files.delete(p); } catch (Exception ignored) {}
                    });
        }

        // mock repository save to assign an ID
        when(jobRepository.save(any(Job.class))).thenAnswer(invocation -> {
            Job job = invocation.getArgument(0);

            Field idField = Job.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(job, 1L);

            return job;
        });
        when(jobRepository.saveAndFlush(any(Job.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    /**
     * Verifies that a valid single-file upload creates one queued job.
     * Expected behavior: the controller returns 200 with a success message and uploaded file name.
     */
    @Test
    void uploadFile_validFile_returns200WithMessage() {

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "test_submission.py",
                "text/plain",
                "print('hello')".getBytes()
        );

        ResponseEntity<Map<String,Object>> response = jobController.uploadFile(file,"fib");

        assertEquals(200, response.getStatusCode().value());
        assertEquals("Successfully uploaded file.", response.getBody().get("message"));
        List<Map<String, Object>> jobs = castJobs(response.getBody().get("jobs"));
        assertEquals(1, jobs.size());
        assertEquals("test_submission.py", jobs.get(0).get("fileName"));
    }

    /**
     * Verifies that duplicate staged upload names are rejected.
     * Expected behavior: the second upload returns 400 and explains that the file already exists.
     */
    @Test
    void uploadFile_duplicateFile_returns409() {

        String name = "duplicate_test.py";

        MockMultipartFile file = new MockMultipartFile(
                "file",
                name,
                "text/plain",
                "print('hello')".getBytes()
        );

        ResponseEntity<Map<String,Object>> first = jobController.uploadFile(file,"fib");
        assertEquals(200, first.getStatusCode().value());

        ResponseEntity<Map<String,Object>> second = jobController.uploadFile(file,"fib");
        assertEquals(400, second.getStatusCode().value());
        assertEquals("File with this name already exists.", second.getBody().get("message"));
    }

    /**
     * Verifies that a zip upload creates one job per file in the archive.
     * Expected behavior: the response reports a successful batch and returns all created job file names.
     */
    @Test
    void uploadFile_zipBatch_returnsCreatedJobs() throws Exception {
        MockMultipartFile zipFile = new MockMultipartFile(
                "file",
                "batch.zip",
                "application/zip",
                zipBytes(
                        Map.of(
                                "alpha.py", "print('alpha')",
                                "nested/beta.py", "print('beta')"
                        )
                )
        );

        ResponseEntity<Map<String, Object>> response = jobController.uploadFile(zipFile, "fib");

        assertEquals(200, response.getStatusCode().value());
        assertEquals("Successfully uploaded batch.", response.getBody().get("message"));

        List<Map<String, Object>> jobs = castJobs(response.getBody().get("jobs"));
        assertEquals(2, jobs.size());
        List<String> fileNames = jobs.stream()
                .map(job -> job.get("fileName").toString())
                .sorted(Comparator.naturalOrder())
                .toList();
        assertEquals(List.of("alpha.py", "beta.py"), fileNames);

        ArgumentCaptor<Job> savedJobs = ArgumentCaptor.forClass(Job.class);
        verify(jobRepository, times(2)).save(savedJobs.capture());
        List<String> submissionPaths = savedJobs.getAllValues().stream()
                .map(Job::getSubmissionPath)
                .sorted(Comparator.naturalOrder())
                .toList();
        assertEquals(List.of("batch.zip/alpha.py", "batch.zip/nested/beta.py"), submissionPaths);
    }

    /**
     * Verifies that duplicate zip upload names receive a numeric suffix.
     * Expected behavior: existing extracted uploads are preserved and the new jobs point at the suffixed directory.
     */
    @Test
    void uploadFile_duplicateZipName_usesNextAvailableDirectory() throws Exception {
        Files.createDirectories(Path.of("grading/uploads/batch.zip"));
        MockMultipartFile zipFile = new MockMultipartFile(
                "file",
                "batch.zip",
                "application/zip",
                zipBytes(
                        Map.of(
                                "alpha.py", "print('alpha')"
                        )
                )
        );

        ResponseEntity<Map<String, Object>> response = jobController.uploadFile(zipFile, "fib");

        assertEquals(200, response.getStatusCode().value());
        assertEquals("Successfully uploaded file.", response.getBody().get("message"));

        ArgumentCaptor<Job> savedJobs = ArgumentCaptor.forClass(Job.class);
        verify(jobRepository).save(savedJobs.capture());
        assertEquals("batch-2.zip/alpha.py", savedJobs.getValue().getSubmissionPath());
    }

    /**
     * Verifies that zip archives cannot contain duplicate base file names.
     * Expected behavior: the upload is rejected so later extracted files cannot overwrite or confuse earlier ones.
     */
    @Test
    void uploadFile_zipWithDuplicateBasenames_returns400() throws Exception {
        MockMultipartFile zipFile = new MockMultipartFile(
                "file",
                "duplicates.zip",
                "application/zip",
                zipBytes(
                        Map.of(
                                "first/duplicate.py", "print('a')",
                                "second/duplicate.py", "print('b')"
                        )
                )
        );

        ResponseEntity<Map<String, Object>> response = jobController.uploadFile(zipFile, "fib");

        assertEquals(400, response.getStatusCode().value());
        assertEquals("Zip archive contains duplicate file names: duplicate.py", response.getBody().get("message"));
    }

    /**
     * Verifies that empty zip archives are not accepted as submissions.
     * Expected behavior: the controller returns 400 with a message that no files were found.
     */
    @Test
    void uploadFile_emptyZip_returns400() throws Exception {
        MockMultipartFile zipFile = new MockMultipartFile(
                "file",
                "empty.zip",
                "application/zip",
                zipBytes(Map.of())
        );

        ResponseEntity<Map<String, Object>> response = jobController.uploadFile(zipFile, "fib");

        assertEquals(400, response.getStatusCode().value());
        assertEquals("Zip archive does not contain any files.", response.getBody().get("message"));
    }

    /**
     * Verifies that zip archives containing only directories are rejected.
     * Expected behavior: the controller treats the archive as empty and returns a validation error.
     */
    @Test
    void uploadFile_zipWithOnlyDirectories_returns400() throws Exception {
        MockMultipartFile zipFile = new MockMultipartFile(
                "file",
                "dirs.zip",
                "application/zip",
                zipBytesWithDirectoriesOnly("nested/", "nested/deeper/")
        );

        ResponseEntity<Map<String, Object>> response = jobController.uploadFile(zipFile, "fib");

        assertEquals(400, response.getStatusCode().value());
        assertEquals("Zip archive does not contain any files.", response.getBody().get("message"));
    }

    /**
     * Verifies that unsafe zip entry paths cannot escape the upload directory.
     * Expected behavior: path traversal entries are rejected with a 400 response.
     */
    @Test
    void uploadFile_zipWithPathTraversal_returns400() throws Exception {
        MockMultipartFile zipFile = new MockMultipartFile(
                "file",
                "unsafe.zip",
                "application/zip",
                zipBytes(
                        Map.of(
                                "../secret.py", "print('bad')"
                        )
                )
        );

        ResponseEntity<Map<String, Object>> response = jobController.uploadFile(zipFile, "fib");

        assertEquals(400, response.getStatusCode().value());
        assertEquals("Zip archive contains an invalid file path.", response.getBody().get("message"));
    }

    /**
     * Verifies that upload fails when the selected grader key is not registered.
     * Expected behavior: the controller returns 400 and does not create any jobs.
     */
    @Test
    void uploadFile_unknownGrader_returns400() {
        when(graderRegistry.getRequired("missing"))
                .thenThrow(new IllegalArgumentException("Unknown grader key: missing"));
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "submission.py",
                "text/plain",
                "print('hello')".getBytes()
        );

        ResponseEntity<Map<String, Object>> response = jobController.uploadFile(file, "missing");

        assertEquals(400, response.getStatusCode().value());
        assertEquals("Unknown grader key: missing", response.getBody().get("message"));
        assertEquals(List.of(), response.getBody().get("jobs"));
    }

    /**
     * Verifies that uploaded files must have a non-blank original name.
     * Expected behavior: blank file names are rejected before writing to disk.
     */
    @Test
    void uploadFile_blankFileName_returns400() {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "   ",
                "text/plain",
                "print('hello')".getBytes()
        );

        ResponseEntity<Map<String, Object>> response = jobController.uploadFile(file, "fib");

        assertEquals(400, response.getStatusCode().value());
        assertEquals("File name is required.", response.getBody().get("message"));
    }

    /**
     * Verifies that single-file uploads cannot use path traversal names.
     * Expected behavior: unsafe file names return 400 and are not staged.
     */
    @Test
    void uploadFile_pathTraversalFileName_returns400() {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "../submission.py",
                "text/plain",
                "print('hello')".getBytes()
        );

        ResponseEntity<Map<String, Object>> response = jobController.uploadFile(file, "fib");

        assertEquals(400, response.getStatusCode().value());
        assertEquals("Invalid file name.", response.getBody().get("message"));
    }

    /**
     * Verifies that filesystem write failures are reported gracefully.
     * Expected behavior: the controller returns 500 and an empty jobs list when bytes cannot be saved.
     */
    @Test
    void uploadFile_ioFailure_returns500() {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "io-failure.py",
                "text/plain",
                "print('hello')".getBytes()
        ) {
            @Override
            public byte[] getBytes() throws java.io.IOException {
                throw new java.io.IOException("disk full");
            }
        };

        ResponseEntity<Map<String, Object>> response = jobController.uploadFile(file, "fib");

        assertEquals(500, response.getStatusCode().value());
        assertEquals("Failed to save uploaded file.", response.getBody().get("message"));
        assertEquals(List.of(), response.getBody().get("jobs"));
    }

    /**
     * Verifies that the recent jobs endpoint returns repository results.
     * Expected behavior: the controller returns 200 with the same ordered jobs provided by the repository.
     */
    @Test
    void getRecentJobs_returnsRepositoryResults() {
        Job first = new Job("a.py", "fib", OffsetDateTime.now(), JobStatus.QUEUED);
        Job second = new Job("b.py", "fib", OffsetDateTime.now(), JobStatus.SUCCEEDED);
        when(jobRepository.findAllOrderByCreatedAtDesc()).thenReturn(List.of(first, second));

        ResponseEntity<List<Job>> response = jobController.getRecentJobs();

        assertEquals(200, response.getStatusCode().value());
        assertEquals(List.of(first, second), response.getBody());
    }

    /**
     * Verifies that grader definitions are converted into frontend option DTOs.
     * Expected behavior: the endpoint returns only the key, label, summary, and details needed by the UI.
     */
    @Test
    void getGraders_returnsFrontendOptionDtos() {
        GraderDefinition fib = new GraderDefinition();
        fib.setKey("fib");
        fib.setLabel("Fibonacci");
        fib.setSummary("Dynamic programming warm-up.");
        fib.setDetails(List.of("Return the nth Fibonacci number."));
        when(graderRegistry.getAll()).thenReturn(List.of(fib));

        ResponseEntity<List<com.autograder.dto.GraderOptionResponse>> response = jobController.getGraders();

        assertEquals(200, response.getStatusCode().value());
        assertEquals(1, response.getBody().size());
        assertEquals("fib", response.getBody().get(0).getKey());
        assertEquals("Fibonacci", response.getBody().get(0).getLabel());
        assertEquals("Dynamic programming warm-up.", response.getBody().get(0).getSummary());
        assertEquals(List.of("Return the nth Fibonacci number."), response.getBody().get(0).getDetails());
    }

    /**
     * Verifies that job details are returned when the job id exists.
     * Expected behavior: the controller returns 200 with the matching Job entity.
     */
    @Test
    void getJobById_existingId_returns200WithJob() {
        Job job = new Job("submission.py", "fib", OffsetDateTime.now(), JobStatus.QUEUED);
        when(jobRepository.findById(7L)).thenReturn(Optional.of(job));

        ResponseEntity<?> response = jobController.getJobById(7L);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(job, response.getBody());
    }

    /**
     * Verifies that missing job detail requests return a not-found response.
     * Expected behavior: the controller returns 404 with a clear missing-id message.
     */
    @Test
    void getJobById_missingId_returns404() {
        when(jobRepository.findById(77L)).thenReturn(Optional.empty());

        ResponseEntity<?> response = jobController.getJobById(77L);

        assertEquals(404, response.getStatusCode().value());
        assertEquals("Unable to find job with id: 77", response.getBody());
    }

    /**
     * Verifies that partial grader results are persisted correctly.
     * Expected behavior: the job becomes PARTIAL, score/test counts are stored, and failure fields stay clear.
     */
    @Test
    void runJob_partialResult_persistsPartialStatusAndResults() throws Exception {
        Job job = new Job("submission.py", "fib", OffsetDateTime.now(), JobStatus.QUEUED);
        setJobId(job, 11L);
        job.setSubmissionPath("batch-123/submission.py");
        when(jobRepository.findById(11L)).thenReturn(Optional.of(job));

        ObjectNode result = objectMapper.createObjectNode();
        result.put("status", "PARTIAL");
        result.put("tests_passed", 1);
        result.put("tests_total", 2);
        result.put("score", new BigDecimal("50.0"));
        ArrayNode results = result.putArray("results");
        ObjectNode entry = results.addObject();
        entry.put("kind", "test");
        entry.put("name", "case_1");
        entry.put("passed", false);
        entry.put("message", "Expected 55, got 34");

        when(gradingOrchestrator.runJobInKubernetes(11L, "batch-123/submission.py", "fib")).thenReturn(result);

        ResponseEntity<?> response = jobController.runJob(11L, "\"submission.py\"");

        assertEquals(200, response.getStatusCode().value());
        assertEquals(JobStatus.PARTIAL, job.getStatus());
        assertEquals(1, job.getTestsPassed());
        assertEquals(2, job.getTestsTotal());
        assertEquals(0, new BigDecimal("50.0").compareTo(job.getScore()));
        assertEquals(FailureReason.NONE, job.getFailureReason());
        assertNull(job.getFailureMessage());
        assertTrue(job.getResultJson().contains("\"case_1\""));
        assertEquals("batch-123/submission.py", job.getSubmissionPath());
    }

    /**
     * Verifies that failed grader results with valid uploads are treated as wrong answers.
     * Expected behavior: the job is marked FAILED with WRONG_ANSWER and saved result details.
     */
    @Test
    void runJob_zeroPassResult_persistsFailedStatusAndWrongAnswerReason() throws Exception {
        Job job = new Job("submission.py", "fib", OffsetDateTime.now(), JobStatus.QUEUED);
        setJobId(job, 14L);
        job.setSubmissionPath("batch-123/submission.py");
        when(jobRepository.findById(14L)).thenReturn(Optional.of(job));

        ObjectNode result = objectMapper.createObjectNode();
        result.put("status", "FAILED");
        result.put("validation_passed", true);
        result.put("tests_passed", 0);
        result.put("tests_total", 2);
        result.put("score", new BigDecimal("0.0"));
        result.put("error_message", "No test cases passed.");
        ArrayNode results = result.putArray("results");
        ObjectNode entry = results.addObject();
        entry.put("kind", "test");
        entry.put("name", "case_1");
        entry.put("passed", false);
        entry.put("message", "Expected 5, got 0");

        when(gradingOrchestrator.runJobInKubernetes(14L, "batch-123/submission.py", "fib")).thenReturn(result);

        ResponseEntity<?> response = jobController.runJob(14L, "\"submission.py\"");

        assertEquals(200, response.getStatusCode().value());
        assertEquals(JobStatus.FAILED, job.getStatus());
        assertEquals(FailureReason.WRONG_ANSWER, job.getFailureReason());
        assertEquals("No test cases passed.", job.getFailureMessage());
        assertTrue(job.getResultJson().contains("\"case_1\""));
    }

    /**
     * Verifies that grader validation failures are stored as invalid uploads.
     * Expected behavior: the job is marked FAILED with INVALID_UPLOAD and the validation message is saved.
     */
    @Test
    void runJob_validationFailure_persistsInvalidUploadReason() throws Exception {
        Job job = new Job("dog.py", "fib", OffsetDateTime.now(), JobStatus.QUEUED);
        setJobId(job, 15L);
        job.setSubmissionPath("batch-123/dog.py");
        when(jobRepository.findById(15L)).thenReturn(Optional.of(job));

        ObjectNode result = objectMapper.createObjectNode();
        result.put("status", "FAILED");
        result.put("validation_passed", false);
        result.put("tests_passed", 0);
        result.put("tests_total", 0);
        result.put("score", new BigDecimal("0.0"));
        result.put("error_message", "submission is missing callable function 'fib'");
        ArrayNode results = result.putArray("results");
        ObjectNode entry = results.addObject();
        entry.put("kind", "validation");
        entry.put("name", "validation_check");
        entry.put("passed", false);
        entry.put("message", "submission is missing callable function 'fib'");

        when(gradingOrchestrator.runJobInKubernetes(15L, "batch-123/dog.py", "fib")).thenReturn(result);

        ResponseEntity<?> response = jobController.runJob(15L, "\"dog.py\"");

        assertEquals(200, response.getStatusCode().value());
        assertEquals(JobStatus.FAILED, job.getStatus());
        assertEquals(FailureReason.INVALID_UPLOAD, job.getFailureReason());
        assertEquals("submission is missing callable function 'fib'", job.getFailureMessage());
    }

    /**
     * Verifies that runJob can fall back to the request body when no submission path is stored.
     * Expected behavior: the job succeeds, the submission path is saved, and the staged upload is deleted.
     */
    @Test
    void runJob_withoutStoredPath_usesRequestBodyAndDeletesUpload() throws Exception {
        Path uploadDir = Path.of("grading/uploads");
        Files.createDirectories(uploadDir);
        Path upload = uploadDir.resolve("body-submission.py");
        Files.writeString(upload, "def fib(n): return n");

        Job job = new Job("body-submission.py", "fib", OffsetDateTime.now(), JobStatus.QUEUED);
        setJobId(job, 24L);
        when(jobRepository.findById(24L)).thenReturn(Optional.of(job));

        ObjectNode result = objectMapper.createObjectNode();
        result.put("status", "SUCCEEDED");
        result.put("tests_passed", 2);
        result.put("tests_total", 2);
        result.put("score", new BigDecimal("100.0"));
        result.putArray("results").addObject().put("name", "case_1").put("passed", true);

        when(gradingOrchestrator.runJobInKubernetes(24L, "body-submission.py", "fib")).thenReturn(result);

        ResponseEntity<?> response = jobController.runJob(24L, "\"body-submission.py\"");

        assertEquals(200, response.getStatusCode().value());
        assertEquals(JobStatus.SUCCEEDED, job.getStatus());
        assertEquals("body-submission.py", job.getSubmissionPath());
        assertFalse(Files.exists(upload));
    }

    /**
     * Verifies that stored result JSON can be downloaded as an attachment.
     * Expected behavior: the response is JSON and includes a results.json content disposition header.
     */
    @Test
    void downloadResults_partialJobWithStoredJson_returnsAttachment() throws Exception {
        Job job = new Job("submission.py", "fib", OffsetDateTime.now(), JobStatus.PARTIAL);
        setJobId(job, 12L);
        job.setResultJson("[{\"kind\":\"test\",\"name\":\"case_1\",\"passed\":false}]");
        when(jobRepository.findById(12L)).thenReturn(Optional.of(job));

        ResponseEntity<String> response = jobController.downloadResults(12L, true);

        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getBody().contains("\"case_1\""));
        assertEquals("application/json", response.getHeaders().getContentType().toString());
        assertEquals(
                "form-data; name=\"attachment\"; filename=\"results.json\"",
                response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)
        );
    }

    /**
     * Verifies that structured grading failures are persisted on the job.
     * Expected behavior: the response is 500 and the job records the orchestrator failure reason/message.
     */
    @Test
    void runJob_gradingFailure_persistsFailedStatusAndFailureReason() throws Exception {
        Job job = new Job("submission.py", "fib", OffsetDateTime.now(), JobStatus.QUEUED);
        setJobId(job, 13L);
        job.setSubmissionPath("batch-123/submission.py");
        when(jobRepository.findById(13L)).thenReturn(Optional.of(job));
        when(gradingOrchestrator.runJobInKubernetes(13L, "batch-123/submission.py", "fib"))
                .thenThrow(new GradingFailureException(FailureReason.TIMEOUT, "Timed out"));

        ResponseEntity<?> response = jobController.runJob(13L, "\"submission.py\"");

        assertEquals(500, response.getStatusCode().value());
        assertInstanceOf(tools.jackson.databind.node.StringNode.class, response.getBody());
        assertEquals(JobStatus.FAILED, job.getStatus());
        assertEquals(FailureReason.TIMEOUT, job.getFailureReason());
        assertEquals("Timed out", job.getFailureMessage());
    }

    /**
     * Verifies that running a missing job id returns not found.
     * Expected behavior: the controller returns 404 before invoking the grading orchestrator.
     */
    @Test
    void runJob_missingJob_returns404() {
        when(jobRepository.findById(404L)).thenReturn(Optional.empty());

        ResponseEntity<?> response = jobController.runJob(404L, "\"submission.py\"");

        assertEquals(404, response.getStatusCode().value());
        assertInstanceOf(tools.jackson.databind.node.StringNode.class, response.getBody());
        assertEquals("Unable to find job object for id 404", response.getBody().toString().replace("\"", ""));
    }

    /**
     * Verifies that invalid stored submission paths are treated as configuration errors.
     * Expected behavior: the job is marked FAILED with CONFIG_ERROR and a 400 response is returned.
     */
    @Test
    void runJob_invalidStoredSubmissionPath_persistsConfigError() throws Exception {
        Job job = new Job("submission.py", "fib", OffsetDateTime.now(), JobStatus.QUEUED);
        setJobId(job, 16L);
        job.setSubmissionPath("../secret.py");
        when(jobRepository.findById(16L)).thenReturn(Optional.of(job));

        ResponseEntity<?> response = jobController.runJob(16L, "\"submission.py\"");

        assertEquals(400, response.getStatusCode().value());
        assertEquals(JobStatus.FAILED, job.getStatus());
        assertEquals(FailureReason.CONFIG_ERROR, job.getFailureReason());
        assertEquals("Invalid file name.", job.getFailureMessage());
    }

    /**
     * Verifies that unexpected runtime exceptions are captured instead of escaping the controller.
     * Expected behavior: the job is marked FAILED with UNKNOWN and the error message is saved.
     */
    @Test
    void runJob_unexpectedException_persistsUnknownFailure() throws Exception {
        Job job = new Job("submission.py", "fib", OffsetDateTime.now(), JobStatus.QUEUED);
        setJobId(job, 17L);
        job.setSubmissionPath("batch-123/submission.py");
        when(jobRepository.findById(17L)).thenReturn(Optional.of(job));
        when(gradingOrchestrator.runJobInKubernetes(17L, "batch-123/submission.py", "fib"))
                .thenThrow(new RuntimeException("cluster exploded"));

        ResponseEntity<?> response = jobController.runJob(17L, "\"submission.py\"");

        assertEquals(500, response.getStatusCode().value());
        assertEquals(JobStatus.FAILED, job.getStatus());
        assertEquals(FailureReason.UNKNOWN, job.getFailureReason());
        assertEquals("cluster exploded", job.getFailureMessage());
    }

    /**
     * Verifies that the optional callback endpoint can apply successful grading results.
     * Expected behavior: the existing job is updated with status, score, counts, and result JSON.
     */
    @Test
    void updateJob_existingJob_appliesResults() {
        Job job = new Job("submission.py", "fib", OffsetDateTime.now(), JobStatus.RUNNING);
        when(jobRepository.findById(18L)).thenReturn(Optional.of(job));

        ObjectNode result = objectMapper.createObjectNode();
        result.put("status", "SUCCEEDED");
        result.put("tests_passed", 2);
        result.put("tests_total", 2);
        result.put("score", new BigDecimal("100.0"));
        ArrayNode results = result.putArray("results");
        results.addObject().put("name", "case_1").put("passed", true);

        ResponseEntity<String> response = jobController.updateJob(18L, result);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("Successfully updated job.", response.getBody());
        assertEquals(JobStatus.SUCCEEDED, job.getStatus());
        assertEquals(2, job.getTestsPassed());
        assertEquals(2, job.getTestsTotal());
        assertEquals(0, new BigDecimal("100.0").compareTo(job.getScore()));
        assertEquals(FailureReason.NONE, job.getFailureReason());
        assertTrue(job.getResultJson().contains("case_1"));
    }

    /**
     * Verifies that callback updates fail clearly when the job id is missing.
     * Expected behavior: the controller returns 404 and does not attempt to apply results.
     */
    @Test
    void updateJob_missingJob_returns404() {
        when(jobRepository.findById(19L)).thenReturn(Optional.empty());

        ResponseEntity<String> response = jobController.updateJob(19L, objectMapper.createObjectNode());

        assertEquals(404, response.getStatusCode().value());
        assertEquals("Unable to find existing job with id 19", response.getBody());
    }

    /**
     * Verifies that malformed callback result bodies are rejected.
     * Expected behavior: the controller returns 500 with a message describing the update failure.
     */
    @Test
    void updateJob_invalidResult_returns500() {
        Job job = new Job("submission.py", "fib", OffsetDateTime.now(), JobStatus.RUNNING);
        when(jobRepository.findById(20L)).thenReturn(Optional.of(job));

        ResponseEntity<String> response = jobController.updateJob(20L, objectMapper.createObjectNode());

        assertEquals(500, response.getStatusCode().value());
        assertTrue(response.getBody().contains("Failed to update job"));
        assertTrue(response.getBody().contains("status"));
    }

    /**
     * Verifies that explicit file cleanup deletes an existing staged upload.
     * Expected behavior: the endpoint returns 200 and the file is removed from disk.
     */
    @Test
    void removeFile_existingFile_deletesFile() throws Exception {
        Path uploadDir = Path.of("grading/uploads");
        Files.createDirectories(uploadDir);
        Path file = uploadDir.resolve("remove-me.py");
        Files.writeString(file, "print('remove')");

        ResponseEntity<String> response = jobController.removeFile("remove-me.py");

        assertEquals(200, response.getStatusCode().value());
        assertEquals("Successfully deleted file.", response.getBody());
        assertFalse(Files.exists(file));
    }

    /**
     * Verifies that deleting a missing staged upload returns not found.
     * Expected behavior: the controller returns 404 with a simple file-not-found message.
     */
    @Test
    void removeFile_missingFile_returns404() {
        ResponseEntity<String> response = jobController.removeFile("missing.py");

        assertEquals(404, response.getStatusCode().value());
        assertEquals("File not found.", response.getBody());
    }

    /**
     * Verifies that file cleanup rejects path traversal attempts.
     * Expected behavior: unsafe paths return 400 and are not resolved under uploads.
     */
    @Test
    void removeFile_pathTraversal_returns400() {
        ResponseEntity<String> response = jobController.removeFile("../secret.py");

        assertEquals(400, response.getStatusCode().value());
        assertEquals("Invalid file name.", response.getBody());
    }

    /**
     * Verifies that result download requests for missing jobs return not found.
     * Expected behavior: the controller returns 404 with the missing job id.
     */
    @Test
    void downloadResults_missingJob_returns404() throws Exception {
        when(jobRepository.findById(21L)).thenReturn(Optional.empty());

        ResponseEntity<String> response = jobController.downloadResults(21L, true);

        assertEquals(404, response.getStatusCode().value());
        assertEquals("Unable to find job with id: 21", response.getBody());
    }

    /**
     * Verifies that result download requests fail when a job has no stored result JSON.
     * Expected behavior: the controller returns 404 and explains that results are unavailable.
     */
    @Test
    void downloadResults_jobWithoutResult_returns404() throws Exception {
        Job job = new Job("submission.py", "fib", OffsetDateTime.now(), JobStatus.QUEUED);
        when(jobRepository.findById(22L)).thenReturn(Optional.of(job));

        ResponseEntity<String> response = jobController.downloadResults(22L, true);

        assertEquals(404, response.getStatusCode().value());
        assertEquals("Unable to get results for id: 22", response.getBody());
    }

    /**
     * Verifies that inline result viewing can omit the attachment header.
     * Expected behavior: JSON is returned with no content-disposition when fromTable is false.
     */
    @Test
    void downloadResults_notFromTable_returnsInlineJsonWithoutAttachment() throws Exception {
        Job job = new Job("submission.py", "fib", OffsetDateTime.now(), JobStatus.SUCCEEDED);
        job.setResultJson("[{\"name\":\"case_1\",\"passed\":true}]");
        when(jobRepository.findById(23L)).thenReturn(Optional.of(job));

        ResponseEntity<String> response = jobController.downloadResults(23L, false);

        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getBody().contains("\"case_1\""));
        assertNull(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castJobs(Object jobs) {
        return (List<Map<String, Object>>) jobs;
    }

    private byte[] zipBytes(Map<String, String> entries) throws Exception {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try (ZipOutputStream zipOutputStream = new ZipOutputStream(outputStream)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zipOutputStream.putNextEntry(new ZipEntry(entry.getKey()));
                zipOutputStream.write(entry.getValue().getBytes());
                zipOutputStream.closeEntry();
            }
        }
        return outputStream.toByteArray();
    }

    private byte[] zipBytesWithDirectoriesOnly(String... directories) throws Exception {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try (ZipOutputStream zipOutputStream = new ZipOutputStream(outputStream)) {
            for (String directory : directories) {
                zipOutputStream.putNextEntry(new ZipEntry(directory));
                zipOutputStream.closeEntry();
            }
        }
        return outputStream.toByteArray();
    }

    private void setJobId(Job job, Long id) throws Exception {
        Field idField = Job.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(job, id);
    }
}
