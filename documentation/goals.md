# Elastic Autograder Resilience Goals

When a goal is finished, mark it as `Finished` and add notes describing the solution, important files changed, tradeoffs, and follow-up work.

## Context

The main goal is to handle a large number of submissions in a short burst without tying grading work to browser requests or one backend machine.

The intended direction is:
- Redis-backed asynchronous job queueing.
- Kubernetes grader execution through kind locally and production-like clusters later.
- Mock institution/user identity so jobs can be scoped like a university autograder.
- Shared durable submission storage so workers on different nodes can process the same queued jobs.
- Better operational visibility for queued, running, failed, retried, and completed jobs.

## Worker And Storage Model

Right now, after Goal 2, the backend owns job execution with an in-process Java executor.

Current local model:
- One backend process has a local Java thread pool.
- `corePoolSize = 4` means up to 4 Java worker threads can orchestrate grading jobs at the same time inside that one backend process.
- These Java threads do not run student code directly.
- The Java threads create Kubernetes Jobs, wait for pod completion, read logs, save results, and clean up staged submissions.
- Student code runs inside Kubernetes grader pods.

Future Redis model:
- Redis will store queued job messages durably.
- Multiple backend worker pods can consume jobs from Redis.
- Each backend worker pod can still have its own Java thread pool.
- Total backend orchestration concurrency is roughly:
  ```text
  backend worker pod count * threads per worker pod
  ```
- Actual grading capacity is also limited by Kubernetes CPU/memory, grader pod resource requests/limits, and any configured max active Kubernetes jobs.

Why shared storage comes before Redis:
- If submissions stay on one backend pod's local disk, another worker pod cannot process them.
- Goal 3 creates a shared durable submission store so any future worker pod can read the submitted code.
- Redis in Goal 4 should queue job messages, not file contents.

## Goal 1: Stabilize The Controller And Service Refactor

Status: Finished

### Problem

The backend has been split into focused controllers and services, but the refactor needs to be stabilized before queue behavior is added. Future async work depends on having clean service boundaries and passing tests.

### Target Outcome

Controllers only translate HTTP requests and responses. Services own submission storage, job creation, job execution lifecycle, result mapping, grader lookup, and cleanup. Backend tests pass under Java 21.

### Implementation Notes

- Run the backend test suite with Java 21 and fix compile or test failures.
- Rename or split `JobControllerTest` so tests match the new controller classes.
- Move business-heavy controller tests into service tests.
- Keep `SynchronousJobDispatcher` for local tests and as a baseline implementation.
- Remove names or response types that imply synchronous grading results where future async behavior should return queued/accepted state.

### Completion Notes

Finished after stabilizing the controller/service refactor around focused tests and clearer naming.

Solution notes:
- Verified the backend test suite runs under Java 21 with `./gradlew test`.
- Replaced the broad `JobControllerTest` with focused controller tests for submission, execution, query, grader listing, and upload cleanup.
- Added service/storage tests for submission intake, local storage/zip rules, job execution lifecycle, result mapping, query behavior, grader DTO conversion, and mock identity parsing.
- Renamed `RunJobResult` to `JobExecutionResponse` so the current synchronous behavior is clearer before async queue semantics are added.
- Kept runtime API behavior unchanged so Goal 2 can focus only on queue semantics.

## Goal 2: Convert Job Running To Async Queue Semantics

Status: Finished

### Problem

The frontend currently uploads submissions and then calls `runJob` for each job. The backend still treats running as request-driven work, which does not scale during a submission burst.

### Target Outcome

Uploading a submission creates jobs and queues them for background processing. API requests should not wait for Kubernetes grading to finish.

### Implementation Notes

- Change upload flow so created jobs are automatically queued.
- Change `POST /api/jobs/run/{id}` into a compatibility endpoint that enqueues an existing queued job and returns `202 Accepted`.
- Keep job result retrieval through polling existing job endpoints.
- Make `QUEUED` a normal durable state rather than a short-lived state before a browser-triggered run.

### Completion Notes

Finished after moving normal submission execution out of the browser request fan-out and into backend-owned asynchronous scheduling.

Solution notes:
- Upload now creates queued jobs and schedules each created job on an in-process backend executor.
- `POST /api/jobs/run/{id}` is now a compatibility/manual enqueue endpoint that returns `202 Accepted` instead of waiting for grader results.
- The existing synchronous Kubernetes dispatcher is still used inside the background worker task.
- The frontend submit flow no longer calls `runJob` with `Promise.all`; it uploads once, shows queued status, navigates to the jobs board, and relies on polling.
- This is not durable across backend restarts and does not coordinate multiple backend nodes yet. Redis-backed distributed queueing remains Goal 4.

## Goal 3: Add Durable Shared Submission Storage

Status: Finished

### Problem

Submissions currently live under local `grading/uploads`. That works for one backend process, but a Redis worker on another node will not be able to read those files.

### Target Outcome

Queued workers can read submissions from shared durable storage regardless of which backend node received the upload.

### Implementation Notes

- Add a `submissions` table for durable shared submission content.
- Keep `SubmissionStorageService` as the storage boundary so controllers, job services, and Kubernetes orchestration do not care where content lives.
- Add a Postgres-backed implementation, `DatabaseSubmissionStorageService`.
- Store each uploaded or extracted file as its own submission row.
- Jobs should store `submission_id` for the database relationship and a UUID-backed storage key in `submission_path` during the compatibility transition.
- Keep local filesystem storage only for dev/test if it remains useful; production-style wiring should use shared storage.
- Keep Kubernetes ConfigMaps as the first delivery mechanism into grader pods. Workers should read content from shared storage and create ConfigMaps from that content.
- Add upload size and zip batch limits before queueing jobs in a later hardening pass.
- Do not add Redis in Goal 3. Redis queueing remains Goal 4.

Recommended `submissions` fields:
```text
id
storage_key
original_filename
content
content_type
size_bytes
created_at
```

Use `BIGSERIAL` IDs for internal database relationships and `UUID` storage keys for distributed worker-facing references. Postgres can safely allocate incrementing IDs under concurrent uploads, while UUID keys are better suited for queue payloads and future object-storage paths.

Institution/user fields can be added now if convenient, but Goal 6 owns their behavior:
```text
institution_id
submitted_by
```

Expected interface changes:
- `StoredSubmission` should represent both a durable storage key and optional internal submission id, not a filesystem path.
- `SubmissionStorageService.readSubmission(...)` should read from shared storage.
- `Fabric8GradingOrchestrator` should continue reading through `SubmissionStorageService`.
- `JobSubmissionService` should create jobs with durable submission references.
- `JobExecutionService.executeQueuedJob(...)` should work from the stored durable submission reference.

Acceptance checklist:
- Uploading a single file stores submission content in shared storage.
- Uploading a zip stores each extracted file in shared storage.
- Jobs reference durable submission keys/ids.
- Background execution can read submissions without local upload files.
- Existing result persistence and download behavior remains unchanged.
- Backend tests pass with Java 21.

Implementation outline:
- Create a SQL init/update script for the `submissions` table.
- Add a `Submission` JPA model/entity and `SubmissionRepository`.
- Add `DatabaseSubmissionStorageService`.
- For now, store file contents as text because current submissions are Python files and ConfigMaps need text content.
- Revisit binary/blob support later if non-text uploads become a requirement.
- For zip upload, keep the current validation rules: reject path traversal, empty archives, directories-only archives, and duplicate basenames.
- Default retention behavior keeps durable submission rows after grading. A later cleanup/retention goal can decide when old submissions are removed.

### Completion Notes

- Added `Submission` and `SubmissionRepository` for Postgres-backed upload content.
- Added `DatabaseSubmissionStorageService` as the primary `SubmissionStorageService`.
- Uploads now store each single file or extracted zip entry as a submission row, create jobs with `submission_id`, and keep a `db:<uuid>` key in `submission_path` for compatibility.
- Execution now dispatches with the durable submission key and does not delete submission rows after grading.
- Added `init/add_submission_storage.sql` and updated `init/create_job.sql` for the new `submissions` table and `jobs.submission_id` foreign key.
- Backend tests passed with Java 21.

## Goal 4: Add Redis Queue Producer And Worker Consumer

Status: Finished

### Problem

Redis is available in Docker Compose and dependencies, but the app does not yet use it as a real queue.

### Target Outcome

The backend can enqueue grading work into Redis, and one or more worker consumers can process jobs with bounded concurrency.

### Implementation Notes

- Add a queue message containing `jobId`, durable `submissionKey` or `submissionId`, `graderType`, `institutionId`, `requestedBy`, and `attempt`.
- Add a Redis-backed `JobQueueService`.
- Add a worker service that consumes queue messages and runs jobs outside the request path.
- Make worker concurrency configurable by Spring properties.
- Keep queue names and Redis keys centralized in configuration.
- Redis workers must not depend on local `grading/uploads`.
- Each backend worker pod will consume messages and use its own Java thread pool.
- Redis is the durable queue; Postgres submission storage is the durable content store.

### Completion Notes

- Added `GradingJobMessage` with `jobId`, durable `submissionKey`, `graderType`, identity fields, and `attempt`.
- Replaced the local Runnable queue with `JobQueueService` publishing JSON messages to a Redis list.
- Added `GradingJobWorker`, which polls Redis and submits job execution to the local grading worker pool.
- Added `grading.queue.*` and `grading.worker.*` properties for queue enablement, queue name, worker enablement, worker concurrency, and poll timeout.
- Each backend app process can now publish and consume Redis jobs by default. In a multi-pod deployment, total local execution concurrency is roughly backend pod count times `grading.worker.concurrency`.
- Goal 5 still owns retries, dead-letter queues, worker ownership, and atomic claim behavior.

## Goal 5: Add Job Attempt, Retry, And Dead-Letter Tracking

Status: Not Started

### Problem

The current job table tracks success and failure, but it does not track attempts, worker ownership, retry limits, or dead-letter states.

### Target Outcome

Failed transient work can be retried safely, duplicate workers cannot process the same job at the same time, and permanently failed queue messages are visible.

### Implementation Notes

- Add job fields for `queued_at`, `attempt_count`, `max_attempts`, `last_attempt_at`, `queue_message_id`, and `worker_id`.
- Add status or failure handling for exhausted retries.
- Add repository methods for atomic job claiming.
- Default max attempts to `3`.
- Record failure messages from queue processing and Kubernetes failures.
- Before multiple Redis worker pods are enabled, add an atomic "claim job if queued" repository operation.
- This prevents two worker pods or two threads from executing the same job.

### Completion Notes

Not finished yet.

## Goal 6: Add Mock Institution And User Ownership

Status: Not Started

### Problem

Mock request identity exists, but jobs are not yet persisted or queried by institution/user. A university-style autograder needs ownership boundaries before multi-user burst testing is meaningful.

### Target Outcome

Jobs and submissions store institution/user ownership, and job list/detail queries can be scoped by institution.

### Implementation Notes

- Persist `institution_id` and `submitted_by` on job creation.
- Read optional `X-Mock-Institution` and `X-Mock-User` headers.
- Default missing headers to `local` and `anonymous`.
- Scope recent job queries by institution by default.
- Return ownership metadata in job detail responses.

### Completion Notes

Not finished yet.

## Goal 7: Prepare Kubernetes And kind For Multi-Worker Grading

Status: Not Started

### Problem

Kubernetes execution currently assumes a simple default namespace and local kind setup. Burst processing needs configurable namespace, worker capacity, labels, and permissions.

### Target Outcome

The backend can run many grader jobs safely in kind and later in a production-like cluster with clear resource limits and labels.

### Implementation Notes

- Move namespace, job TTL, poll interval, worker concurrency, and max active Kubernetes jobs into Spring properties.
- Add labels and annotations for `job-id`, `institution-id`, and `grader-type`.
- Add a dedicated namespace for grader jobs.
- Add RBAC manifests for creating, reading, logging, and deleting Jobs, Pods, and ConfigMaps.
- Expand kind config to support one control-plane and two worker nodes for local burst tests.
- Fix or remove stale manual Kubernetes templates that do not match the Fabric8-generated job shape.
- Backend worker threads decide how many jobs the backend tries to orchestrate.
- Kubernetes capacity decides how many grader pods can actually run.
- Add a max active Kubernetes jobs setting so backend workers do not flood the cluster when Redis has a large backlog.

### Completion Notes

Not finished yet.

## Goal 8: Update Frontend Submission Flow For Queued Jobs

Status: Partially Finished

### Problem

The frontend no longer calls `runJob` for every uploaded job, but it still needs clearer queue-oriented UX and future mock institution controls.

### Target Outcome

Submitting a file or zip upload clearly communicates queued background work, navigates to the jobs board, and relies on polling to show progress.

### Implementation Notes

- Keep the Goal 2 behavior where submit uploads once and does not fan out `runJob` calls.
- Polish status messages for large batches and queued/running states.
- Keep jobs board and job details polling active for `QUEUED` and `RUNNING`.
- Keep result download disabled until `resultJson` exists.
- Add mock institution headers later when the UI has a mock identity selector.
- Consider adding a simple queue/batch summary after upload once queue health endpoints exist.

### Completion Notes

Partially finished in Goal 2.

Solution notes so far:
- `SubmitJobPage.jsx` no longer calls `runJob` with `Promise.all`.
- Upload queues jobs on the backend and navigates to the jobs board.
- Remaining work is UI clarity, mock identity controls, and queue health visibility.

## Goal 9: Add Queue And System Health Visibility

Status: Not Started

### Problem

Burst processing needs visibility into queue depth, worker activity, stuck jobs, and Kubernetes failures. The current UI and backend expose only job rows.

### Target Outcome

Developers can quickly tell whether jobs are queued, running, blocked, retrying, or dead-lettered.

### Implementation Notes

- Add backend health data for Redis connectivity and queue depth.
- Add worker heartbeat or worker id tracking.
- Add a way to identify jobs stuck in `RUNNING` past their timeout.
- Add logs around enqueue, claim, Kubernetes start, Kubernetes finish, retry, and final failure.
- Consider a simple admin/debug endpoint for queue health before adding any complex dashboard.

### Completion Notes

Not finished yet.

## Goal 10: Add Burst And Failure Testing Scenarios

Status: Not Started

### Problem

The system needs test coverage that matches the target problem: many submissions arriving quickly, mixed grader outcomes, resource failures, and worker restarts.

### Target Outcome

There are repeatable tests or scripts that prove the queue and Kubernetes workers behave under burst load.

### Implementation Notes

- Add a script that uploads a zip or many submissions quickly.
- Test queueing more jobs than worker concurrency allows.
- Test mixed success, partial, validation failure, timeout, and resource-limit cases.
- Test worker restart behavior with queued and running jobs.
- Test duplicate worker claim protection.
- Track observed throughput and failure behavior in this document or a separate load-test note.

### Completion Notes

Not finished yet.
