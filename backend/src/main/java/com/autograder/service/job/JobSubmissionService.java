package com.autograder.service.job;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.autograder.model.GraderDefinition;
import com.autograder.model.Job;
import com.autograder.model.JobStatus;
import com.autograder.repository.JobRepository;
import com.autograder.service.GraderRegistry;
import com.autograder.service.LocalGraderSetupStatus;
import com.autograder.service.identity.RequestIdentity;
import com.autograder.service.submission.StoredSubmission;
import com.autograder.service.submission.SubmissionStorageService;

/**
 * Handles upload intake and queued Job creation.
 *
 * Controllers delegate here so grader validation, storage writes, and database
 * rollback rules stay in one application service.
 */
@Service
public class JobSubmissionService {

    private final JobRepository jobRepository;
    private final GraderRegistry graderRegistry;
    private final LocalGraderSetupStatus graderSetupStatus;
    private final SubmissionStorageService submissionStorageService;

    public JobSubmissionService(
            JobRepository jobRepository,
            GraderRegistry graderRegistry,
            LocalGraderSetupStatus graderSetupStatus,
            SubmissionStorageService submissionStorageService
    ) {
        this.jobRepository = jobRepository;
        this.graderRegistry = graderRegistry;
        this.graderSetupStatus = graderSetupStatus;
        this.submissionStorageService = submissionStorageService;
    }

    /**
     * Stores the uploaded submission and creates one queued Job per staged file.
     *
     * @param file uploaded file or zip archive
     * @param graderType selected grader key
     * @param identity mock caller identity reserved for later institution scoping
     * @return upload response for the existing frontend contract
     * @throws IOException if file storage fails
     */
    public UploadJobResponse upload(MultipartFile file, String graderType, RequestIdentity identity) throws IOException {
        if (!graderSetupStatus.isAcceptingJobs()) {
            throw new JobIntakeUnavailableException(graderSetupStatus.getMessage());
        }

        String cleanedGraderType = cleanGraderType(graderType);
        GraderDefinition grader = graderRegistry.getRequired(cleanedGraderType);

        List<StoredSubmission> submissions = submissionStorageService.isZipUpload(file)
                ? submissionStorageService.storeZip(file)
                : List.of(submissionStorageService.storeSingle(file));

        List<Job> createdJobs = new ArrayList<>();
        try {
            List<UploadedJobSummary> uploadedJobs = new ArrayList<>();
            for (StoredSubmission submission : submissions) {
                Job job = createQueuedJob(submission, cleanedGraderType, grader);
                createdJobs.add(job);
                uploadedJobs.add(new UploadedJobSummary(job.getId(), job.getOriginalFilename()));
            }

            String message = uploadedJobs.size() == 1
                    ? "Successfully uploaded file."
                    : "Successfully uploaded batch.";
            return new UploadJobResponse(message, uploadedJobs);
        } catch (RuntimeException e) {
            cleanupCreatedJobs(createdJobs);
            cleanupStoredSubmissions(submissions);
            throw e;
        }
    }

    private Job createQueuedJob(StoredSubmission submission, String graderType, GraderDefinition grader) {
        Job job = new Job(submission.originalFileName(), graderType, OffsetDateTime.now(), JobStatus.QUEUED);
        job.setSubmissionPath(submission.key());
        job.setGraderImage(grader.getImageName());
        return jobRepository.save(job);
    }

    private String cleanGraderType(String graderType) {
        if (graderType == null || graderType.isBlank()) {
            throw new IllegalArgumentException("graderType is required.");
        }

        return graderType.trim();
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

    private void cleanupStoredSubmissions(List<StoredSubmission> submissions) {
        for (StoredSubmission submission : submissions) {
            try {
                submissionStorageService.deleteIfExists(submission.key());
            } catch (Exception ignored) {
                // Best-effort cleanup after database rollback.
            }
        }
    }
}
