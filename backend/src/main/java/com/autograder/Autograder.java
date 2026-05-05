package com.autograder;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import com.autograder.config.KubernetesGradingProperties;
import com.autograder.config.QueueProperties;
import com.autograder.config.WorkerProperties;

/**
 * Runs the main application
 */
@SpringBootApplication
@EnableConfigurationProperties({QueueProperties.class, WorkerProperties.class, KubernetesGradingProperties.class})
public class Autograder {

    public static void main(String[] args) {
        SpringApplication.run(Autograder.class, args);
    }
}
