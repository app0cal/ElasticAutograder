package com.autograder.service.identity;

import org.springframework.stereotype.Service;

/**
 * Resolves mock request identity from optional headers while keeping existing
 * unauthenticated local workflows working by default.
 */
@Service
public class RequestIdentityProvider {

    /**
     * Builds the current request identity from mock auth headers.
     *
     * @param institutionHeader optional institution identifier from the request
     * @param userHeader optional user identifier from the request
     * @return resolved identity, falling back to local anonymous values
     */
    public RequestIdentity resolve(String institutionHeader, String userHeader) {
        String institution = cleanOrDefault(institutionHeader, RequestIdentity.DEFAULT_INSTITUTION);
        String user = cleanOrDefault(userHeader, RequestIdentity.DEFAULT_USER);
        return new RequestIdentity(institution, user);
    }

    private String cleanOrDefault(String rawValue, String defaultValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return defaultValue;
        }

        return rawValue.trim();
    }
}
