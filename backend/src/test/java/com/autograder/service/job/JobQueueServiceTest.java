package com.autograder.service.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.OffsetDateTime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.autograder.config.QueueProperties;
import com.autograder.model.Job;
import com.autograder.model.JobStatus;
import com.autograder.service.identity.RequestIdentity;

class JobQueueServiceTest {

    private StringRedisTemplate redisTemplate;
    private ListOperations<String, String> listOperations;
    private QueueProperties queueProperties;
    private JobQueueService service;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        redisTemplate = Mockito.mock(StringRedisTemplate.class);
        listOperations = Mockito.mock(ListOperations.class);
        queueProperties = new QueueProperties();
        objectMapper = new ObjectMapper();
        service = new JobQueueService(redisTemplate, objectMapper, queueProperties);

        when(redisTemplate.opsForList()).thenReturn(listOperations);
    }

    @Test
    void enqueue_enabledQueue_publishesRedisMessage() throws Exception {
        Job job = job(42L);
        job.setSubmissionPath("db:2ee63863-c9ec-4a1f-8ce9-d4db05cc7a5c");
        job.setGraderType("fib");
        job.setInstitutionId("university");
        job.setSubmittedBy("student");
        job.setQueueMessageId("queue-message-1");
        job.setAttemptCount(1);

        service.enqueue(job, new RequestIdentity("university", "student"));

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(listOperations).leftPush(eq("grading-jobs"), payload.capture());
        GradingJobMessage message = objectMapper.readValue(payload.getValue(), GradingJobMessage.class);
        assertEquals(42L, message.jobId());
        assertEquals("queue-message-1", message.queueMessageId());
        assertEquals("db:2ee63863-c9ec-4a1f-8ce9-d4db05cc7a5c", message.submissionKey());
        assertEquals("fib", message.graderType());
        assertEquals("university", message.institutionId());
        assertEquals("student", message.requestedBy());
        assertEquals(2, message.attempt());
    }

    @Test
    void enqueue_disabledQueue_rejectsIntake() throws Exception {
        queueProperties.setEnabled(false);

        JobIntakeUnavailableException exception = assertThrows(
                JobIntakeUnavailableException.class,
                () -> service.enqueue(job(42L), RequestIdentity.localAnonymous())
        );

        assertEquals("Redis grading queue is disabled.", exception.getMessage());
    }

    private Job job(Long id) throws Exception {
        Job job = new Job("submission.py", "fib", OffsetDateTime.now(), JobStatus.QUEUED);
        Field idField = Job.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(job, id);
        return job;
    }
}
