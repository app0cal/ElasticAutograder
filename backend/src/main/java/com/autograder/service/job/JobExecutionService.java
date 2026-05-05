package com.autograder.service.job;

import java.time.OffsetDateTime;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger logger = LoggerFactory.getLogger(JobExecutionService.class);

    private final JobRepository jobRepository;
    private final LocalGraderSetupStatus graderSetupStatus;
    private final SubmissionStorageService submissionStorageService;
    private final JobDispatcher jobDispatcher;
    private final JobResultMapper jobResultMapper;
    private final JobQueueService jobQueueService;

    public JobExecutionService(
            JobRepository jobRepository,
            LocalGraderSetupStatus graderSetupStatus,
            SubmissionStorageService submissionStorageService,
            JobDispatcher jobDispatcher,
            JobResultMapper jobResultMapper,
            JobQueueService jobQueueService
    ) {
        this.jobRepository = jobRepository;
        this.graderSetupStatus = graderSetupStatus;
        this.submissionStorageService = submissionStorageService;
        this.jobDispatcher = jobDispatcher;
        this.jobResultMapper = jobResultMapper;
        this.jobQueueService = jobQueueService;
    }

    /**
     * Validates and schedules an existing queued job for background execution.
     *
     * @param id job id to enqueue
     * @param identity mock caller identity reserved for later authorization
     * @return accepted response for the compatibility run endpoint
     */
    public JobEnqueueResponse enqueueJob(Long id, RequestIdentity identity) {
        if (!graderSetupStatus.isAcceptingJobs()) {
            throw new JobIntakeUnavailableException(graderSetupStatus.getMessage());
        }

        Job job = getJobOrThrow(id, "Unable to find job object for id " + id);
        if (!identity.institution().equals(job.getInstitutionId())) {
            throw new JobNotFoundException("Unable to find job object for id " + id);
        }
        if (job.getStatus() != JobStatus.QUEUED) {
            throw new IllegalArgumentException("Job " + id + " is not queued.");
        }

        jobQueueService.enqueue(job, identity);
        return new JobEnqueueResponse(HttpStatus.ACCEPTED, new StringNode("Job " + id + " queued."));
    }

    /**
     * Executes a queued job in the background and persists its final state.
     *
     * @param id queued job id to execute
     */
    public void executeQueuedJob(Long id) {
        Optional<Job> jobEntity = jobRepository.findById(id);
        if (jobEntity.isEmpty()) {
            return;
        }

        Job job = jobEntity.get();
        if (job.getStatus() != JobStatus.QUEUED) {
            logger.info("Skipping non-queued job jobId={} status={}", id, job.getStatus());
            return;
        }

        String submissionKey;

        try {
            submissionKey = submissionStorageService.resolveSubmissionKey(job.getSubmissionPath(), null);

            markRunning(job);
            logger.info(
                    "Started grading job jobId={} institutionId={} graderType={}",
                    id,
                    job.getInstitutionId(),
                    job.getGraderType()
            );
            var result = jobDispatcher.dispatch(id, submissionKey, job.getGraderType(), job.getInstitutionId());

            jobResultMapper.applyJobResults(job, result);
            job.setFinishedAt(OffsetDateTime.now());
            job.setUpdatedAt(OffsetDateTime.now());
            job.setK8sJobName("grading-job-" + id);
            jobRepository.saveAndFlush(job);
            logger.info("Finished grading job jobId={} status={}", id, job.getStatus());
        } catch (IllegalArgumentException e) {
            persistFailure(job, FailureReason.CONFIG_ERROR, e.getMessage());
        } catch (GradingFailureException e) {
            persistFailure(job, e.getFailureReason(), e.getMessage());
        } catch (Exception e) {
            persistFailure(job, FailureReason.UNKNOWN, e.getMessage());
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

    private Job getJobOrThrow(Long id, String message) {
        Optional<Job> jobEntity = jobRepository.findById(id);
        if (jobEntity.isEmpty()) {
            throw new JobNotFoundException(message);
        }

        return jobEntity.get();
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
        logger.error(
                "Failed grading job jobId={} failureReason={} message={}",
                job.getId(),
                failureReason,
                message
        );
    }

}
