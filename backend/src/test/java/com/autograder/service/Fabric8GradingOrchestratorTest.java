package com.autograder.service;

import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

import java.util.List;

import com.autograder.model.FailureReason;
import com.autograder.model.GraderDefinition;

class Fabric8GradingOrchestratorTest {

    private KubernetesClient kubernetesClient;
    private GraderRegistry graderRegistry;
    private Fabric8GradingOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        kubernetesClient = Mockito.mock(KubernetesClient.class);
        graderRegistry = Mockito.mock(GraderRegistry.class);
        orchestrator = new Fabric8GradingOrchestrator(kubernetesClient, graderRegistry);
    }

    /**
     * Verifies that a grader type is required before orchestration starts.
     * Expected behavior: null grader input is rejected before Kubernetes resources are created.
     */
    @Test
    void runJobInKubernetes_nullGraderType_throwsIllegalArgumentException() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> orchestrator.runJobInKubernetes(1L, "submission.py", null, "local")
        );

        assertEquals("graderType is required.", exception.getMessage());
    }

    /**
     * Verifies that blank grader types are treated the same as missing grader types.
     * Expected behavior: whitespace-only grader input is rejected with a clear validation message.
     */
    @Test
    void runJobInKubernetes_blankGraderType_throwsIllegalArgumentException() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> orchestrator.runJobInKubernetes(1L, "submission.py", "   ", "local")
        );

        assertEquals("graderType is required.", exception.getMessage());
    }

    /**
     * Verifies that unknown grader keys fail during registry lookup.
     * Expected behavior: the orchestrator surfaces the registry validation error instead of creating a job.
     */
    @Test
    void runJobInKubernetes_unknownGraderType_throwsIllegalArgumentException() {
        when(graderRegistry.getRequired("unknown"))
                .thenThrow(new IllegalArgumentException("Unknown grader key: unknown"));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> orchestrator.runJobInKubernetes(1L, "submission.py", "unknown", "local")
        );

        assertTrue(exception.getMessage().contains("Unknown grader key"));
    }

    /**
     * Verifies that a submission file must exist before a ConfigMap can be created.
     * Expected behavior: missing staged submissions are rejected with a file-not-found message.
     */
    @Test
    void createSubmissionConfigMap_missingSubmissionFile_throwsIllegalArgumentException() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> orchestrator.createSubmissionConfigMap(1L, "does_not_exist.py")
        );

        assertTrue(exception.getMessage().contains("Submission file not found"));
    }

    /**
     * Verifies that submission paths cannot traverse outside the upload directory.
     * Expected behavior: path traversal input is rejected before file access.
     */
    @Test
    void createSubmissionConfigMap_invalidPathTraversalFileName_throwsIllegalArgumentException() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> orchestrator.createSubmissionConfigMap(1L, "../secret.py")
        );

        assertEquals("Invalid file name.", exception.getMessage());
    }

    /**
     * Verifies that blank submission paths are invalid.
     * Expected behavior: ConfigMap creation fails before reading the filesystem.
     */
    @Test
    void createSubmissionConfigMap_blankFileName_throwsIllegalArgumentException() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> orchestrator.createSubmissionConfigMap(1L, "   ")
        );

        assertEquals("File name is required.", exception.getMessage());
    }

    /**
     * Verifies that a zero-second wait deadline times out immediately.
     * Expected behavior: the orchestrator reports a timeout for the expected Kubernetes job name.
     */
    @Test
    void waitForJobCompletion_zeroTimeout_throwsTimeout() {
        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> orchestrator.waitForJobCompletion(49L, 0)
        );

        assertEquals("Timed out waiting for job completion: grading-job-49", exception.getMessage());
    }

    /**
     * Verifies the pure ConfigMap builder used for staged submissions.
     * Expected behavior: the ConfigMap includes job labels and mounts submission content as submission.py.
     */
    @Test
    void buildSubmissionConfigMap_setsMetadataLabelsAndSubmissionData() {
        var configMap = orchestrator.buildSubmissionConfigMap(
                54L,
                "def fib(n): return n",
                "fib",
                "university-a"
        );

        assertEquals("submission-job-54", configMap.getMetadata().getName());
        assertEquals("elastic-autograder", configMap.getMetadata().getLabels().get("app"));
        assertEquals("grader", configMap.getMetadata().getLabels().get("component"));
        assertEquals("54", configMap.getMetadata().getLabels().get("job-id"));
        assertEquals("university-a", configMap.getMetadata().getLabels().get("institution-id"));
        assertEquals("fib", configMap.getMetadata().getLabels().get("grader-type"));
        assertEquals("university-a", configMap.getMetadata().getAnnotations().get("elastic-autograder/institution-id"));
        assertEquals("def fib(n): return n", configMap.getData().get("submission.py"));
    }

    /**
     * Verifies the pure Kubernetes Job builder for grader execution.
     * Expected behavior: the generated job includes image, command, args, resources, volume, and timeout settings.
     */
    @Test
    void buildGradingJob_setsExpectedKubernetesSpec() {
        var job = orchestrator.buildGradingJob(55L, createGrader(), "university-a");

        assertEquals("grading-job-55", job.getMetadata().getName());
        assertEquals("elastic-autograder", job.getMetadata().getLabels().get("app"));
        assertEquals("grader", job.getMetadata().getLabels().get("component"));
        assertEquals("55", job.getMetadata().getLabels().get("job-id"));
        assertEquals("university-a", job.getMetadata().getLabels().get("institution-id"));
        assertEquals("fib", job.getMetadata().getLabels().get("grader-type"));
        assertEquals("fib", job.getMetadata().getAnnotations().get("elastic-autograder/grader-type"));
        assertEquals(Integer.valueOf(0), job.getSpec().getBackoffLimit());
        assertEquals(Integer.valueOf(300), job.getSpec().getTtlSecondsAfterFinished());
        assertEquals(Long.valueOf(10), job.getSpec().getActiveDeadlineSeconds());

        var podSpec = job.getSpec().getTemplate().getSpec();
        assertEquals("university-a", job.getSpec().getTemplate().getMetadata().getLabels().get("institution-id"));
        assertEquals("Never", podSpec.getRestartPolicy());
        assertEquals("submission-volume", podSpec.getVolumes().get(0).getName());
        assertEquals("submission-job-55", podSpec.getVolumes().get(0).getConfigMap().getName());

        var container = podSpec.getContainers().get(0);
        assertEquals("grader", container.getName());
        assertEquals("ea-grader-fibbonaci:v1", container.getImage());
        assertEquals("IfNotPresent", container.getImagePullPolicy());
        assertEquals(List.of("python", "/app/main.py"), container.getCommand());
        assertEquals(List.of("/work/submission.py", "/app/grader/manifest.json"), container.getArgs());
        assertEquals("100", container.getResources().getRequests().get("cpu").getAmount());
        assertEquals("m", container.getResources().getRequests().get("cpu").getFormat());
        assertEquals("128", container.getResources().getRequests().get("memory").getAmount());
        assertEquals("Mi", container.getResources().getRequests().get("memory").getFormat());
        assertEquals("500", container.getResources().getLimits().get("cpu").getAmount());
        assertEquals("m", container.getResources().getLimits().get("cpu").getFormat());
        assertEquals("512", container.getResources().getLimits().get("memory").getAmount());
        assertEquals("Mi", container.getResources().getLimits().get("memory").getFormat());
        assertEquals("/work", container.getVolumeMounts().get(0).getMountPath());
    }

    /**
     * Verifies the successful orchestration flow without requiring a real Kubernetes cluster.
     * Expected behavior: logs are parsed as JSON and temporary ConfigMap cleanup is attempted.
     */
    @Test
    void runJobInKubernetes_successfulRun_returnsParsedJsonAndCleansUpConfigMap() throws Exception {
        when(graderRegistry.getRequired("fib")).thenReturn(createGrader());
        TestableFabric8GradingOrchestrator testOrchestrator =
                new TestableFabric8GradingOrchestrator(kubernetesClient, graderRegistry);
        testOrchestrator.logs = "{\"status\":\"SUCCEEDED\",\"tests_passed\":2,\"tests_total\":2}";

        var result = testOrchestrator.runJobInKubernetes(22L, "\"batch-1/submission.py\"", "fib", "local");

        assertEquals("SUCCEEDED", result.get("status").asText());
        assertEquals(2, result.get("tests_passed").asInt());
        assertEquals("batch-1/submission.py", testOrchestrator.createdConfigMapForFile);
        assertTrue(testOrchestrator.createdJob);
        assertTrue(testOrchestrator.waitedForCompletion);
        assertEquals("submission-job-22", testOrchestrator.deletedConfigMapName);
    }

    /**
     * Verifies timeout errors are classified for job persistence.
     * Expected behavior: timeout text is wrapped in a GradingFailureException with TIMEOUT reason.
     */
    @Test
    void runJobInKubernetes_timeoutFailure_wrapsAsTimeoutFailure() {
        GradingFailureException exception = runFailureForMessage("Timed out waiting for job completion");

        assertEquals(FailureReason.TIMEOUT, exception.getFailureReason());
        assertTrue(exception.getMessage().contains("failed for grader 'fib'"));
    }

    /**
     * Verifies resource-limit failures are classified for job persistence.
     * Expected behavior: OOM or memory-limit text maps to RESOURCE_LIMIT.
     */
    @Test
    void runJobInKubernetes_resourceFailure_wrapsAsResourceLimitFailure() {
        GradingFailureException exception = runFailureForMessage("Pod was OOMKilled because memory limit was exceeded");

        assertEquals(FailureReason.RESOURCE_LIMIT, exception.getFailureReason());
    }

    /**
     * Verifies malformed result output is classified separately from infrastructure errors.
     * Expected behavior: parse-related failures map to RESULT_PARSE_ERROR.
     */
    @Test
    void runJobInKubernetes_resultParseFailure_wrapsAsResultParseFailure() {
        GradingFailureException exception = runFailureForMessage("result parse error");

        assertEquals(FailureReason.RESULT_PARSE_ERROR, exception.getFailureReason());
    }

    /**
     * Verifies configuration-related failures are classified for user-visible diagnostics.
     * Expected behavior: config text maps to CONFIG_ERROR.
     */
    @Test
    void runJobInKubernetes_configFailure_wrapsAsConfigError() {
        GradingFailureException exception = runFailureForMessage("config map missing");

        assertEquals(FailureReason.CONFIG_ERROR, exception.getFailureReason());
    }

    /**
     * Verifies unknown orchestration failures fall back to a Kubernetes error category.
     * Expected behavior: unrecognized failures map to KUBERNETES_ERROR.
     */
    @Test
    void runJobInKubernetes_genericFailure_wrapsAsKubernetesError() {
        GradingFailureException exception = runFailureForMessage("cluster unavailable");

        assertEquals(FailureReason.KUBERNETES_ERROR, exception.getFailureReason());
    }

    private GradingFailureException runFailureForMessage(String message) {
        when(graderRegistry.getRequired("fib")).thenReturn(createGrader());
        TestableFabric8GradingOrchestrator testOrchestrator =
                new TestableFabric8GradingOrchestrator(kubernetesClient, graderRegistry);
        testOrchestrator.waitFailure = new IllegalStateException(message);

        GradingFailureException exception = assertThrows(
                GradingFailureException.class,
                () -> testOrchestrator.runJobInKubernetes(23L, "submission.py", "fib", "local")
        );

        assertEquals("submission-job-23", testOrchestrator.deletedConfigMapName);
        return exception;
    }

    private GraderDefinition createGrader() {
        GraderDefinition grader = new GraderDefinition();
        grader.setKey("fib");
        grader.setLabel("Fibonacci");
        grader.setImageName("ea-grader-fibbonaci:v1");
        grader.setManifestPath("/app/grader/manifest.json");
        grader.setTimeoutSeconds(10);
        grader.setCpuRequestMilli(100);
        grader.setCpuLimitMilli(500);
        grader.setMemoryRequestMb(128);
        grader.setMemoryLimitMb(512);
        return grader;
    }

    private static class TestableFabric8GradingOrchestrator extends Fabric8GradingOrchestrator {
        private String logs;
        private RuntimeException waitFailure;
        private String createdConfigMapForFile;
        private boolean createdJob;
        private boolean waitedForCompletion;
        private String deletedConfigMapName;

        TestableFabric8GradingOrchestrator(KubernetesClient kubernetesClient, GraderRegistry graderRegistry) {
            super(kubernetesClient, graderRegistry);
        }

        @Override
        public io.fabric8.kubernetes.api.model.ConfigMap createSubmissionConfigMap(
                Long jobId,
                String fileName,
                String graderType,
                String institutionId
        ) {
            createdConfigMapForFile = fileName;
            return new io.fabric8.kubernetes.api.model.ConfigMap();
        }

        @Override
        public io.fabric8.kubernetes.api.model.batch.v1.Job createGradingJob(
                Long jobId,
                GraderDefinition grader,
                String institutionId
        ) {
            createdJob = true;
            return new io.fabric8.kubernetes.api.model.batch.v1.Job();
        }

        @Override
        public io.fabric8.kubernetes.api.model.batch.v1.Job waitForJobCompletion(Long jobId, long timeoutSeconds) {
            waitedForCompletion = true;
            if (waitFailure != null) {
                throw waitFailure;
            }
            return new io.fabric8.kubernetes.api.model.batch.v1.Job();
        }

        @Override
        public String getJobLogs(Long jobId) {
            return logs;
        }

        @Override
        public void deleteSubmissionConfigMap(String configMapName) {
            deletedConfigMapName = configMapName;
        }
    }
}
