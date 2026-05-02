package com.autograder.config;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

class JacksonConfigTest {

    /**
     * Verifies that the JSON configuration exposes a shared ObjectMapper bean.
     * Expected behavior: callers receive a usable mapper instance from the config class.
     */
    @Test
    void objectMapper_returnsMapperInstance() {
        JacksonConfig config = new JacksonConfig();

        assertNotNull(config.objectMapper());
    }
}
