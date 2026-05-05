package com.autograder.service.identity;

/**
 * Lightweight caller identity used to prepare job services for later
 * institution-aware authorization without requiring real authentication yet.
 */
public record RequestIdentity(String institution, String user) {

    public static final String DEFAULT_INSTITUTION = "local";
    public static final String DEFAULT_USER = "anonymous";

    public static RequestIdentity localAnonymous() {
        return new RequestIdentity(DEFAULT_INSTITUTION, DEFAULT_USER);
    }
}
