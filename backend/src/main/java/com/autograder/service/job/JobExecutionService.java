package com.autograder.service.job;

import java.time.OffsetDateTime;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.autograder.model.FailureReason;
import com.autograder.model.Job;
import com.autograder.model.JobStatus;
import com.autograder.repository.JobRepository;
import com.autograder.service.GradingFailureException;
import com.autograder.service.LocalGraderSetupStatus;
import com.autograder.service.dispatch.JobDispatcher;
import com.autograder.service.identity.RequestIdentity;
import com.autograder.service.submission.SubmissionStorageService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.StringNode;

/**
 * Owns job execution lifecycle state transitions.
 *
 * This service is the boundary between persisted jobs and the dispatcher that
 * may later become Redis-backed.
 */
@Service
public class JobExecutionService {

    private final JobRepository jobRepository;
    private final LocalGraderSetupStatus graderSetupStatus;
    private final SubmissionStorageService submissionStorageService;
    private final JobDispatcher jobDispatcher;
    private final JobResultMapper jobResultMapper;

    public JobExecutionService(
            JobRepository jobRepository,
            LocalGraderSetupStatus graderSetupStatus,
            SubmissionStorageService submissionStorageService,
            JobDispatcher jobDispatcher,
            JobResultMapper jobResultMapper
    ) {
        this.jobRepository = jobRepository;
        this.graderSetupStatus = graderSetupStatus;
        this.submissionStorageService = submissionStorageService;
        this.jobDispatcher = jobDispatcher;
        this.jobResultMapper = jobResultMapper;
    }

    /**
     * Runs an existing queued job and persists the resulting lifecycle state.
     *
     * @param id job id to run
     * @param rawSubmissionBody legacy request body containing a submission name
     * @param identity mock caller identity reserved for later authorization
     * @return API-compatible JSON result and status
     */
    public RunJobResult runJob(Long id, String rawSubmissionBody, RequestIdentity identity) {
        if (!graderSetupStatus.isAcceptingJobs()) {
            throw new JobIntakeUnavailableException(graderSetupStatus.getMessage());
        }

        Optional<Job> jobEntity = jobRepository.findById(id);
        if (jobEntity.isEmpty()) {
            throw new JobNotFoundException("Unable to find job object for id " + id);
        }

        Job job = jobEntity.get();
        String stagedSubmissionKey = null;

        try {
            stagedSubmissionKey = submissionStorageService.resolveSubmissionKey(job.getSubmissionPath(), rawSubmissionBody);

            markRunning(job);
            JsonNode result = jobDispatcher.dispatch(id, stagedSubmissionKey, job.getGraderType());

            jobResultMapper.applyJobResults(job, result);
            job.setFinishedAt(OffsetDateTime.now());
            job.setUpdatedAt(OffsetDateTime.now());
            job.setK8sJobName("grading-job-" + id);
            jobRepository.saveAndFlush(job);

            return new RunJobResult(HttpStatus.OK, result);
        } catch (IllegalArgumentException e) {
            persistFailure(job, FailureReason.CONFIG_ERROR, e.getMessage());
            return new RunJobResult(HttpStatus.BAD_REQUEST, new StringNode(e.getMessage()));
        } catch (GradingFailureException e) {
            persistFailure(job, e.getFailureReason(), e.getMessage());
            return new RunJobResult(HttpStatus.INTERNAL_SERVER_ERROR, new StringNode(e.getMessage()));
        } catch (Exception e) {
            persistFailure(job, FailureReason.UNKNOWN, e.getMessage());
            return new RunJobResult(HttpStatus.INTERNAL_SERVER_ERROR, new StringNode(e.getMessage()));
        } finally {
            cleanupStagedSubmission(job, stagedSubmissionKey);
        }
    }

    /**
     * Applies pushed grader results to an existing job.
     *
     * @param id job id to update
     * @param jobResults grader result payload
     */
    public void updateJob(Long id, JsonNode jobResults) throws Exception {
        Optional<Job> jobEntity = jobRepository.findById(id);
        if (jobEntity.isEmpty()) {
            throw new JobNotFoundException("Unable to find existing job with id " + id);
        }

        Job job = jobEntity.get();
        jobResultMapper.applyJobResults(job, jobResults);
        job.setUpdatedAt(OffsetDateTime.now());
        jobRepository.saveAndFlush(job);
    }

    private void markRunning(Job job) {
        job.setStatus(JobStatus.RUNNING);
        job.setStartedAt(OffsetDateTime.now());
        job.setUpdatedAt(OffsetDateTime.now());
        job.setFailureReason(FailureReason.NONE);
        job.setFailureMessage(null);
        jobRepository.saveAndFlush(job);
    }

    private void persistFailure(Job job, FailureReason failureReason, String message) {
        job.setStatus(JobStatus.FAILED);
        job.setFailureReason(failureReason);
        job.setFailureMessage(message);
        job.setFinishedAt(OffsetDateTime.now());
        job.setUpdatedAt(OffsetDateTime.now());
        jobRepository.saveAndFlush(job);
    }

    private void cleanupStagedSubmission(Job job, String stagedSubmissionKey) {
        if (stagedSubmissionKey == null) {
            return;
        }

        try {
            job.setSubmissionPath(stagedSubmissionKey);
            submissionStorageService.deleteIfExists(stagedSubmissionKey);
        } catch (Exception e) {
            System.err.println("Failed to delete staged upload file '" + stagedSubmissionKey + "': " + e.getMessage());
        }
    }
}
