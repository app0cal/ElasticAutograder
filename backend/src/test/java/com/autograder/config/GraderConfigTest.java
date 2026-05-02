package com.autograder.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.autograder.model.GraderDefinition;

class GraderConfigTest {

    /**
     * Verifies that GraderConfig stores the list Jackson maps from graders.json.
     * Expected behavior: the getter returns the same grader list and toString remains useful for debugging.
     */
    @Test
    void setGraders_getGradersAndToString_roundTrip() {
        GraderDefinition grader = new GraderDefinition("fib", "Fibonacci", "image", "manifest.json");
        GraderConfig config = new GraderConfig();

        config.setGraders(List.of(grader));

        assertEquals(List.of(grader), config.getGraders());
        assertTrue(config.toString().contains("GraderConfig"));
        assertTrue(config.toString().contains("graders="));
    }
}
