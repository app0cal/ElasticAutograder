package com.autograder.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration class for JSON-related beans.
 * 
 * Right now this mainly provides a shared ObjectMapper so other
 * backend components, such as config loaders, can deserialize JSON
 * files without creating their own mapper instance manually.
 */
@Configuration
public class JacksonConfig {

    /**
     * Creates the main shared Jackson ObjectMapper bean used across the backend.
     * 
     * This object mapper introduces Spring-managed classes like GraderConfigLoader to inject
     * the mapper and use it for parsing JSON config files such as graders.json to read from.
     *
     * @return shared ObjectMapper bean
     */
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}