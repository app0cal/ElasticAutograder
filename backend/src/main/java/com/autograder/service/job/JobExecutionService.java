package com.autograder.service.job;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

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

        prepareQueuedJob(job);
        jobQueueService.enqueue(job, identity);
        return new JobEnqueueResponse(HttpStatus.ACCEPTED, new StringNode("Job " + id + " queued."));
    }

    /**
     * Executes a queued job in the background and persists its final state.
     *
     * @param id queued job id to execute
     */
    public void executeQueuedJob(Long id) {
        executeQueuedJob(id, "worker-local");
    }

    /**
     * Executes a queued job for a specific worker after an atomic claim.
     *
     * @param id queued job id to execute
     * @param workerId worker process/thread identifier claiming the job
     */
    public void executeQueuedJob(Long id, String workerId) {
        int claimed = jobRepository.claimQueuedJob(id, workerId);
        if (claimed == 0) {
            logger.info("Skipping unclaimed job jobId={} workerId={}", id, workerId);
            return;
        }

        Optional<Job> jobEntity = jobRepository.findById(id);
        if (jobEntity.isEmpty()) {
            return;
        }

        Job job = jobEntity.get();
        String submissionKey;

        try {
            submissionKey = submissionStorageService.resolveSubmissionKey(job.getSubmissionPath(), null);

            logger.info(
                    "Started grading job jobId={} institutionId={} graderType={} workerId={}",
                    id,
                    job.getInstitutionId(),
                    job.getGraderType(),
                    workerId
            );
            var result = jobDispatcher.dispatch(id, submissionKey, job.getGraderType(), job.getInstitutionId());

            jobResultMapper.applyJobResults(job, result);
            job.setFinishedAt(OffsetDateTime.now());
            job.setUpdatedAt(OffsetDateTime.now());
            job.setWorkerId(null);
            job.setK8sJobName("grading-job-" + id);
            jobRepository.saveAndFlush(job);
            logger.info("Finished grading job jobId={} status={}", id, job.getStatus());
        } catch (IllegalArgumentException e) {
            handleExecutionFailure(job, FailureReason.CONFIG_ERROR, e.getMessage());
        } catch (GradingFailureException e) {
            handleExecutionFailure(job, e.getFailureReason(), e.getMessage());
        } catch (Exception e) {
            handleExecutionFailure(job, FailureReason.UNKNOWN, e.getMessage());
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

    private void prepareQueuedJob(Job job) {
        job.setQueuedAt(OffsetDateTime.now());
        job.setQueueMessageId(UUID.randomUUID().toString());
        job.setUpdatedAt(OffsetDateTime.now());
        jobRepository.saveAndFlush(job);
    }

    private void handleExecutionFailure(Job job, FailureReason failureReason, String message) {
        if (shouldRetry(job, failureReason)) {
            requeueAfterFailure(job, failureReason, message);
            return;
        }

        persistFinalFailure(job, failureReason, message, terminalFailureStatus(failureReason, job));
    }

    private boolean shouldRetry(Job job, FailureReason failureReason) {
        return isRetryableFailure(failureReason) && currentAttempt(job) < maxAttempts(job);
    }

    private boolean isRetryableFailure(FailureReason failureReason) {
        return failureReason == FailureReason.KUBERNETES_ERROR || failureReason == FailureReason.UNKNOWN;
    }

    private void requeueAfterFailure(Job job, FailureReason failureReason, String message) {
        job.setStatus(JobStatus.QUEUED);
        job.setFailureReason(failureReason);
        job.setFailureMessage(message);
        job.setWorkerId(null);
        job.setFinishedAt(null);
        prepareQueuedJob(job);
        jobQueueService.enqueue(job, new RequestIdentity(job.getInstitutionId(), job.getSubmittedBy()));
        logger.warn(
                "Requeued grading job jobId={} attempt={} maxAttempts={} failureReason={}",
                job.getId(),
                currentAttempt(job),
                maxAttempts(job),
                failureReason
        );
    }

    private JobStatus terminalFailureStatus(FailureReason failureReason, Job job) {
        if (isRetryableFailure(failureReason) && currentAttempt(job) >= maxAttempts(job)) {
            return JobStatus.DEAD_LETTERED;
        }

        return JobStatus.FAILED;
    }

    private int currentAttempt(Job job) {
        return job.getAttemptCount() == null ? 0 : job.getAttemptCount();
    }

    private int maxAttempts(Job job) {
        return job.getMaxAttempts() == null ? Job.DEFAULT_MAX_ATTEMPTS : job.getMaxAttempts();
    }

    private void persistFinalFailure(Job job, FailureReason failureReason, String message, JobStatus status) {
        job.setStatus(status);
        job.setWorkerId(null);
        job.setFailureReason(failureReason);
        job.setFailureMessage(message);
        job.setFinishedAt(OffsetDateTime.now());
        job.setUpdatedAt(OffsetDateTime.now());
        jobRepository.saveAndFlush(job);
        logger.error(
                "Failed grading job jobId={} status={} failureReason={} message={}",
                job.getId(),
                status,
                failureReason,
                message
        );
    }

}
