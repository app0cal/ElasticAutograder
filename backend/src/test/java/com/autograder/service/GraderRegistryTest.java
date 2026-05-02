package com.autograder.service;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.autograder.config.GraderConfigLoader;
import com.autograder.model.GraderDefinition;

class GraderRegistryTest {

    private GraderRegistry createRegistry() {
        List<GraderDefinition> graderDefinitions = List.of(
                createGrader("fib", "Fibonacci", "ea-grader-fibbonaci:v1"),
                createGrader("twosum", "Two Sum", "ea-grader-twosum:v1")
        );

        return new GraderRegistry(graderDefinitions);
    }

    private GraderDefinition createGrader(String key, String label, String imageName) {
        GraderDefinition grader = new GraderDefinition();
        grader.setKey(key);
        grader.setLabel(label);
        grader.setImageName(imageName);
        grader.setManifestPath("/app/grader/manifest.json");
        grader.setTimeoutSeconds(10);
        grader.setCpuRequestMilli(100);
        grader.setCpuLimitMilli(500);
        grader.setMemoryRequestMb(128);
        grader.setMemoryLimitMb(512);
        return grader;
    }

    /**
     * Verifies that a registered Fibonacci grader can be looked up by key.
     * Expected behavior: the registry returns the exact grader metadata needed to create a job.
     */
    @Test
    void getRequired_validKey_returnsCorrectFibGrader() {
        GraderRegistry registry = createRegistry();

        GraderDefinition grader = registry.getRequired("fib");

        assertNotNull(grader);
        assertEquals("fib", grader.getKey());
        assertEquals("Fibonacci", grader.getLabel());
        assertEquals("ea-grader-fibbonaci:v1", grader.getImageName());
        assertEquals("/app/grader/manifest.json", grader.getManifestPath());
    }

    /**
     * Verifies that a registered Two Sum grader can be looked up by key.
     * Expected behavior: the registry returns the correct label, image, and manifest path.
     */
    @Test
    void getRequired_validKey_returnsCorrectTwoSumGrader() {
        GraderRegistry registry = createRegistry();

        GraderDefinition grader = registry.getRequired("twosum");

        assertNotNull(grader);
        assertEquals("twosum", grader.getKey());
        assertEquals("Two Sum", grader.getLabel());
        assertEquals("ea-grader-twosum:v1", grader.getImageName());
        assertEquals("/app/grader/manifest.json", grader.getManifestPath());
    }

    /**
     * Verifies that unknown grader keys fail fast.
     * Expected behavior: the registry throws an IllegalArgumentException instead of returning null.
     */
    @Test
    void getRequired_unknownKey_throwsIllegalArgumentException() {
        GraderRegistry registry = createRegistry();

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> registry.getRequired("unknown")
        );

        assertTrue(exception.getMessage().contains("Unknown grader key"));
    }

    /**
     * Verifies that all configured graders are exposed for frontend selection.
     * Expected behavior: getAll returns every registered grader definition.
     */
    @Test
    void getAll_returnsAllRegisteredGraders() {
        GraderRegistry registry = createRegistry();

        List<GraderDefinition> graders = registry.getAll();

        assertEquals(2, graders.size());

        boolean hasFib = graders.stream().anyMatch(g ->
                g.getKey().equals("fib") &&
                g.getLabel().equals("Fibonacci")
        );

        boolean hasTwoSum = graders.stream().anyMatch(g ->
                g.getKey().equals("twosum") &&
                g.getLabel().equals("Two Sum")
        );

        assertTrue(hasFib);
        assertTrue(hasTwoSum);
    }

    /**
     * Verifies that callers cannot mutate the registry's grader list.
     * Expected behavior: getAll returns an unmodifiable copy of the registered graders.
     */
    @Test
    void getAll_returnsUnmodifiableList() {
        GraderRegistry registry = createRegistry();

        List<GraderDefinition> graders = registry.getAll();

        assertThrows(UnsupportedOperationException.class, () ->
            graders.add(createGrader("new", "New Grader", "ea-grader-new:v1"))
        );
    }

    /**
     * Verifies that the production constructor loads graders through GraderConfigLoader.
     * Expected behavior: grader definitions returned by the loader are registered and queryable.
     */
    @Test
    void loaderConstructor_registersGradersFromConfigLoader() {
        GraderConfigLoader loader = Mockito.mock(GraderConfigLoader.class);
        when(loader.loadGraders()).thenReturn(List.of(
                createGrader("fib", "Fibonacci", "ea-grader-fibbonaci:v1")
        ));

        GraderRegistry registry = new GraderRegistry(loader);

        assertEquals("Fibonacci", registry.getRequired("fib").getLabel());
        assertEquals(1, registry.getAll().size());
    }
}
