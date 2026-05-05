package com.autograder.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class KubernetesGradingPropertiesTest {

    @Test
    void defaults_matchLocalKindGradingSettings() {
        KubernetesGradingProperties properties = new KubernetesGradingProperties();

        assertEquals("elastic-grading", properties.getNamespace());
        assertEquals(300, properties.getJobTtlSeconds());
        assertEquals(Duration.ofSeconds(1), properties.getPollInterval());
        assertEquals(8, properties.getMaxActiveJobs());
        assertFalse(properties.isCleanupJobs());
    }
}
