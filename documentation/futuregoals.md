# Elastic Autograder Future Goals

This document tracks larger improvements after the current resilience goals in `documentation/goals.md`.

The near-term roadmap is intentionally resume and systems focused. The strongest remaining story is:
- Redis-backed distributed job queueing.
- Multiple backend worker processes consuming from the same queue.
- Postgres atomic job claims preventing duplicate execution.
- Kubernetes Jobs isolating untrusted grader execution.
- Java and C++ language-specific grader runtimes.
- Measured mixed burst experiments with realistic success and failure outcomes.

When a goal is finished, mark it as `Finished` and add notes describing the solution, important files changed, tradeoffs, and follow-up work.

## Priority Legend

- `Immediate`: Next features that complete the distributed systems and multi-language demo story.
- `Near Term`: Polish, documentation, and feedback improvements that support the demo.
- `Medium Term`: Product features for instructors, students, assignments, and courses.
- `Long Term`: Production readiness, security, retention, and operations work.

## Future Goal 1: Demonstrate Distributed Worker Execution

Status: Finished
Priority: Immediate

### Problem

The backend can already enqueue and consume Redis jobs, but the current local workflow usually runs one backend process that acts as both API server and worker. The distributed systems story is stronger if multiple independent worker processes consume the same Redis queue while Postgres remains the source of truth for claims and results.

### Target Outcome

Developers can run one API-facing backend process plus multiple backend worker processes locally, then prove that the workers drain the queue together without duplicate job execution.

### Implementation Notes

- Add clear API-only and worker-enabled runtime modes using existing `grading.worker.enabled` and queue properties.
- Prefer Docker Compose worker replicas for the first demonstrable setup.
- Keep the API process responsible for accepting uploads and publishing Redis messages.
- Run worker replicas as separate backend processes connected to the same Redis, Postgres, and Kubernetes cluster.
- Preserve the existing Postgres `claimQueuedJob` operation as the duplicate-execution guard.
- Add worker identity visibility to the demo output so it is clear different processes handled jobs.
- Document total orchestration concurrency as:
  ```text
  worker replica count * grading.worker.concurrency
  ```
- Be explicit that Kubernetes capacity and `grading.kubernetes.max-active-jobs` still bound actual grader pod execution.

### Acceptance Checklist

- A local command can start one API backend and at least three worker replicas.
- Uploads through the API backend are processed by multiple worker processes.
- Job rows show worker ids from different worker processes.
- Duplicate claim protection is covered by tests and demonstrated in documentation.
- Queue health shows backlog, running counts, dead-lettered counts, and stale running counts during distributed processing.
- Documentation explains the difference between queued jobs, worker concurrency, and Kubernetes pod concurrency.

### Completion Notes

Finished as a Docker Compose distributed-worker demo. The implemented stage runs one `backend-api` process with `grading.worker.enabled=false` and scalable `backend-worker` processes with `grading.worker.enabled=true`, all sharing Redis, Postgres, and the host kind Kubernetes context. Worker IDs can be prefixed with `grading.worker.id-prefix`, and queue health exposes recent running jobs so demos can show which worker processes claimed work during a burst.

This completes the resume-safe claim "distributed worker architecture." It does not claim Kubernetes-native backend workers; that remains a later deployment stage with backend API/worker Kubernetes manifests, Service, config, secret/RBAC, and kubeconfig strategy.

## Future Goal 2: Generalize The Grader Runtime Contract

Status: Finished
Priority: Immediate

### Problem

The current Python grader runtime assumes submissions expose a function named by `entry_function`. Java and C++ submissions should not be forced into Python-style function-call semantics.

### Target Outcome

Graders can use a command-based contract while all runtimes still emit one normalized result JSON shape for the backend and frontend.

### Implementation Notes

- Preserve existing Python function-call graders unchanged.
- Add a command-based grading mode with compile command, run command, stdin/stdout test cases, timeout, and resource settings.
- Keep result JSON normalized across languages:
  ```text
  status
  validation_passed
  tests_passed
  tests_total
  score
  error_message
  results[]
  ```
- Classify compile errors, runtime errors, wrong answers, timeouts, resource-limit failures, result parse errors, and internal grader/config errors.
- Keep grader output JSON small and do not log submission contents or full result bodies.
- Document the runtime contract so new language images can be added without backend changes.

### Acceptance Checklist

- Existing Python graders still work.
- At least one command-based grader can compile, run, compare stdout, and emit normalized JSON.
- Compile failures produce a clear terminal job failure.
- Runtime failures produce a clear terminal job failure.
- Wrong answers still produce normal failed or partial grading results.
- Backend result mapping does not need language-specific branches for the shared result schema.

### Completion Notes

Finished with v2 manifest support for Python `function_cases` and command-based `stdio_cases`. The runtime now supports optional compile commands, per-case run commands, stdin/stdout comparison, timeouts, stderr capture, and normalized result JSON shared with the existing backend result mapper. Existing legacy Python graders continue to run unchanged.

## Future Goal 3: Add Java Submission Support

Status: Finished
Priority: Immediate

### Problem

The platform is currently Python-only. Java support is a high-value resume feature because Java is common in coursework and backend engineering.

### Target Outcome

The system can grade one-file Java submissions in isolated Kubernetes Jobs while preserving the existing queue, storage, result, and health models.

### Implementation Notes

- Add Java language metadata to grader definitions and job responses where useful.
- Add a Java grader image/runtime that can compile and run `Main.java`.
- Use command-based grading for v1:
  ```text
  javac Main.java
  java Main
  ```
- Use stdin/stdout test cases for sample assignments.
- Add Java fixture submissions for pass, wrong answer, compile error, runtime error, and timeout.
- Keep Java v1 to single-file submissions; project zip support is a later goal.
- Set Java timeouts high enough to include JVM startup under local kind load.

### Acceptance Checklist

- Java graders appear in the grader catalog.
- Uploading `Main.java` creates queued jobs and stores submission content durably.
- Java pass, wrong answer, compile error, runtime error, and timeout fixtures reach expected terminal states.
- Java results render through the existing jobs board and details page.
- Backend tests cover grader config loading and language/default behavior.
- Grader setup builds and loads Java grader images into kind.

### Completion Notes

Finished for single-file Java stdin/stdout graders. The Java Fibonacci grader uses v2 `stdio_cases`, compiles staged submissions as `Main.java`, runs `javac Main.java` and `java Main`, and includes pass, wrong-answer, compile-error, and runtime-error fixtures. Grader config carries `language: "java"`, Fabric8 injects `GRADER_LANGUAGE`, and setup builds Java-capable grader images.

## Future Goal 4: Add C++ Submission Support

Status: Finished
Priority: Immediate

### Problem

C++ support strengthens the systems angle of the project and exercises compiled-language resource limits more realistically than Python-only grading.

### Target Outcome

The system can grade one-file C++ submissions in isolated Kubernetes Jobs while preserving the same command-based runtime contract and normalized result schema used by Java.

### Implementation Notes

- Add a C++ grader image/runtime with `g++`.
- Use command-based grading for v1:
  ```text
  g++ -std=c++17 main.cpp -O2 -o main
  ./main
  ```
- Use stdin/stdout test cases for sample assignments.
- Add C++ fixture submissions for pass, wrong answer, compile error, runtime error, timeout, and resource-limit behavior where practical.
- Keep C++ v1 to single-file submissions.
- Preserve CPU and memory requests/limits per grader definition.
- Mention Rust as a later optional language after command-based grading is stable, not part of the immediate implementation.

### Acceptance Checklist

- C++ graders appear in the grader catalog.
- Uploading `main.cpp` creates queued jobs and stores submission content durably.
- C++ pass, wrong answer, compile error, runtime error, and timeout fixtures reach expected terminal states.
- C++ results render through the existing jobs board and details page.
- Backend tests cover language routing and config validation.
- Grader setup builds and loads C++ grader images into kind.

### Completion Notes

Finished for single-file C++ stdin/stdout graders. The C++ Fibonacci grader uses v2 `stdio_cases`, compiles staged submissions as `main.cpp`, runs `g++ -std=c++17 -O2 -Wall -Wextra main.cpp -o main` and `./main`, and includes pass, wrong-answer, compile-error, and runtime-error fixtures. Grader config carries `language: "cpp"`, Fabric8 injects `GRADER_LANGUAGE`, and setup builds C++-capable grader images.

## Future Goal 5: Document Large Mixed Burst Experiments

Status: In Progress
Priority: Immediate

### Problem

The current burst script proves queueing with repeated fixtures, but the strongest demo should show a varied workload across languages and failure modes. It should also avoid overclaiming "1,000 concurrent pods" when the realistic goal is accepting and draining a 1,000-job burst.

### Target Outcome

There is documented evidence that the system can accept large mixed bursts, distribute work across multiple worker processes, and drain the backlog with clear throughput and failure reporting.

### Implementation Notes

- Extend the existing burst script to mix Python, Java, and C++ submissions with reproducible random selection.
- Add a `mixed-language-burst` scenario that accepts `--seed` and prints the chosen workload composition.
- Include passing, partial, wrong-answer, compile-error, runtime-error, timeout, and resource-limit fixtures.
- Run and document 100, 500, and 1,000 job burst experiments.
- Report:
  ```text
  submitted jobs
  worker replicas
  total worker concurrency
  max Redis queue depth
  max durable queued jobs
  max running jobs
  elapsed drain time
  throughput
  terminal status counts
  failure reason counts
  stale running jobs
  dead-lettered jobs
  ```
- Use precise wording: "processed a 1,000-job burst through distributed workers" instead of "ran 1,000 jobs concurrently."
- Record environment details such as machine specs, kind node count, worker replica count, worker concurrency, max-active Kubernetes jobs, and grader resource requests.

### Acceptance Checklist

- A randomized mixed-language burst scenario can be run from one documented command.
- Re-running the same mixed-language command with the same seed selects the same fixture mix.
- Results from at least one 100-job, 500-job, and 1,000-job run are recorded.
- Documentation explains observed bottlenecks and whether they were Redis, backend workers, Kubernetes capacity, image startup, or grader timeout.
- Failed jobs are categorized by expected failure reason.
- No stale running jobs remain at the end of a successful experiment.
- Dead-lettered jobs, if any, are explained.

### Completion Notes

Not finished yet. Partial progress: the burst script now includes a weighted `mixed-language-burst` scenario that randomly selects Python, Java, C++, timeout, and memory-limit fixtures. The selection is reproducible with `--seed`, and the script prints the selected fixture composition before upload.

A 500-job run exposed `EA-QUEUE-001`: Redis drained to zero while durable Postgres jobs remained `QUEUED`, caused by workers popping messages faster than local executor capacity. The worker now applies local backpressure before popping Redis messages and requeues payloads if task dispatch is rejected. A local recovery helper can requeue stranded pre-fix jobs. Measured 100, 500, and 1,000 job results still need to be recorded after rerunning with the fixed worker.

## Future Goal 6: Improve Developer And Deployment Experience

Status: In Progress
Priority: Near Term

### Problem

The project has several moving parts: frontend, backend API process, backend worker processes, Postgres, Redis, kind, Kubernetes resources, and grader images. A strong demo needs setup and recovery docs that match the current architecture.

### Target Outcome

Developers can reliably set up, test, debug, and demonstrate the distributed worker and multi-language grading system with fewer manual steps.

### Implementation Notes

- Update stale docs that still describe local upload storage or browser-triggered grading as the normal path.
- Keep frontend upload formats aligned with selected grader language so Java and C++ graders are usable from the browser.
- Make local worker startup expectations explicit: normal single-process testing should run worker-enabled, while API-only mode requires separate worker processes.
- Document that `./gradlew bootRun --args='--spring.profiles.active=local --grading.worker.enabled=false'` accepts uploads and queues jobs but will not finish them unless another worker-enabled process is running.
- Add health-check commands for Docker, Redis, Postgres, kind, Kubernetes namespace/RBAC, worker replicas, and grader images.
- Document common failure modes and recovery steps.
- Add Docker Compose examples for API-only and worker-replica processes.
- Add production-style deployment notes for Redis, Postgres, backend workers, Kubernetes namespace/RBAC, and grader image publication.
- Keep the local kind workflow simple for contributors.

### Acceptance Checklist

- README and backend developer docs match the current async queue architecture.
- Setup docs include Redis, Postgres, kind, RBAC, worker replicas, and grader image validation.
- New contributor setup can be validated with one documented smoke test.
- Distributed worker demo commands are documented.
- Docs explain how to diagnose jobs stuck in `QUEUED` by checking worker startup logs, Redis queue depth, and Postgres job state.
- Common failure recovery steps are documented.

### Completion Notes

Not finished yet. Partial progress: the submit page and upload API now use grader language metadata so Python, Java, and C++ graders advertise and enforce the correct single-file source extensions while keeping `.zip` batch uploads available.

Recent QUEUED-job investigation confirmed an important local workflow distinction: `grading.worker.enabled=false` starts an API-only backend. In that mode uploads still create Postgres jobs and publish Redis messages, but jobs remain `QUEUED` until a worker-enabled backend process consumes the queue. Normal local testing should use `./gradlew bootRun --args='--spring.profiles.active=local'`; API-only mode should be paired with the Docker Compose `app` profile or another worker process.

## Future Goal 7: Improve Student Feedback And Result Presentation

Status: Not Started
Priority: Near Term

### Problem

Raw result JSON is useful for debugging, but Java and C++ grading introduces compile errors, runtime errors, stdout mismatches, and timeout messages that need clearer presentation.

### Target Outcome

Job details show clear feedback for normalized test results and language-specific failure categories without requiring users to inspect raw JSON.

### Implementation Notes

- Render compile errors, runtime errors, wrong answers, timeouts, and resource-limit failures clearly.
- Show test names, pass/fail state, safe messages, score, and test counts.
- Keep raw JSON download available for debugging.
- Avoid exposing hidden expected outputs or private grader diagnostics if hidden tests are later added.
- Keep the result schema frontend-compatible across Python, Java, and C++.

### Acceptance Checklist

- Job details render structured feedback for success, partial, wrong-answer, compile-error, runtime-error, timeout, and resource-limit cases.
- Result downloads remain available for completed jobs.
- Frontend handles missing optional fields gracefully.
- Tests or documented manual checks cover representative result payloads.

### Completion Notes

Not finished yet.

## Future Goal 8: Support Multi-File And Project Submissions

Status: Not Started
Priority: Medium Term

### Problem

One-file Java and C++ submissions are enough for the first multi-language demo, but many real courses expect multi-file projects with directories, helper files, configuration files, and test fixtures.

### Target Outcome

The system can treat a zip archive as one project submission when an assignment requires it, while still supporting the current "one job per extracted file" batch behavior.

### Implementation Notes

- Add an assignment or grader setting for upload mode: single file, batch zip, or project zip.
- Preserve safe directory structure for project submissions.
- Store project files durably without allowing path traversal or unsafe absolute paths.
- Add manifest fields for entrypoint file, build command, test command, allowed file patterns, and ignored paths.
- Ensure Kubernetes delivery can mount a project directory, not only `submission.py`.
- Keep size, file count, and archive depth limits configurable.

### Acceptance Checklist

- Existing single-file and batch zip uploads continue to work.
- Project zip upload creates one job with multiple stored files.
- Path traversal, duplicate unsafe paths, empty archives, and oversized archives are rejected.
- A project grader can build and test a multi-file submission in Kubernetes.
- Tests cover project storage and ConfigMap or volume delivery behavior.

### Completion Notes

Not finished yet.

## Future Goal 9: Add Assignment And Course Management

Status: Not Started
Priority: Medium Term

### Problem

Grader definitions currently come from JSON config. That is simple for local development, but a university-style autograder needs durable courses, assignments, due dates, ownership, and visibility rules.

### Target Outcome

Institutions can manage courses and assignments in the database, and jobs can be tied to the assignment they were submitted for.

### Implementation Notes

- Add durable course and assignment models owned by an institution.
- Link graders, submissions, and jobs to assignments.
- Support assignment metadata such as title, summary, due date, open/closed state, allowed languages, and visible test policy.
- Keep JSON grader config as a local/dev fallback until database management is mature.
- Scope course and assignment queries by institution and user role once real auth exists.

### Acceptance Checklist

- Assignments can be created and listed through backend APIs.
- Submissions are associated with an assignment.
- Existing JSON graders can still be used for local development.
- Job history can be filtered by assignment.
- Tests cover institution scoping and assignment/job relationships.

### Completion Notes

Not finished yet.

## Future Goal 10: Add Instructor-Facing Grader Management

Status: Not Started
Priority: Medium Term

### Problem

Adding or changing graders currently requires editing config files and rebuilding images manually. Instructors need safer workflows for configuring assignments without breaking the running system.

### Target Outcome

Instructors can create, validate, preview, and activate grader definitions through controlled UI and API workflows.

### Implementation Notes

- Add backend APIs for creating and updating grader definitions.
- Validate manifests and resource settings before activation.
- Track grader image build/load status separately from assignment visibility.
- Prevent submissions to graders that are invalid, unavailable, or still building.
- Add a clear rollback or deactivate path for broken graders.

### Acceptance Checklist

- A grader definition can be saved as draft.
- Invalid manifests are rejected before activation.
- Active graders are visible to the submit page only when ready.
- Broken grader state is visible to instructors.
- Tests cover validation and activation rules.

### Completion Notes

Not finished yet.

## Future Goal 11: Add Real Authentication And Role-Based Authorization

Status: Not Started
Priority: Long Term

### Problem

Mock identity headers are useful for local development and demos, but they are not real authentication. A deployed autograder needs users, roles, and enforceable access boundaries.

### Target Outcome

The system supports authenticated users with institution, course, assignment, and role-based access controls.

### Implementation Notes

- Replace mock headers with a real authentication provider.
- Define roles such as student, instructor, institution admin, and system admin.
- Enforce institution and course boundaries on all read/write APIs.
- Keep a local development mode that can still use mock identities.
- Add audit fields for who created assignments, submitted work, and changed grader settings.

### Acceptance Checklist

- Users must authenticate before using protected APIs.
- Students can only see their own submissions unless policy says otherwise.
- Instructors can manage assignments for their courses.
- Institution admins cannot access other institutions.
- Tests cover authorization success and denial paths.

### Completion Notes

Not finished yet.

## Future Goal 12: Add Submission Retention And Storage Lifecycle Policies

Status: Not Started
Priority: Long Term

### Problem

Durable submission and result storage is necessary for distributed workers, but storage will grow without lifecycle rules.

### Target Outcome

Institutions can define how long submissions, job metadata, and result bodies are retained, exported, or deleted.

### Implementation Notes

- Add retention settings at system or institution level.
- Add scheduled cleanup for old submissions and results.
- Preserve job metadata needed for audit and reporting.
- Add export support before destructive cleanup.
- Consider object storage for larger or binary project submissions.

### Acceptance Checklist

- Retention settings are configurable.
- Cleanup can delete old submission contents without breaking retained job summaries.
- Exports can be generated before cleanup.
- Cleanup actions are logged without exposing submission contents.
- Tests cover retention eligibility and deletion behavior.

### Completion Notes

Not finished yet.

## Future Goal 13: Harden Sandbox And Security Boundaries

Status: Not Started
Priority: Long Term

### Problem

Student code is untrusted. Kubernetes isolation, namespace/RBAC, and resource limits are a strong start, but production use needs stricter sandboxing and security review.

### Target Outcome

Grader pods run with least privilege, constrained resources, restricted network/filesystem access, and clear policies for handling untrusted code.

### Implementation Notes

- Review container privileges, service accounts, namespace policies, and RBAC.
- Add no-network grading support where assignments do not need network access.
- Use read-only root filesystems where practical.
- Add seccomp, AppArmor, or equivalent runtime profiles when supported.
- Keep grader pods separate from backend and database credentials.
- Document what security guarantees the local kind setup does and does not provide.

### Acceptance Checklist

- Grader pods do not run privileged.
- Network access can be disabled for grader jobs.
- Resource quotas and limits are documented and enforced.
- Backend/database credentials are not mounted into grader pods.
- Security assumptions are documented for local and production-like deployments.

### Completion Notes

Not finished yet.
