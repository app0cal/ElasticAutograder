package com.autograder.service.job;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.autograder.config.QueueProperties;
import com.autograder.config.WorkerProperties;

class GradingJobWorkerTest {

    private StringRedisTemplate redisTemplate;
    private ListOperations<String, String> listOperations;
    private TaskExecutor taskExecutor;
    private JobExecutionService jobExecutionService;
    private GradingJobWorker worker;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        redisTemplate = Mockito.mock(StringRedisTemplate.class);
        listOperations = Mockito.mock(ListOperations.class);
        taskExecutor = Runnable::run;
        jobExecutionService = Mockito.mock(JobExecutionService.class);
        objectMapper = new ObjectMapper();
        worker = new GradingJobWorker(
                redisTemplate,
                objectMapper,
                new QueueProperties(),
                new WorkerProperties(),
                taskExecutor,
                jobExecutionService
        );

        when(redisTemplate.opsForList()).thenReturn(listOperations);
    }

    @Test
    void pollOnce_messageAvailable_executesJob() throws Exception {
        String payload = objectMapper.writeValueAsString(new GradingJobMessage(
                77L,
                "queue-message-1",
                "db:2ee63863-c9ec-4a1f-8ce9-d4db05cc7a5c",
                "SINGLE_FILE",
                "fib",
                "local",
                "anonymous",
                1
        ));
        when(listOperations.rightPop(any(), any())).thenReturn(payload);

        assertTrue(worker.pollOnce());

        verify(jobExecutionService).executeQueuedJob(Mockito.eq(77L), anyString());
    }

    @Test
    void shouldStart_apiModeWorkerDisabled_returnsFalse() {
        WorkerProperties apiWorkerProperties = new WorkerProperties();
        apiWorkerProperties.setEnabled(false);
        GradingJobWorker apiWorker = new GradingJobWorker(
                redisTemplate,
                objectMapper,
                new QueueProperties(),
                apiWorkerProperties,
                taskExecutor,
                jobExecutionService
        );

        assertFalse(apiWorker.shouldStart());
        apiWorker.start();
        assertFalse(apiWorker.isRunning());
    }

    @Test
    void shouldStart_workerModeEnabled_returnsTrueAndUsesConfiguredPrefix() {
        WorkerProperties workerModeProperties = new WorkerProperties();
        workerModeProperties.setIdPrefix("compose-worker");
        GradingJobWorker workerModeWorker = new GradingJobWorker(
                redisTemplate,
                objectMapper,
                new QueueProperties(),
                workerModeProperties,
                taskExecutor,
                jobExecutionService
        );

        assertTrue(workerModeWorker.shouldStart());
        assertTrue(workerModeWorker.getWorkerId().startsWith("compose-worker-"));
    }

    @Test
    void pollOnce_noMessage_returnsFalse() {
        when(listOperations.rightPop(any(), any())).thenReturn(null);

        assertFalse(worker.pollOnce());
        assertEquals(4, worker.availableExecutionPermits());
    }

    @Test
    void submitPayload_malformedPayload_skipsMessage() {
        worker.submitPayload("{not-json");

        verify(jobExecutionService, never()).executeQueuedJob(any(), anyString());
        verify(listOperations, never()).leftPush(any(), anyString());
        assertEquals(4, worker.availableExecutionPermits());
    }

    @Test
    void pollOnce_multipleMessages_dispatchesEachValidJob() throws Exception {
        String firstPayload = objectMapper.writeValueAsString(message(101L));
        String secondPayload = objectMapper.writeValueAsString(message(102L));
        when(listOperations.rightPop(any(), any())).thenReturn(firstPayload, secondPayload, null);

        assertTrue(worker.pollOnce());
        assertTrue(worker.pollOnce());
        assertFalse(worker.pollOnce());

        verify(jobExecutionService).executeQueuedJob(Mockito.eq(101L), anyString());
        verify(jobExecutionService).executeQueuedJob(Mockito.eq(102L), anyString());
        verify(jobExecutionService, times(2)).executeQueuedJob(any(), anyString());
    }

    @Test
    void submitPayload_malformedThenValidPayload_continuesDispatching() throws Exception {
        worker.submitPayload("{not-json");
        worker.submitPayload(objectMapper.writeValueAsString(message(103L)));

        verify(jobExecutionService).executeQueuedJob(Mockito.eq(103L), anyString());
        verify(jobExecutionService, times(1)).executeQueuedJob(any(), anyString());
    }

    @Test
    void submitPayload_missingJobId_skipsMessage() throws Exception {
        String payload = objectMapper.writeValueAsString(new GradingJobMessage(
                null,
                "queue-message-1",
                "db:2ee63863-c9ec-4a1f-8ce9-d4db05cc7a5c",
                "SINGLE_FILE",
                "fib",
                "local",
                "anonymous",
                1
        ));

        worker.submitPayload(payload);

        verify(jobExecutionService, never()).executeQueuedJob(any(), anyString());
        verify(listOperations, never()).leftPush(any(), anyString());
        assertEquals(4, worker.availableExecutionPermits());
    }

    @Test
    void submitPayload_executorRejectsTask_requeuesOriginalPayloadAndReleasesPermit() throws Exception {
        TaskExecutor rejectingExecutor = command -> {
            throw new TaskRejectedException("executor full");
        };
        GradingJobWorker rejectingWorker = new GradingJobWorker(
                redisTemplate,
                objectMapper,
                new QueueProperties(),
                new WorkerProperties(),
                rejectingExecutor,
                jobExecutionService
        );
        String payload = objectMapper.writeValueAsString(message(104L));

        rejectingWorker.submitPayload(payload);

        verify(listOperations).leftPush("grading-jobs", payload);
        verify(jobExecutionService, never()).executeQueuedJob(any(), anyString());
        assertEquals(4, rejectingWorker.availableExecutionPermits());
    }

    @Test
    void pollOnce_executorRejectsPoppedMessage_requeuesOriginalPayloadAndReleasesPermit() throws Exception {
        TaskExecutor rejectingExecutor = command -> {
            throw new TaskRejectedException("executor full");
        };
        GradingJobWorker rejectingWorker = new GradingJobWorker(
                redisTemplate,
                objectMapper,
                new QueueProperties(),
                new WorkerProperties(),
                rejectingExecutor,
                jobExecutionService
        );
        String payload = objectMapper.writeValueAsString(message(105L));
        when(listOperations.rightPop(any(), any())).thenReturn(payload);

        assertTrue(rejectingWorker.pollOnce());

        verify(listOperations).leftPush("grading-jobs", payload);
        verify(jobExecutionService, never()).executeQueuedJob(any(), anyString());
        assertEquals(4, rejectingWorker.availableExecutionPermits());
    }

    private GradingJobMessage message(Long jobId) {
        return new GradingJobMessage(
                jobId,
                "queue-message-" + jobId,
                "db:2ee63863-c9ec-4a1f-8ce9-d4db05cc7a5c",
                "SINGLE_FILE",
                "fib",
                "local",
                "anonymous",
                1
        );
    }
}
