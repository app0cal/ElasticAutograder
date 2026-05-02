package com.autograder.controller;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.autograder.dto.GraderOptionResponse;
import com.autograder.model.FailureReason;
import com.autograder.model.GraderDefinition;
import com.autograder.model.Job;
import com.autograder.model.JobStatus;
import com.autograder.repository.JobRepository;
import com.autograder.service.Fabric8GradingOrchestrator;
import com.autograder.service.GraderRegistry;
import com.autograder.service.GradingFailureException;
import com.autograder.service.GradingOrchestrator;
import com.autograder.service.LocalGraderSetupStatus;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.StringNode;

/**
 * Main REST controller for job submission, execution, result retrieval,
 * file cleanup, and grader option lookup.
 *
 * This controller handles the full basic workflow:
 * 1. Upload a submission file
 * 2. Create a Job record
 * 3. Run the job through the grading orchestrator
 * 4. Store results/failure details in the database
 * 5. Return job history and downloadable results to the frontend
 */
@CrossOrigin(origins = "http://localhost:5173/")
@RestController
@RequestMapping("/api")
public class JobController {

    // this is where submissions are stored
    private static final Path UPLOAD_ROOT = Path.of("grading/uploads");

    private final JobRepository jobRepository;
    private final GradingOrchestrator gradingOrchestrator;
    private final ObjectMapper objectMapper;
    private final Fabric8GradingOrchestrator fabric8GradingOrchestrator;
    private final GraderRegistry graderRegistry;
    private final LocalGraderSetupStatus graderSetupStatus;

    /**
     * Constructs the controller with the required repository and service dependencies.
     *
     * @param jobRepository database access for Job records
     * @param gradingOrchestrator main grading runner abstraction
     * @param fabric8GradingOrchestrator Fabric8-based Kubernetes orchestrator
     * @param gradingRegistry registry of supported graders loaded from config
     */
    public JobController(JobRepository jobRepository,
                     GradingOrchestrator gradingOrchestrator,
                     Fabric8GradingOrchestrator fabric8GradingOrchestrator,
                     GraderRegistry gradingRegistry) {
        this(jobRepository, gradingOrchestrator, fabric8GradingOrchestrator, gradingRegistry, new LocalGraderSetupStatus());
    }

    @Autowired
    public JobController(JobRepository jobRepository,
                     GradingOrchestrator gradingOrchestrator,
                     Fabric8GradingOrchestrator fabric8GradingOrchestrator,
                     GraderRegistry gradingRegistry,
                     LocalGraderSetupStatus graderSetupStatus) {
    this.jobRepository = jobRepository;
    this.gradingOrchestrator = gradingOrchestrator;
    this.fabric8GradingOrchestrator = fabric8GradingOrchestrator;
    this.objectMapper = new ObjectMapper();
    this.graderRegistry = gradingRegistry;
    this.graderSetupStatus = graderSetupStatus;
    }

    /**
     * Uploads a submission file to the local staging folder and creates
     * a corresponding Job row in the database.
     *
     * Flow:
     * - sanitize file name
     * - verify grader exists
     * - create upload directory if needed
     * - reject duplicate file names
     * - write file to disk
     * - create queued Job entry
     *
     * @param file uploaded submission file
     * @param graderType selected grader key from the frontend
     * @return message + job id on success, or an error response
     */
    @PostMapping("/jobs/upload")
    public ResponseEntity<Map<String, Object>> uploadFile(
        @RequestParam MultipartFile file,
        @RequestParam String graderType
    ) {
        try {
            if (!graderSetupStatus.isAcceptingJobs()) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(Map.of(
                                "message", graderSetupStatus.getMessage(),
                                "jobs", List.of()
                        ));
            }

            String cleanedGraderType = graderType.trim();

            // double check grader exists before continuing
            GraderDefinition grader = graderRegistry.getRequired(cleanedGraderType);

            if (!Files.exists(UPLOAD_ROOT)) {
                Files.createDirectories(UPLOAD_ROOT);
            }

            List<Map<String, Object>> jobs = isZipUpload(file)
                    ? createJobsFromZip(file, cleanedGraderType, grader)
                    : List.of(createJobForSingleFile(file, cleanedGraderType, grader));

            return ResponseEntity.ok(Map.of(
                    "message", jobs.size() == 1 ? "Successfully uploaded file." : "Successfully uploaded batch.",
                    "jobs", jobs
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of(
                            "message", e.getMessage(),
                            "jobs", List.of()
                    ));
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of(
                            "message", "Failed to save uploaded file.",
                            "jobs", List.of()
                    ));
        }
    }

    /**
     * Runs a staged submission through the grading pipeline.
     *
     * This method:
     * - loads the existing Job row
     * - marks it RUNNING
     * - invokes the grading orchestrator
     * - stores success or failure details back into the database
     *
     * @param id id of the Job row to run
     * @param fileName raw request body containing the uploaded file name
     * @return grader result JSON on success, or an error body on failure
     */
    @PostMapping("/jobs/run/{id}")
    public ResponseEntity<JsonNode> runJob(@PathVariable Long id, @RequestBody(required = false) String fileName) {
        if (!graderSetupStatus.isAcceptingJobs()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new StringNode(graderSetupStatus.getMessage()));
        }

        Optional<Job> jobEntity = jobRepository.findById(id);
        if (jobEntity.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new StringNode("Unable to find job object for id " + id));
        }

        Job job = jobEntity.get();
        String stagedSubmissionPath = null;

        try {
            stagedSubmissionPath = resolveSubmissionPath(job, fileName);

            // marks job as running, removing any other states
            job.setStatus(JobStatus.RUNNING);
            job.setStartedAt(OffsetDateTime.now());
            job.setUpdatedAt(OffsetDateTime.now());
            job.setFailureReason(FailureReason.NONE);
            job.setFailureMessage(null);
            jobRepository.saveAndFlush(job);

            // executes the submission via the kubernetes orchestrator
            JsonNode result = gradingOrchestrator.runJobInKubernetes(id, stagedSubmissionPath, job.getGraderType());

            // persists grading outputs back into the Job row
            applyJobResults(job, result);
            job.setFinishedAt(OffsetDateTime.now());
            job.setUpdatedAt(OffsetDateTime.now());
            job.setK8sJobName("grading-job-" + id);
            jobRepository.saveAndFlush(job);

            return ResponseEntity.ok(result);

        } catch (IllegalArgumentException e) {
            // Input/config-level problem such as missing file name or invalid grader.
            job.setStatus(JobStatus.FAILED);
            job.setFailureReason(FailureReason.CONFIG_ERROR);
            job.setFailureMessage(e.getMessage());
            job.setFinishedAt(OffsetDateTime.now());
            job.setUpdatedAt(OffsetDateTime.now());
            jobRepository.saveAndFlush(job);

            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new StringNode(e.getMessage()));

        } catch (GradingFailureException e) {
            // Structured grading failure from the orchestrator, such as timeout/resource failure.
            job.setStatus(JobStatus.FAILED);
            job.setFailureReason(e.getFailureReason());
            job.setFailureMessage(e.getMessage());
            job.setFinishedAt(OffsetDateTime.now());
            job.setUpdatedAt(OffsetDateTime.now());
            jobRepository.saveAndFlush(job);

            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new StringNode(e.getMessage()));

        } catch (Exception e) {
            // Fallback for any unexpected backend/runtime error.
            job.setStatus(JobStatus.FAILED);
            job.setFailureReason(FailureReason.UNKNOWN);
            job.setFailureMessage(e.getMessage());
            job.setFinishedAt(OffsetDateTime.now());
            job.setUpdatedAt(OffsetDateTime.now());
            jobRepository.saveAndFlush(job);

            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new StringNode(e.getMessage()));
        } finally {
            if (stagedSubmissionPath != null) {
                try {
                    Path filePath = resolveUploadPath(stagedSubmissionPath);
                    job.setSubmissionPath(stagedSubmissionPath);
                    Files.deleteIfExists(filePath);
                    deleteEmptyUploadParents(filePath);
                } catch (IOException e) {
                    System.err.println("Failed to delete staged upload file '" + stagedSubmissionPath + "': " + e.getMessage());
                }
            }
        }
    }

    /**
     * Returns recent jobs ordered by creation time descending.
     *
     * @return list of recent Job rows for the frontend jobs table
     */
    @GetMapping("/jobs/recent")
    public ResponseEntity<List<Job>> getRecentJobs() {
        return ResponseEntity.ok(jobRepository.findAllOrderByCreatedAtDesc());
    }

    /**
     * Returns a single job by database id.
     *
     * @param id database id of the job row
     * @return Job row when found, or a not-found error body
     */
    @GetMapping("/jobs/{id}")
    public ResponseEntity<?> getJobById(@PathVariable Long id) {
        Optional<Job> jobEntity = jobRepository.findById(id);
        if (jobEntity.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("Unable to find job with id: " + id);
        }

        return ResponseEntity.ok(jobEntity.get());
    }

    /**
     * Optional callback endpoint if you later choose a push-based result model.
     * Not required for the current Phase 1 backend-pulls-logs approach.
     */
    @PostMapping("/jobs/{id}/callback")
    public ResponseEntity<String> updateJob(@PathVariable Long id, @RequestBody JsonNode jobResults) {
        Optional<Job> jobEntity = jobRepository.findById(id);
        if (jobEntity.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("Unable to find existing job with id " + id);
        }

        try {
            Job job = jobEntity.get();
            applyJobResults(job, jobResults);
            job.setUpdatedAt(OffsetDateTime.now());
            jobRepository.saveAndFlush(job);

            return ResponseEntity.ok("Successfully updated job.");
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed to update job: " + e.getMessage());
        }
    }

    /**
     * Deletes a staged uploaded submission file from the local uploads folder.
     *
     * This is currently the controller method responsible for removing the
     * uploaded submission file itself. It is manual, meaning it only runs
     * when the frontend explicitly calls this endpoint.
     *
     * This is kept for manual deletions to be used for admins and other use
     * cases.
     *
     * @param fileName raw request body containing the file name to remove
     * @return OK/Error response depending on whether deletion succeeded
     */
    @DeleteMapping("/files/remove")
    public ResponseEntity<String> removeFile(@RequestBody String fileName) {
        try {
            Path filePath = resolveUploadPath(fileName);

            if (!Files.exists(filePath)) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body("File not found.");
            }

            Files.delete(filePath);
            deleteEmptyUploadParents(filePath);
            return ResponseEntity.ok("Successfully deleted file.");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Unable to delete file.");
        }
    }

    /**
     * Returns the stored result JSON for a given job.
     *
     * This is used by the frontend to download a results file or inspect
     * pretty-printed result JSON directly.
     *
     * @param id job id whose results should be returned
     * @param fromTable whether the response should include an attachment header
     * @return JSON result body or a not-found error
     */
    @GetMapping("/jobs/result/{id}")
    public ResponseEntity<String> downloadResults(@PathVariable Long id, @RequestParam(defaultValue = "true") boolean fromTable) throws IOException {
        Optional<Job> jobEntity = jobRepository.findById(id);
        if (jobEntity.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("Unable to find job with id: " + id);
        }

        Job job = jobEntity.get();
        if (job.getResultJson() == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("Unable to get results for id: " + id);
        }

        JsonNode resultJson = objectMapper.readTree(job.getResultJson());
        String prettyJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(resultJson);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (fromTable) {
            headers.setContentDispositionFormData("attachment", "results.json");
        }
        return new ResponseEntity<>(prettyJson, headers, HttpStatus.OK);
    }

    /**
     * Applies grader result JSON to the Job entity.
     *
     * This maps summary data such as status, score, test counts,
     * failure message, and per-test results into the database row.
     *
     * @param job Job entity to update
     * @param jobResults JSON object returned by the grader runtime
     * @throws IOException if result JSON cannot be serialized back into a string
     */
    private void applyJobResults(Job job, JsonNode jobResults) throws IOException {
        if (jobResults == null || jobResults.get("status") == null) {
            throw new IllegalArgumentException("Grader result is missing required field: status");
        }

        JobStatus parsedStatus = parseJobStatus(jobResults.get("status").asText());
        job.setStatus(parsedStatus);

        if (jobResults.has("tests_passed")) {
            job.setTestsPassed(jobResults.get("tests_passed").asInt());
        }

        if (jobResults.has("tests_total")) {
            job.setTestsTotal(jobResults.get("tests_total").asInt());
        }

        if (jobResults.has("score") && !jobResults.get("score").isNull()) {
            job.setScore(jobResults.get("score").decimalValue());
        } else {
            job.setScore(BigDecimal.ZERO);
        }

        if (parsedStatus != JobStatus.FAILED) {
            job.setFailureReason(FailureReason.NONE);
            job.setFailureMessage(null);
        }

        if (parsedStatus == JobStatus.FAILED
                && jobResults.has("error_message")
                && !jobResults.get("error_message").isNull()) {
            job.setFailureMessage(jobResults.get("error_message").asText());

            boolean validationFailed = jobResults.has("validation_passed")
                    && !jobResults.get("validation_passed").isNull()
                    && !jobResults.get("validation_passed").asBoolean();

            if (job.getFailureReason() == null || job.getFailureReason() == FailureReason.NONE) {
                job.setFailureReason(validationFailed ? FailureReason.INVALID_UPLOAD : FailureReason.WRONG_ANSWER);
            }
        }

        if (jobResults.has("results")) {
            job.setResultJson(objectMapper.writeValueAsString(jobResults.get("results")));
        }
    }

    /**
     * Converts a raw grader status string into the JobStatus enum used by the backend.
     *
     * @param rawStatus raw string from grader output
     * @return parsed JobStatus enum value
     * @throws IllegalArgumentException if the status is missing or invalid
     */
    private JobStatus parseJobStatus(String rawStatus) {
        if (rawStatus == null || rawStatus.isBlank()) {
            throw new IllegalArgumentException("Job status is missing.");
        }

        String normalized = rawStatus.trim().toUpperCase();

        // If your grader returns SUCCEEDED/FAILED, this only works
        // if your JobStatus enum contains those exact values.
        return JobStatus.valueOf(normalized);
    }

     /**
     * Sanitizes and validates incoming file names before using them
     * for local file system operations.
     *
     * This prevents blank file names and basic path traversal attempts.
     *
     * @param rawFileName raw incoming file name from the request
     * @return cleaned file name safe to use under the uploads folder
     * @throws IllegalArgumentException if the file name is missing or invalid
     */
    private String sanitizeFileName(String rawFileName) {
        if (rawFileName == null) {
            throw new IllegalArgumentException("File name is required.");
        }

        String cleaned = rawFileName.trim();

        // Common case when frontend sends JSON string body like: "submission.py"
        if (cleaned.startsWith("\"") && cleaned.endsWith("\"") && cleaned.length() >= 2) {
            cleaned = cleaned.substring(1, cleaned.length() - 1).trim();
        }

        if (cleaned.isBlank()) {
            throw new IllegalArgumentException("File name is required.");
        }

        if (cleaned.contains("..") || cleaned.contains("/") || cleaned.contains("\\")) {
            throw new IllegalArgumentException("Invalid file name.");
        }

        return cleaned;
    }

    private boolean isZipUpload(MultipartFile file) {
        String originalFileName = file.getOriginalFilename();
        return originalFileName != null && originalFileName.toLowerCase().endsWith(".zip");
    }

    private Map<String, Object> createJobForSingleFile(
            MultipartFile file,
            String graderType,
            GraderDefinition grader
    ) throws IOException {
        String fileName = sanitizeFileName(file.getOriginalFilename());
        Path filePath = resolveUploadPath(fileName);

        if (Files.exists(filePath)) {
            throw new IllegalArgumentException("File with this name already exists.");
        }

        Files.write(filePath, file.getBytes(), StandardOpenOption.CREATE_NEW);

        Job job = new Job(fileName, graderType, OffsetDateTime.now(), JobStatus.QUEUED);
        job.setSubmissionPath(fileName);
        job.setGraderImage(grader.getImageName());
        jobRepository.save(job);

        return Map.of(
                "id", job.getId(),
                "fileName", job.getOriginalFilename()
        );
    }

    private List<Map<String, Object>> createJobsFromZip(
            MultipartFile file,
            String graderType,
            GraderDefinition grader
    ) throws IOException {
        Path batchDirectory = createZipUploadDirectory(file.getOriginalFilename());

        List<Job> createdJobs = new ArrayList<>();
        List<Path> extractedFiles = new ArrayList<>();

        try (InputStream inputStream = file.getInputStream(); ZipInputStream zipInputStream = new ZipInputStream(inputStream)) {
            Set<String> seenBasenames = new LinkedHashSet<>();
            ZipEntry entry;

            while ((entry = zipInputStream.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    zipInputStream.closeEntry();
                    continue;
                }

                Path relativeEntryPath = sanitizeZipEntryName(entry.getName());
                String baseName = relativeEntryPath.getFileName().toString();

                if (!seenBasenames.add(baseName)) {
                    throw new IllegalArgumentException("Zip archive contains duplicate file names: " + baseName);
                }

                Path extractedPath = batchDirectory.resolve(relativeEntryPath).normalize();
                if (!extractedPath.startsWith(batchDirectory)) {
                    throw new IllegalArgumentException("Zip archive contains an invalid file path.");
                }

                Files.createDirectories(extractedPath.getParent());
                Files.copy(zipInputStream, extractedPath);
                extractedFiles.add(extractedPath);
                zipInputStream.closeEntry();
            }

            if (extractedFiles.isEmpty()) {
                throw new IllegalArgumentException("Zip archive does not contain any files.");
            }

            List<Map<String, Object>> uploadedJobs = new ArrayList<>();
            for (Path extractedFile : extractedFiles) {
                String relativeSubmissionPath = UPLOAD_ROOT.relativize(extractedFile).toString().replace('\\', '/');
                String originalFileName = extractedFile.getFileName().toString();

                Job job = new Job(originalFileName, graderType, OffsetDateTime.now(), JobStatus.QUEUED);
                job.setSubmissionPath(relativeSubmissionPath);
                job.setGraderImage(grader.getImageName());
                jobRepository.save(job);
                createdJobs.add(job);

                uploadedJobs.add(Map.of(
                        "id", job.getId(),
                        "fileName", originalFileName
                ));
            }

            return uploadedJobs;
        } catch (IOException | RuntimeException e) {
            cleanupCreatedJobs(createdJobs);
            cleanupBatchDirectory(batchDirectory);
            throw e;
        }
    }

    private Path createZipUploadDirectory(String rawZipFileName) throws IOException {
        String zipFileName = sanitizeFileName(rawZipFileName);
        String lowerZipFileName = zipFileName.toLowerCase();
        int extensionStart = lowerZipFileName.lastIndexOf(".zip");
        String baseName = zipFileName.substring(0, extensionStart);
        String extension = zipFileName.substring(extensionStart);

        int suffix = 1;
        while (true) {
            String candidateName = suffix == 1
                    ? zipFileName
                    : baseName + "-" + suffix + extension;
            Path candidateDirectory = resolveUploadPath(candidateName);

            try {
                Files.createDirectory(candidateDirectory);
                return candidateDirectory;
            } catch (FileAlreadyExistsException e) {
                suffix++;
            }
        }
    }

    private String resolveSubmissionPath(Job job, String rawRequestBody) {
        String storedPath = job.getSubmissionPath();
        if (storedPath != null && !storedPath.isBlank()) {
            return sanitizeStoredSubmissionPath(storedPath);
        }

        return sanitizeStoredSubmissionPath(rawRequestBody);
    }

    private Path resolveUploadPath(String rawRelativePath) {
        String cleanedPath = sanitizeStoredSubmissionPath(rawRelativePath);
        Path resolvedPath = UPLOAD_ROOT.resolve(cleanedPath).normalize();
        if (!resolvedPath.startsWith(UPLOAD_ROOT)) {
            throw new IllegalArgumentException("Invalid file name.");
        }
        return resolvedPath;
    }

    private String sanitizeStoredSubmissionPath(String rawPath) {
        if (rawPath == null) {
            throw new IllegalArgumentException("File name is required.");
        }

        String cleaned = rawPath.trim();

        if (cleaned.startsWith("\"") && cleaned.endsWith("\"") && cleaned.length() >= 2) {
            cleaned = cleaned.substring(1, cleaned.length() - 1).trim();
        }

        if (cleaned.isBlank()) {
            throw new IllegalArgumentException("File name is required.");
        }

        cleaned = cleaned.replace('\\', '/');
        Path normalized = Path.of(cleaned).normalize();

        if (normalized.isAbsolute() || normalized.startsWith("..")) {
            throw new IllegalArgumentException("Invalid file name.");
        }

        String normalizedString = normalized.toString().replace('\\', '/');
        if (normalizedString.isBlank() || normalizedString.equals(".")) {
            throw new IllegalArgumentException("File name is required.");
        }

        return normalizedString;
    }

    private Path sanitizeZipEntryName(String rawEntryName) {
        if (rawEntryName == null || rawEntryName.isBlank()) {
            throw new IllegalArgumentException("Zip archive contains an invalid file path.");
        }

        String cleanedEntry = rawEntryName.replace('\\', '/');
        Path normalized = Path.of(cleanedEntry).normalize();

        if (normalized.isAbsolute() || normalized.startsWith("..")) {
            throw new IllegalArgumentException("Zip archive contains an invalid file path.");
        }

        if (normalized.getFileName() == null) {
            throw new IllegalArgumentException("Zip archive contains an invalid file path.");
        }

        return normalized;
    }

    private void cleanupCreatedJobs(List<Job> createdJobs) {
        for (Job createdJob : createdJobs) {
            try {
                jobRepository.delete(createdJob);
            } catch (Exception ignored) {
                // Best-effort rollback for partially created batch jobs.
            }
        }
    }

    private void cleanupBatchDirectory(Path batchDirectory) throws IOException {
        if (!Files.exists(batchDirectory)) {
            return;
        }

        try (var walk = Files.walk(batchDirectory)) {
            walk.sorted((left, right) -> right.compareTo(left))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                            // Best-effort cleanup for failed zip uploads.
                        }
                    });
        }
    }

    private void deleteEmptyUploadParents(Path filePath) throws IOException {
        Path parent = filePath.getParent();
        while (parent != null && !parent.equals(UPLOAD_ROOT)) {
            if (!Files.isDirectory(parent)) {
                break;
            }

            try (var children = Files.list(parent)) {
                if (children.findAny().isPresent()) {
                    break;
                }
            }

            Files.deleteIfExists(parent);
            parent = parent.getParent();
        }
    }

    /**
     * Returns the list of grader options that should appear in the frontend dropdown.
     *
     * This converts the full grader definitions from the registry into a smaller
     * DTO (Data Transfer Object) containing only the fields needed by the UI.
     *
     * @return list of frontend grader options
     */
    @GetMapping("/graders")
    public ResponseEntity<List<GraderOptionResponse>> getGraders() {
        List<GraderOptionResponse> graders = graderRegistry.getAll().stream()
                .map(grader -> new GraderOptionResponse(
                        grader.getKey(),
                        grader.getLabel(),
                        grader.getSummary(),
                        grader.getDetails()
                ))
                .toList();

        return ResponseEntity.ok(graders);
    }

}
