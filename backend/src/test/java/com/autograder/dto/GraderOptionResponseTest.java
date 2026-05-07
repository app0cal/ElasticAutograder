package com.autograder.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class GraderOptionResponseTest {

    /**
     * Verifies the minimal grader option constructor used when only key and label are known.
     * Expected behavior: the DTO supplies a default summary and an empty details list.
     */
    @Test
    void minimalConstructor_usesDefaultSummaryAndEmptyDetails() {
        GraderOptionResponse response = new GraderOptionResponse("fib", "Fibonacci");

        assertEquals("fib", response.getKey());
        assertEquals("Fibonacci", response.getLabel());
        assertEquals("python", response.getLanguage());
        assertEquals("No details provided.", response.getSummary());
        assertEquals(List.of(), response.getDetails());
    }

    /**
     * Verifies that the full constructor protects its details list from outside mutation.
     * Expected behavior: later changes to the input list do not change the DTO response.
     */
    @Test
    void fullConstructor_copiesDetailsDefensively() {
        List<String> details = new ArrayList<>();
        details.add("Return the nth Fibonacci number.");

        GraderOptionResponse response = new GraderOptionResponse(
                "fib",
                "Fibonacci",
                "java",
                "Dynamic programming warm-up.",
                details
        );
        details.add("Changed after construction.");

        assertEquals("java", response.getLanguage());
        assertEquals("Dynamic programming warm-up.", response.getSummary());
        assertEquals(List.of("Return the nth Fibonacci number."), response.getDetails());
        assertThrows(UnsupportedOperationException.class, () -> response.getDetails().add("new detail"));
    }

    /**
     * Verifies that null details are normalized for frontend responses.
     * Expected behavior: callers receive an empty details list instead of null.
     */
    @Test
    void fullConstructor_nullDetailsUsesEmptyList() {
        GraderOptionResponse response = new GraderOptionResponse(
                "twosum",
                "Two Sum",
                null,
                "Hash map problem.",
                null
        );

        assertEquals("python", response.getLanguage());
        assertEquals(List.of(), response.getDetails());
    }
}
