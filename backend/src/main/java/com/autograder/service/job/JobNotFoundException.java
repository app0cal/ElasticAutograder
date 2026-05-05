package com.autograder.service.job;

/**
 * Indicates that a requested job id does not exist in persistent storage.
 */
public class JobNotFoundException extends RuntimeException {

    public JobNotFoundException(String message) {
        super(message);
    }
}
