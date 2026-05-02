package com.autograder.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

import com.autograder.model.FailureReason;

class GradingFailureExceptionTest {

    /**
     * Verifies that structured grading failures keep their failure reason.
     * Expected behavior: the exception exposes both the reason enum and message for persistence.
     */
    @Test
    void constructorWithoutCause_storesFailureReasonAndMessage() {
        GradingFailureException exception =
                new GradingFailureException(FailureReason.TIMEOUT, "Timed out.");

        assertEquals(FailureReason.TIMEOUT, exception.getFailureReason());
        assertEquals("Timed out.", exception.getMessage());
    }

    /**
     * Verifies that wrapped grading failures preserve their original cause.
     * Expected behavior: callers can inspect the failure reason, message, and root exception.
     */
    @Test
    void constructorWithCause_storesFailureReasonMessageAndCause() {
        RuntimeException cause = new RuntimeException("root cause");
        GradingFailureException exception =
                new GradingFailureException(FailureReason.KUBERNETES_ERROR, "Cluster failed.", cause);

        assertEquals(FailureReason.KUBERNETES_ERROR, exception.getFailureReason());
        assertEquals("Cluster failed.", exception.getMessage());
        assertSame(cause, exception.getCause());
    }
}
