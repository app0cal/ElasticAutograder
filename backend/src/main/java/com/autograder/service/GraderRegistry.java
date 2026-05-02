package com.autograder.service;

import com.autograder.config.GraderConfigLoader;
import com.autograder.model.GraderDefinition;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;


/**
 * Central registry for all grader definitions currently available to the backend.
 *
 * This service loads grader definitions with ../config/GraderConfigLoader.java 
 * stores them in a map, keyed by grader key so other backend components can 
 * quickly look up a grader by name when creating jobs or returning frontend 
 * grader options.
 */
@Service
public class GraderRegistry {

    // Map of grader key -> grader definition to lookup backend
    private final Map<String, GraderDefinition> graders;

    /**
     * Main Spring constructor used in the running backend.
     *
     * This loads grader definitions from graders.json through the config loader
     * and stores them in a map so each grader can be looked up by its unique key.
     *
     * @param graderConfigLoader loader used to read grader definitions from config
     */
    @Autowired
    public GraderRegistry(GraderConfigLoader graderConfigLoader) {
        List<GraderDefinition> loadedGraders = graderConfigLoader.loadGraders();

        this.graders = loadedGraders.stream()
                .collect(Collectors.toMap(
                        GraderDefinition::getKey,
                        Function.identity()
                ));
    }

    /**
     * Secondary constructor mainly used for tests.
     *
     * This allows a registry to be created directly from a provided list of
     * grader definitions without needing to read graders.json from disk.
     *
     * @param graderDefinitions list of grader definitions to register manually
     */
    public GraderRegistry(List<GraderDefinition> graderDefinitions){
        this.graders = graderDefinitions.stream()
                .collect(Collectors.toMap(
                        GraderDefinition::getKey,
                        Function.identity()
                ));

    }

    /**
     * Returns the grader definition for the given key.
     *
     * This exists to verify the grader exists, and throws an 
     * immediate error if the provided key is unknown/ext
     *
     * @param key backend grader key such as "fib" or "twosum"
     * @return grader definition for the requested key
     * @throws IllegalArgumentException if the grader key is not registered
     */
    public GraderDefinition getRequired(String key) {
        GraderDefinition grader = graders.get(key);
        if (grader == null) {
            throw new IllegalArgumentException("Unknown grader key: " + key);
        }
        return grader;
    }

    /**
     * Returns all registered graders as an immutable list.
     *
     * Used when the backend needs to return the available
     * grader options to the frontend submission page.
     *
     * @return copy of all registered grader definitions
     */
    public List<GraderDefinition> getAll() {
        return List.copyOf(graders.values());
    }
}