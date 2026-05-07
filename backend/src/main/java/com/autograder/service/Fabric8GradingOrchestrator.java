package com.autograder.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.ArrayList;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.JobList;
import io.fabric8.kubernetes.client.KubernetesClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.autograder.config.KubernetesGradingProperties;
import com.autograder.model.FailureReason;
import com.autograder.model.GraderDefinition;
import com.autograder.service.submission.LocalSubmissionStorageService;
import com.autograder.service.submission.SubmissionStorageService;

/**
 * Main grading orchestrator implementation backed by the Fabric8 Kubernetes client.
 *
 * This service is responsible for:
 * - creating a ConfigMap from the uploaded submission file
 * - creating the Kubernetes grading Job
 * - waiting for the Job to finish
 * - reading grader logs back as JSON
 * - classifying failures such as timeout or resource-limit errors
 * - cleaning up temporary Kubernetes resources after execution
 */
@Primary
@Service
public class Fabric8GradingOrchestrator implements GradingOrchestrator {

    private static final Logger logger = LoggerFactory.getLogger(Fabric8GradingOrchestrator.class);

    // Local JSON mapper used to parse grader output returned from pod logs.
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final KubernetesClient kubernetesClient;
    private final GraderRegistry graderRegistry;
    private final SubmissionStorageService submissionStorageService;
    private final KubernetesGradingProperties kubernetesProperties;

    /**
     * Constructor for Fabric8GradingOrchestrator with: 
     * the shared k8s client 
     * and the grader registry loaded from config.
     *
     * @param kubernetesClient Fabric8 client used to create/read/delete cluster resources
     * @param graderRegistry registry used to resolve grader metadata from the selected grader key
     */
    public Fabric8GradingOrchestrator(KubernetesClient kubernetesClient, GraderRegistry graderRegistry) {
        this(kubernetesClient, graderRegistry, new LocalSubmissionStorageService(), new KubernetesGradingProperties());
    }

    @Autowired
    public Fabric8GradingOrchestrator(
            KubernetesClient kubernetesClient,
            GraderRegistry graderRegistry,
            SubmissionStorageService submissionStorageService,
            KubernetesGradingProperties kubernetesProperties
    ) {
        this.kubernetesClient = kubernetesClient;
        this.graderRegistry = graderRegistry;
        this.submissionStorageService = submissionStorageService;
        this.kubernetesProperties = kubernetesProperties;
    }

    /**
     * Runs a job in Kubernetes for a specific submission indexed by ID
     *
     * Flow:
     * 1. Validate grader type
     * 2. Look up grader config from the registry
     * 3. Create a ConfigMap containing the uploaded submission
     * 4. Create the Kubernetes Job
     * 5. Wait for the job to finish
     * 6. Read the job logs and parse them as grader result JSON
     * 7. Clean up the temporary ConfigMap even if grading fails
     *
     * @param jobId id of the Job row associated with this grading run
     * @param fileName staged submission file name
     * @param graderType selected grader key
     * @return parsed grader JSON result
     * @throws Exception ONLY if job creation, execution, log parsing, or cleanup fails
     */
    @Override
    public JsonNode runJobInKubernetes(Long jobId, String fileName, String graderType, String institutionId) throws Exception {
        if (graderType == null || graderType.isBlank()) {
            throw new IllegalArgumentException("graderType is required.");
        }
        GraderDefinition grader = graderRegistry.getRequired(graderType);

        String cleanedFileName = submissionStorageService.sanitizeSubmissionKey(fileName);
        String configMapName = "submission-job-" + jobId;

        try{
            createSubmissionConfigMap(jobId, cleanedFileName, graderType, institutionId);
            createGradingJob(jobId, grader, institutionId);
            waitForJobCompletion(jobId,grader.getTimeoutSeconds());
            String logs = getJobLogs(jobId);

            return objectMapper.readTree(logs);
        } 
        catch (Exception err) {
            // Wraps lower level runtime errors into a structured grading failure.
            // This helps us store the grading error + grading error message to see 
            // later!
            FailureReason failureReason = classifyFailure(err);
            throw new GradingFailureException(
                failureReason,
                "Grading job " + jobId + " failed for grader '" + graderType + "': " + err.getMessage(),
                err
            );
        }
        finally {
            // Cleanup logic should always try to delete submitted config map.
            try{
                deleteSubmissionConfigMap(configMapName);
            } catch (Exception e) {
                logger.warn("Failed to delete ConfigMap {}: {}", configMapName, e.getMessage());
            }
        }
    }

    /**
     * Creates a ConfigMap based off the uploaded submission file contents.
     *
     * This allows the grader container to mount the submission file under /work
     * without needing direct access to the local backend file system.
     *
     * @param jobId id of the grading job
     * @param fileName staged submission file name
     * @return created or replaced ConfigMap resource
     * @throws Exception if the submission file cannot be found or read
     */
    public ConfigMap createSubmissionConfigMap(Long jobId, String fileName) throws Exception {
        return createSubmissionConfigMap(jobId, fileName, "unknown", "unknown");
    }

    public ConfigMap createSubmissionConfigMap(
            Long jobId,
            String fileName,
            String graderType,
            String institutionId
    ) throws Exception {
        String cleanedFileName = submissionStorageService.sanitizeSubmissionKey(fileName);
        String submissionContents = submissionStorageService.readSubmission(cleanedFileName);
        ConfigMap configMap = buildSubmissionConfigMap(jobId, submissionContents, graderType, institutionId);

        return kubernetesClient.configMaps()
                .inNamespace(kubernetesProperties.getNamespace())
                .resource(configMap)
                .createOrReplace();
    }

    ConfigMap buildSubmissionConfigMap(Long jobId, String submissionContents) {
        return buildSubmissionConfigMap(jobId, submissionContents, "unknown", "unknown");
    }

    ConfigMap buildSubmissionConfigMap(
            Long jobId,
            String submissionContents,
            String graderType,
            String institutionId
    ) {
        String configMapName = "submission-job-" + jobId;

        return new ConfigMapBuilder()
                .withNewMetadata()
                    .withName(configMapName)
                    .addToLabels("app", "elastic-autograder")
                    .addToLabels("component", "grader")
                    .addToLabels("job-id", String.valueOf(jobId))
                    .addToLabels("institution-id", toLabelValue(institutionId))
                    .addToLabels("grader-type", toLabelValue(graderType))
                    .addToAnnotations("elastic-autograder/job-id", String.valueOf(jobId))
                    .addToAnnotations("elastic-autograder/institution-id", institutionId)
                    .addToAnnotations("elastic-autograder/grader-type", graderType)
                .endMetadata()
                .addToData("submission.py", submissionContents)
                .build();
    }

    /**
     * Creates the Kubernetes Job that runs the grader container.
     *
     * This applies:
     * - the selected grader image
     * - manifest path
     * - timeout via activeDeadlineSeconds
     * - CPU and memory requests/limits
     * - the mounted ConfigMap containing the submission file
     *
     * @param jobId id of the grading job
     * @param grader resolved grader definition from config
     * @return created or replaced Kubernetes Job resource
     */
    public Job createGradingJob(Long jobId, GraderDefinition grader) {
        return createGradingJob(jobId, grader, "unknown");
    }

    public Job createGradingJob(Long jobId, GraderDefinition grader, String institutionId) {
        enforceMaxActiveJobs();
        Job job = buildGradingJob(jobId, grader, institutionId);

        return kubernetesClient.batch().v1().jobs()
                .inNamespace(kubernetesProperties.getNamespace())
                .resource(job)
                .createOrReplace();
    }

    Job buildGradingJob(Long jobId, GraderDefinition grader) {
        return buildGradingJob(jobId, grader, "unknown");
    }

    Job buildGradingJob(Long jobId, GraderDefinition grader, String institutionId) {
        String jobName = "grading-job-" + jobId;
        String configMapName = "submission-job-" + jobId;
        String graderType = grader.getKey();

        // important note for understanding this, this basically is just a yaml file so it'll look crazy unless you understand k8s yaml structure
        // Template is under backend/grading/graders if you want a reference to how this should look in yaml format but this as pretty as it gets
        // for using k8s :D 
        Job job = new JobBuilder()
            .withNewMetadata()
                .withName(jobName)
                .addToLabels("app", "elastic-autograder")
                .addToLabels("component", "grader")
                .addToLabels("job-id", String.valueOf(jobId))
                .addToLabels("institution-id", toLabelValue(institutionId))
                .addToLabels("grader-type", toLabelValue(graderType))
                .addToAnnotations("elastic-autograder/job-id", String.valueOf(jobId))
                .addToAnnotations("elastic-autograder/institution-id", institutionId)
                .addToAnnotations("elastic-autograder/grader-type", graderType)
            .endMetadata()
            .withNewSpec()
                .withTtlSecondsAfterFinished(kubernetesProperties.getJobTtlSeconds())
                .withBackoffLimit(0)
                .withActiveDeadlineSeconds(grader.getTimeoutSeconds().longValue())
                .withNewTemplate()
                    .withNewMetadata()
                        .addToLabels("app", "elastic-autograder")
                        .addToLabels("component", "grader")
                        .addToLabels("job-id", String.valueOf(jobId))
                        .addToLabels("institution-id", toLabelValue(institutionId))
                        .addToLabels("grader-type", toLabelValue(graderType))
                        .addToAnnotations("elastic-autograder/job-id", String.valueOf(jobId))
                        .addToAnnotations("elastic-autograder/institution-id", institutionId)
                        .addToAnnotations("elastic-autograder/grader-type", graderType)
                    .endMetadata()
                    .withNewSpec()
                        .withRestartPolicy("Never")
                        .addNewContainer()
                            .withName("grader")
                            .withImage(grader.getImageName())
                            .withImagePullPolicy("IfNotPresent")
                            .withCommand("python", "/app/main.py")
                            .withArgs("/work/submission.py", grader.getManifestPath())
                            .withNewResources()
                                .addToRequests("cpu", toCpuQuantity(grader.getCpuRequestMilli()))
                                .addToRequests("memory", toMemoryQuantity(grader.getMemoryRequestMb()))
                                .addToLimits("cpu", toCpuQuantity(grader.getCpuLimitMilli()))
                                .addToLimits("memory", toMemoryQuantity(grader.getMemoryLimitMb()))
                            .endResources()
                            .addNewVolumeMount()
                                .withName("submission-volume")
                                .withMountPath("/work")
                            .endVolumeMount()
                        .endContainer()
                        .addNewVolume()
                            .withName("submission-volume")
                            .withNewConfigMap()
                                .withName(configMapName)
                            .endConfigMap()
                        .endVolume()
                    .endSpec()
                .endTemplate()
            .endSpec()
            .build();

        addGraderLanguageEnv(job, grader);
        return job;
    }

    private void addGraderLanguageEnv(Job job, GraderDefinition grader) {
        if (grader.getLanguage() == null || grader.getLanguage().isBlank()) {
            return;
        }

        var container = job.getSpec()
                .getTemplate()
                .getSpec()
                .getContainers()
                .get(0);

        if (container.getEnv() == null) {
            container.setEnv(new ArrayList<>());
        }

        container.getEnv().add(new EnvVarBuilder()
                .withName("GRADER_LANGUAGE")
                .withValue(grader.getLanguage())
                .build());
    }

    /**
     * Polls Kubernetes until the grading job succeeds, fails, or times out.
     *
     * On failure, this method tries to inspect the pod state to classify whether
     * the failure was caused by a resource limit or a general Kubernetes error.
     *
     * @param jobId id of the grading job
     * @param timeoutSeconds maximum time to wait before treating the job as timed out
     * @return completed Job resource when successful
     * @throws Exception if the job fails, disappears, or exceeds the timeout
     */
    public Job waitForJobCompletion(Long jobId, long timeoutSeconds) throws Exception {
        String jobName = "grading-job-" + jobId;
        long deadline = System.currentTimeMillis() + (timeoutSeconds * 1000);

        while (System.currentTimeMillis() < deadline) {
            Job job = kubernetesClient.batch()
                    .v1()
                    .jobs()
                    .inNamespace(kubernetesProperties.getNamespace())
                    .withName(jobName)
                    .get();

            if (job == null) {
                throw new IllegalStateException("Job not found while waiting: " + jobName);
            }

            if (job.getStatus() != null) {
                Integer succeeded = job.getStatus().getSucceeded();
                Integer failed = job.getStatus().getFailed();

                if (succeeded != null && succeeded > 0) {
                    return job;
                }

                if (failed != null && failed > 0) {
                    FailureReason reason = detectFailureReasonFromPod(jobId);
                    String message = detectFailureMessageFromPod(jobId, "Job failed: " + jobName);

                    throw new GradingFailureException(reason, message);
                }
            }

            // move around sleep value depending on your preference
            // alternatively punish user/developer using this by changing to 20000000000
            // This is mostly the amount of time you want to wait for Job Completion
            // in ms 
            Thread.sleep(kubernetesProperties.getPollInterval().toMillis());
        }

        throw new IllegalStateException("Timed out waiting for job completion: " + jobName);
    }

    /**
     * Reads logs from the grader pod associated with the given job.
     *
     * The grader runtime is expected to print result JSON to stdout, which is then
     * parsed back into a JsonNode by runJobInKubernetes().
     *
     * @param jobId id of the grading job
     * @return raw pod log output as a string
     * @throws Exception if no pod exists, metadata is missing, or logs are empty
     */

    public String getJobLogs(Long jobId) throws Exception {
        String jobIdLabel = String.valueOf(jobId);

        PodList podList = kubernetesClient.pods()
                .inNamespace(kubernetesProperties.getNamespace())
                .withLabel("app", "elastic-autograder")
                .withLabel("job-id", jobIdLabel)
                .list();

        if (podList == null || podList.getItems() == null || podList.getItems().isEmpty()) {
            throw new IllegalStateException("No pod found for job-id=" + jobIdLabel);
        }

        Pod pod = podList.getItems().get(0);

        if (pod.getMetadata() == null || pod.getMetadata().getName() == null) {
            throw new IllegalStateException("Pod metadata/name missing for job-id=" + jobIdLabel);
        }

        String podName = pod.getMetadata().getName();

        String logs = kubernetesClient.pods()
                .inNamespace(kubernetesProperties.getNamespace())
                .withName(podName)
                .getLog();

        if (logs == null || logs.isBlank()) {
            throw new IllegalStateException("Empty logs returned for pod " + podName);
        }

        return logs;
    }

    /**
     * Attempts to classify a failed job by inspecting the terminated container state
     * of the associated pod.
     *
     * Right now this mainly detects OOMKilled so memory-limit failures can be stored
     * as RESOURCE_LIMIT instead of a generic Kubernetes failure.
     *
     * @param jobId id of the grading job
     * @return detected failure reason or KUBERNETES_ERROR as a fallback
     */
    private FailureReason detectFailureReasonFromPod(Long jobId) {
        String jobIdLabel = String.valueOf(jobId);

        PodList podList = kubernetesClient.pods()
                .inNamespace(kubernetesProperties.getNamespace())
                .withLabel("app", "elastic-autograder")
                .withLabel("job-id", jobIdLabel)
                .list();

        if (podList == null || podList.getItems() == null || podList.getItems().isEmpty()) {
            return FailureReason.KUBERNETES_ERROR;
        }

        for (Pod pod : podList.getItems()) {
            if (pod.getStatus() == null || pod.getStatus().getContainerStatuses() == null) {
                continue;
            }

            // avoid using var normally to avoid too much abstraction, but for future devs this is Fabric8 type
            for (var containerStatus : pod.getStatus().getContainerStatuses()) {
                if (containerStatus.getState() != null && containerStatus.getState().getTerminated() != null) {
                    String reason = containerStatus.getState().getTerminated().getReason();
                    if (reason != null && reason.equalsIgnoreCase("OOMKilled")) {
                        return FailureReason.RESOURCE_LIMIT;
                    }
                }

                if (containerStatus.getLastState() != null && containerStatus.getLastState().getTerminated() != null) {
                    String reason = containerStatus.getLastState().getTerminated().getReason();
                    if (reason != null && reason.equalsIgnoreCase("OOMKilled")) {
                        return FailureReason.RESOURCE_LIMIT;
                    }
                }
            }
        }

        return FailureReason.KUBERNETES_ERROR;
    }

    /**
     * Sanitizes the submission file name before using it in local file system operations.
     *
     * Prevents blank names and basic path traversal input.
     *
     * @param rawFileName raw request/body file name
     * @return cleaned file name safe to resolve under the uploads folder
     * @throws IllegalArgumentException if the file name is missing or invalid
     */
    private String detectFailureMessageFromPod(Long jobId, String defaultMessage) {
        String jobIdLabel = String.valueOf(jobId);

        PodList podList = kubernetesClient.pods()
                .inNamespace(kubernetesProperties.getNamespace())
                .withLabel("app", "elastic-autograder")
                .withLabel("job-id", jobIdLabel)
                .list();

        if (podList == null || podList.getItems() == null || podList.getItems().isEmpty()) {
            return defaultMessage;
        }

        for (Pod pod : podList.getItems()) {
            if (pod.getStatus() == null || pod.getStatus().getContainerStatuses() == null) {
                continue;
            }

            for (var containerStatus : pod.getStatus().getContainerStatuses()) {
                if (containerStatus.getState() != null && containerStatus.getState().getTerminated() != null) {
                    var terminated = containerStatus.getState().getTerminated();
                    String reason = terminated.getReason();
                    Integer exitCode = terminated.getExitCode();

                    if (reason != null) {
                        return "Job failed: " + reason + (exitCode != null ? " (exit code " + exitCode + ")" : "");
                    }
                }

                if (containerStatus.getLastState() != null && containerStatus.getLastState().getTerminated() != null) {
                    var terminated = containerStatus.getLastState().getTerminated();
                    String reason = terminated.getReason();
                    Integer exitCode = terminated.getExitCode();

                    if (reason != null) {
                        return "Job failed: " + reason + (exitCode != null ? " (exit code " + exitCode + ")" : "");
                    }
                }
            }
        }

        return defaultMessage;
    }

    // Cleanup method to delete ConfigMap and Job after completion
    public void deleteSubmissionConfigMap(String configMapName) {
        kubernetesClient.configMaps()
                .inNamespace(kubernetesProperties.getNamespace())
                .withName(configMapName)
                .delete();
    }

    private void enforceMaxActiveJobs() {
        Integer maxActiveJobs = kubernetesProperties.getMaxActiveJobs();
        if (maxActiveJobs == null || maxActiveJobs <= 0) {
            return;
        }

        int activeJobs = countActiveGraderJobs();
        if (activeJobs >= maxActiveJobs) {
            throw new IllegalStateException("Maximum active Kubernetes grading jobs reached: " + maxActiveJobs);
        }
    }

    private int countActiveGraderJobs() {
        JobList jobList = kubernetesClient.batch().v1().jobs()
                .inNamespace(kubernetesProperties.getNamespace())
                .withLabel("app", "elastic-autograder")
                .withLabel("component", "grader")
                .list();

        if (jobList == null || jobList.getItems() == null) {
            return 0;
        }

        return (int) jobList.getItems().stream()
                .filter(this::isActiveJob)
                .count();
    }

    private boolean isActiveJob(Job job) {
        return job.getStatus() != null
                && job.getStatus().getActive() != null
                && job.getStatus().getActive() > 0;
    }

    private String toLabelValue(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }

        String cleaned = value.trim().replaceAll("[^A-Za-z0-9_.-]", "-");
        if (cleaned.isBlank()) {
            return "unknown";
        }

        return cleaned.length() <= 63 ? cleaned : cleaned.substring(0, 63);
    }

    private FailureReason classifyFailure(Exception err) {
        String message = err.getMessage() == null ? "" : err.getMessage().toLowerCase();

        if (message.contains("timed out")) {
            return FailureReason.TIMEOUT;
        }

        if (message.contains("oomkilled") || message.contains("memory") || message.contains("resource")) {
            return FailureReason.RESOURCE_LIMIT;
        }

        if (message.contains("json") || message.contains("parse")) {
            return FailureReason.RESULT_PARSE_ERROR;
        }

        if (message.contains("config")) {
            return FailureReason.CONFIG_ERROR;
        }

        return FailureReason.KUBERNETES_ERROR;
    }

    // lots of helper functions for converting resource values to k8s format, can be enhanced as needed
    private io.fabric8.kubernetes.api.model.Quantity toCpuQuantity(Integer milli) {
        return new io.fabric8.kubernetes.api.model.Quantity(milli + "m");
    }

    private io.fabric8.kubernetes.api.model.Quantity toMemoryQuantity(Integer mb) {
        return new io.fabric8.kubernetes.api.model.Quantity(mb + "Mi");
    }
}
