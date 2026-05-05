package com.autograder.service.job;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.core.task.TaskExecutor;
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
                "db:2ee63863-c9ec-4a1f-8ce9-d4db05cc7a5c",
                "fib",
                "local",
                "anonymous",
                1
        ));
        when(listOperations.rightPop(any(), any())).thenReturn(payload);

        assertTrue(worker.pollOnce());

        verify(jobExecutionService).executeQueuedJob(77L);
    }

    @Test
    void pollOnce_noMessage_returnsFalse() {
        when(listOperations.rightPop(any(), any())).thenReturn(null);

        assertFalse(worker.pollOnce());
    }

    @Test
    void submitPayload_malformedPayload_skipsMessage() {
        worker.submitPayload("{not-json");

        verify(jobExecutionService, never()).executeQueuedJob(any());
    }
}
