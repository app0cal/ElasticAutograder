package com.autograder.config;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

class KubernetesConfigTest {

    /**
     * Verifies that the Kubernetes configuration can create a Fabric8 client bean.
     * Expected behavior: the config returns a non-null client instance that can be closed cleanly.
     */
    @Test
    void kubernetesClient_returnsClientInstance() {
        KubernetesConfig config = new KubernetesConfig();

        try (var client = config.kubernetesClient()) {
            assertNotNull(client);
        }
    }
}
