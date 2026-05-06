package com.autograder.service.job;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.autograder.config.QueueProperties;
import com.autograder.model.Job;
import com.autograder.service.identity.RequestIdentity;

/**
 * Redis queue boundary for asynchronous grading work.
 *
 * API services publish lightweight messages here while durable job and
 * submission content remain in Postgres.
 */
@Service
public class JobQueueService {

    private static final Logger logger = LoggerFactory.getLogger(JobQueueService.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final QueueProperties queueProperties;

    public JobQueueService(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            QueueProperties queueProperties
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.queueProperties = queueProperties;
    }

    /**
     * Publishes a queued job to Redis for worker consumption.
     *
     * @param job queued database job to execute later
     * @param identity caller identity to carry into future institution-aware workers
     */
    public void enqueue(Job job, RequestIdentity identity) {
        if (!queueProperties.isEnabled()) {
            throw new JobIntakeUnavailableException("Redis grading queue is disabled.");
        }

        GradingJobMessage message = new GradingJobMessage(
                job.getId(),
                job.getQueueMessageId(),
                job.getSubmissionPath(),
                job.getGraderType(),
                job.getInstitutionId(),
                job.getSubmittedBy(),
                nextAttempt(job)
        );

        try {
            redisTemplate.opsForList().leftPush(queueProperties.getName(), objectMapper.writeValueAsString(message));
            logger.info(
                    "Queued grading job jobId={} institutionId={} graderType={}",
                    job.getId(),
                    job.getInstitutionId(),
                    job.getGraderType()
            );
        } catch (JsonProcessingException e) {
            throw new JobIntakeUnavailableException("Unable to serialize grading queue message.");
        }
    }

    private int nextAttempt(Job job) {
        Integer attemptCount = job.getAttemptCount();
        return attemptCount == null ? 1 : attemptCount + 1;
    }
}
