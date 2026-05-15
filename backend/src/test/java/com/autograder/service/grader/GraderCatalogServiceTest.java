package com.autograder.service.grader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.autograder.dto.GraderOptionResponse;
import com.autograder.model.GraderDefinition;
import com.autograder.service.GraderRegistry;

class GraderCatalogServiceTest {

    private final GraderRegistry graderRegistry = Mockito.mock(GraderRegistry.class);
    private final GraderCatalogService service = new GraderCatalogService(graderRegistry);

    @Test
    void getGraders_convertsRegistryDefinitionsToDtos() {
        GraderDefinition grader = new GraderDefinition();
        grader.setKey("fib");
        grader.setLabel("Fibonacci");
        grader.setLanguage("cpp");
        grader.setUploadMode("single_file");
        grader.setSummary("Summary");
        grader.setDetails(List.of("Detail"));
        when(graderRegistry.getAll("university-a")).thenReturn(List.of(grader));

        List<GraderOptionResponse> graders = service.getGraders("university-a");

        assertEquals(1, graders.size());
        assertEquals("fib", graders.get(0).getKey());
        assertEquals("Fibonacci", graders.get(0).getLabel());
        assertEquals("cpp", graders.get(0).getLanguage());
        assertEquals("single_file", graders.get(0).getUploadMode());
        assertEquals("Summary", graders.get(0).getSummary());
        assertEquals(List.of("Detail"), graders.get(0).getDetails());
    }
}
