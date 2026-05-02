package com.autograder.config;

import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration for the Fabric8 Kubernetes client.
 *
 * This registers a shared KubernetesClient bean so backend services can
 * communicate with the Kubernetes cluster programmatically instead of
 * manually calling writing raw string kubectl commands.
 *
 * In this project, the client is mainly used by the grading orchestrator
 * to create jobs, inspect pod state, and clean up Kubernetes resources.
 */
@Configuration
public class KubernetesConfig {

    // Creates the shared Kubernetes client used across the backend
    @Bean
    public KubernetesClient kubernetesClient() {
        Config config = new ConfigBuilder().build();
        return new KubernetesClientBuilder().withConfig(config).build();
    }
}