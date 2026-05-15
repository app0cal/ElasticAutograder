package com.autograder.service.grader;

import java.util.List;

import org.springframework.stereotype.Service;

import com.autograder.dto.GraderOptionResponse;
import com.autograder.service.GraderRegistry;

/**
 * Converts grader registry definitions into the compact option DTOs used by
 * the frontend grader selector.
 */
@Service
public class GraderCatalogService {

    private final GraderRegistry graderRegistry;

    public GraderCatalogService(GraderRegistry graderRegistry) {
        this.graderRegistry = graderRegistry;
    }

    public List<GraderOptionResponse> getGraders(String institutionId) {
        return graderRegistry.getAll(institutionId).stream()
                .map(grader -> new GraderOptionResponse(
                        grader.getKey(),
                        grader.getLabel(),
                        grader.getLanguage(),
                        grader.getUploadMode(),
                        grader.getSummary(),
                        grader.getDetails()
                ))
                .toList();
    }
}
