package com.autograder.model;

public enum JobStatus {
    PENDING,
    QUEUED,
    RUNNING,
    SUCCEEDED,
    PARTIAL,
    FAILED,
    DEAD_LETTERED,
    CANCELLED
}
