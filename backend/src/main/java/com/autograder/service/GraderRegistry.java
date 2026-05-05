package com.autograder.service;

import com.autograder.model.GraderDefinition;
import com.autograder.service.grader.GraderCatalogProvider;
import com.autograder.service.grader.JsonGraderCatalogProvider;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;


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

    private final GraderCatalogProvider graderCatalogProvider;

    /**
     * Main Spring constructor used in the running backend.
     *
     * This loads grader definitions from graders.json through the config loader
     * and stores them in a map so each grader can be looked up by its unique key.
     *
     * @param graderCatalogProvider catalog lookup implementation
     */
    @Autowired
    public GraderRegistry(GraderCatalogProvider graderCatalogProvider) {
        this.graderCatalogProvider = graderCatalogProvider;
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
        this.graderCatalogProvider = new JsonGraderCatalogProvider(graderDefinitions);

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
        return getRequired("local", key);
    }

    public GraderDefinition getRequired(String institutionId, String key) {
        return graderCatalogProvider.findByInstitutionAndKey(institutionId, key)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown grader key for institution '" + institutionId + "': " + key
                ));
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
        return getAll("local");
    }

    public List<GraderDefinition> getAll(String institutionId) {
        return graderCatalogProvider.findByInstitution(institutionId);
    }
}
