package com.autograder.service.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RequestIdentityProviderTest {

    private final RequestIdentityProvider provider = new RequestIdentityProvider();

    @Test
    void resolve_missingHeaders_returnsLocalAnonymousIdentity() {
        RequestIdentity identity = provider.resolve(null, null);

        assertEquals("local", identity.institution());
        assertEquals("anonymous", identity.user());
    }

    @Test
    void resolve_presentHeaders_trimsValues() {
        RequestIdentity identity = provider.resolve("  university-a  ", "  instructor-1  ");

        assertEquals("university-a", identity.institution());
        assertEquals("instructor-1", identity.user());
    }
}
