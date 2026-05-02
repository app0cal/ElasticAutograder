package com.autograder.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;

class GraderDefinitionTest {

    /**
     * Verifies that the full grader constructor applies resource defaults when optional values are null.
     * Expected behavior: identity fields are preserved and runtime limits fall back to safe defaults.
     */
    @Test
    void fullConstructor_usesDefaultsForNullResourceFields() {
        GraderDefinition grader = new GraderDefinition(
                "fib",
                "Fibonacci",
                "ea-grader-fibbonaci:v1",
                "/app/grader/manifest.json",
                "Dynamic programming warm-up.",
                List.of("Return the nth Fibonacci number."),
                null,
                null,
                null,
                null,
                null
        );

        assertEquals("fib", grader.getKey());
        assertEquals("Fibonacci", grader.getLabel());
        assertEquals("ea-grader-fibbonaci:v1", grader.getImageName());
        assertEquals("/app/grader/manifest.json", grader.getManifestPath());
        assertEquals("Dynamic programming warm-up.", grader.getSummary());
        assertEquals(List.of("Return the nth Fibonacci number."), grader.getDetails());
        assertEquals(10, grader.getTimeoutSeconds());
        assertEquals(100, grader.getCpuRequestMilli());
        assertEquals(500, grader.getCpuLimitMilli());
        assertEquals(128, grader.getMemoryRequestMb());
        assertEquals(512, grader.getMemoryLimitMb());
    }

    /**
     * Verifies that the short grader constructor initializes the required grader identity.
     * Expected behavior: summary/details remain unset while timeout, CPU, and memory defaults are applied.
     */
    @Test
    void shortConstructor_setsIdentityFieldsAndDefaultResources() {
        GraderDefinition grader = new GraderDefinition("twosum", "Two Sum", "ea-grader-twosum:v1", "/manifest.json");

        assertEquals("twosum", grader.getKey());
        assertEquals("Two Sum", grader.getLabel());
        assertEquals("ea-grader-twosum:v1", grader.getImageName());
        assertEquals("/manifest.json", grader.getManifestPath());
        assertNull(grader.getSummary());
        assertNull(grader.getDetails());
        assertEquals(10, grader.getTimeoutSeconds());
        assertEquals(100, grader.getCpuRequestMilli());
        assertEquals(500, grader.getCpuLimitMilli());
        assertEquals(128, grader.getMemoryRequestMb());
        assertEquals(512, grader.getMemoryLimitMb());
    }
}
