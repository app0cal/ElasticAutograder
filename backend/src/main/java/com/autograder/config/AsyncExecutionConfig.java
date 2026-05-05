package com.autograder.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Configures the local async executor used before Redis-backed workers exist.
 *
 * These defaults keep local grading concurrency conservative while giving the
 * backend ownership of job execution instead of the browser.
 */
@Configuration
public class AsyncExecutionConfig {

    private final WorkerProperties workerProperties;

    public AsyncExecutionConfig(WorkerProperties workerProperties) {
        this.workerProperties = workerProperties;
    }

    @Bean
    public TaskExecutor gradingTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(workerProperties.getConcurrency());
        executor.setMaxPoolSize(workerProperties.getConcurrency());
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("grading-worker-");
        executor.initialize();
        return executor;
    }
}
