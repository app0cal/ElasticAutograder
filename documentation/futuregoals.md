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

Status: Finished
Priority: Near Term

### Problem

The project has several moving parts: frontend, backend API process, backend worker processes, Postgres, Redis, kind, Kubernetes resources, and grader images. A strong demo needs setup and recovery docs that match the current architecture.

### Target Outcome

Developers can reliably set up, test, debug, and demonstrate the distributed worker and multi-language grading system with fewer manual steps.

### Implementation Notes

- Keep stale docs aligned with the current async queue path.
- Keep frontend upload formats aligned with selected grader language so Java and C++ graders are usable from the browser.
- Make local worker startup expectations explicit: normal single-process testing should run worker-enabled, while API-only mode requires separate worker processes.
- Provide read-only diagnostics for Docker, Redis, Postgres, kind, Kubernetes namespace/RBAC, grader images, and backend health.
- Provide one end-to-end smoke test that creates a real job and verifies the API, queue, worker, and Kubernetes grader path.
- Document common failure modes and recovery steps.
- Keep the local kind workflow simple for contributors.

### Acceptance Checklist

- README and backend developer docs match the current async queue architecture.
- Setup docs include Redis, Postgres, kind, RBAC, worker replicas, and grader image validation.
- New contributor setup can be validated with one documented smoke test.
- Distributed worker demo commands are documented.
- Docs explain how to diagnose jobs stuck in `QUEUED` by checking worker startup logs, Redis queue depth, and Postgres job state.
- Common failure recovery steps are documented.

### Completion Notes

Finished for the local contributor and demo workflow. The submit page and upload API use grader language metadata so Python, Java, and C++ graders advertise and enforce the correct single-file source extensions while keeping `.zip` batch uploads available.

Recent QUEUED-job investigation confirmed an important local workflow distinction: `grading.worker.enabled=false` starts an API-only backend. In that mode uploads still create Postgres jobs and publish Redis messages, but jobs remain `QUEUED` until a worker-enabled backend process consumes the queue. Normal local testing should use `./gradlew bootRun --args='--spring.profiles.active=local'`; API-only mode should be paired with the Docker Compose `app` profile or another worker process.

The documentation now covers setup, daily startup, Docker Compose profiles, shutdown, common recovery steps, and release shape. `scripts/doctor.py` provides read-only checks for local tools, Compose services, kind, Kubernetes namespace/RBAC, grader images, and backend health. `scripts/smoke-test.py` creates one known-good Fibonacci job and waits for `SUCCEEDED`, printing queue-health-based troubleshooting hints if the job stays queued or fails.

Production deployment remains a separate future hardening track. This goal completes the local developer experience baseline rather than claiming a production-ready installer.

## Future Goal 7: Improve Student Feedback And Result Presentation

Status: Finished
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

Finished for the current frontend presentation scope. Job details now normalize stored result JSON from both array and `{ results: [...] }` shapes, render student-facing outcome text in the Summary card, and show individual result cards with test name, kind, pass/fail state, and safe runtime messages.

Failure details now avoid misleading messages for successful, partial, queued, running, failed, and dead-lettered jobs. Raw JSON download remains available for debugging, and the backend result schema is unchanged.

This does not add hidden-test policy, instructor-specific feedback controls, or richer backend result typing. Those remain future product/backend feedback concerns if assignments need per-test visibility rules.

## Future Goal 8: Support Multi-File And Project Submissions

Status: Finished
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

### Implementation Phases

1. Define the upload-mode contract so graders can declare `single_file`, `batch_zip`, or `project_zip`, and expose that behavior to the frontend.
2. Add durable project zip storage that preserves normalized relative paths and creates one queued job for a project archive.
3. Update job intake and queueing so project jobs remain separate from existing single-file and batch zip flows.
4. Deliver project files into Kubernetes as a mounted project directory instead of a single `submission.py` file.
5. Implement runtime support for executable `project_cases` manifests with project-level build and test commands.
6. Add at least one sample project grader and fixtures that prove pass, wrong-answer, build-error, and runtime-error behavior.
7. Update documentation and verification scripts so local users understand which upload modes are supported and how to test them.

### Acceptance Checklist

- Existing single-file and batch zip uploads continue to work.
- Project zip upload creates one job with multiple stored files.
- Path traversal, duplicate unsafe paths, empty archives, and oversized archives are rejected.
- A project grader can build and test a multi-file submission in Kubernetes.
- Tests cover project storage and ConfigMap or volume delivery behavior.

### Completion Notes

- Phase 1 added the grader `uploadMode` contract (`single_file`, `batch_zip`, `project_zip`) across backend config, grader API responses, and frontend accepted-file behavior.
- Phase 2 added durable project zip storage with normalized relative paths, project/file database records, validation limits, and one visible queued job per project archive.
- Phase 3 added explicit job submission kinds (`SINGLE_FILE`, `BATCH_FILE`, `PROJECT_ZIP`), exposes them in job responses, and prevents project jobs from entering execution paths before runtime support exists.
- Phase 4 added Kubernetes project directory delivery builders that map stored project files into `/work/project/<relative_path>` with ConfigMap item mappings while preserving existing single-file delivery.
- Phase 5 made `project_cases` executable in the runtime with optional project compile commands, per-case run commands, optional stdin, exact stdout comparison, and normalized result JSON. `PROJECT_ZIP` jobs now enqueue and dispatch through the existing backend queue/orchestrator path using `/work/project`.
- Phase 6 added the `fib-java-project` sample grader plus project zip fixtures for pass, wrong-answer, build-error, and runtime-error behavior.
- Phase 7 added project smoke scenarios, project verification commands, and the documentation updates needed to keep the project submission path easy to exercise locally.

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
- Support assignment metadata such as title, summary, due date, open/closed state, allowed languages, late policy, grader version, and visible result policy.
- Keep JSON grader config as a local/dev fallback until database management is mature.
- Use the existing institution/identity seams for scoping until a future institution-specific identity integration exists.

### Institutional Benefit

This turns the platform into coursework infrastructure instead of a raw grader launcher. Institutions gain durable ownership, assignment history, and instructor-managed submission windows without editing config files.

### What It Automates

- Assignment availability windows
- Assignment-scoped grader selection
- Submission grouping and history by course/assignment
- Assignment-level filtering for job history and reporting

### Acceptance Checklist

- Assignments can be created and listed through backend APIs.
- Submissions are associated with an assignment.
- Existing JSON graders can still be used for local development.
- Job history can be filtered by assignment.
- Tests cover institution scoping and assignment/job relationships.

### Completion Notes

Not finished yet.

## Future Goal 10: Add Hidden Tests, Rubric Controls, And Instructor-Facing Grader Management

Status: Not Started
Priority: Medium Term

### Problem

The current grader model is good enough for visible pass/fail cases, but real coursework needs hidden tests, weighted scoring, and safer workflows for changing graders without breaking live assignments.

### Target Outcome

Instructors can create, validate, preview, version, and activate grader definitions through controlled UI and API workflows, with hidden tests and rubric-based scoring supported as first-class grading features.

### Implementation Notes

- Add backend APIs for creating and updating grader definitions.
- Extend grader definitions to support visible tests, hidden tests, weighted rubric sections, and feedback visibility rules.
- Validate manifests, scoring rules, and resource settings before activation.
- Add grader draft, preview, active, inactive, and broken states.
- Version graders so assignments can point to a specific active revision.
- Track grader image build/load status separately from assignment visibility.
- Prevent submissions to graders that are invalid, unavailable, or still building.
- Add a clear rollback or deactivate path for broken graders.

### Institutional Benefit

This improves grading quality directly. Instructors can protect assignment integrity with hidden tests, award partial credit with rubric controls, and change graders through a safer workflow than editing config files by hand.

### What It Automates

- Grader validation before activation
- Versioned grader rollout and rollback
- Hidden versus visible feedback handling
- Weighted score calculation
- Prevention of submissions to invalid or still-building graders

### Acceptance Checklist

- A grader definition can be saved as draft.
- Invalid manifests are rejected before activation.
- Active graders are visible to the submit page only when ready.
- Broken grader state is visible to instructors.
- Tests cover validation, activation, rubric scoring, and hidden feedback visibility rules.

### Completion Notes

Not finished yet.

## Future Goal 11: Add Submission Retention, Binary Storage, And Object-Storage Support

Status: Not Started
Priority: Long Term

### Problem

Durable submission and result storage is necessary for distributed workers, but the current path is still oriented around local development and text-heavy fixtures. Institutions need larger project support, binary-safe storage, and lifecycle rules so storage growth stays manageable.

### Target Outcome

Institutions can store larger and binary submissions through a storage abstraction that supports object storage, while applying retention, export, and cleanup policies without losing the metadata needed for reporting or audits.

### Implementation Notes

- Add a submission storage abstraction with local filesystem support for development and object-storage support for deployed environments.
- Keep submission payload blobs separate from durable metadata records.
- Support binary-safe storage and retrieval for larger project archives and future artifact types.
- Add retention settings at system or institution level.
- Add scheduled cleanup for old submissions and results.
- Preserve job metadata needed for audit and reporting.
- Add export support before destructive cleanup where retention policies require it.

### Institutional Benefit

This makes the platform viable for larger courses and real project submissions. Institutions can keep the records they need, expire payloads they do not want to retain forever, and avoid local-disk assumptions that break down in production.

### What It Automates

- Payload offloading to object storage
- Storage lifecycle enforcement
- Export-before-delete workflows
- Separation of retained summaries from deleted submission contents

### Acceptance Checklist

- Storage providers can store and retrieve project archives and binary submission payloads.
- Retention settings are configurable.
- Cleanup can delete old submission contents without breaking retained job summaries.
- Exports can be generated before cleanup.
- Cleanup actions are logged without exposing submission contents.
- Tests cover provider behavior, retention eligibility, and deletion behavior.

### Completion Notes

Not finished yet.

## Future Goal 12: Harden Sandbox And Security Boundaries

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
- Validate grader/runtime configurations against safe defaults before execution.
- Document what security guarantees the local kind setup does and does not provide.

### Institutional Benefit

This makes the platform reviewable by institutional infrastructure and security teams. It reduces the risk that untrusted student code can escape its execution boundary or reach services it should not see.

### What It Automates

- Safe-by-default pod configuration
- Blocking or flagging unsafe execution settings
- Network isolation for standard grading jobs
- Enforcement of resource and credential separation

### Acceptance Checklist

- Grader pods do not run privileged.
- Network access can be disabled for grader jobs.
- Resource quotas and limits are documented and enforced.
- Backend/database credentials are not mounted into grader pods.
- Security assumptions are documented for local and production-like deployments.

### Completion Notes

Not finished yet.

## Future Goal 13: Add Large-Burst Validation And Operational Observability

Status: Not Started
Priority: Long Term

### Problem

The system currently has enough telemetry and tooling for local verification, but institutions need stronger observability and load validation to survive class deadlines and operator triage under burst traffic.

### Target Outcome

Operators can measure, diagnose, and recover from queue backlogs, slow grading, failed pods, and burst submission events with clear metrics, logs, and runbooks.

### Implementation Notes

- Add structured metrics for queue depth, dispatch latency, grading latency, pod startup time, retry counts, and failure reasons.
- Add load and soak validation paths that simulate deadline-style submission bursts.
- Add stuck-job detection and better operator tooling for requeue and triage.
- Expose assignment- and institution-level operational views or equivalent structured diagnostics.
- Add runbooks for common failure classes such as queue backlog, broken grader image, storage failure, and worker starvation.

### Institutional Benefit

This makes the platform predictable under real teaching loads. Institutions care about whether the system still behaves correctly when hundreds or thousands of students submit near a deadline, not just whether a single happy-path job succeeds.

### What It Automates

- Queue health diagnosis
- Failure categorization
- Burst-capacity validation
- Faster operator response to stuck or degraded grading pipelines

### Acceptance Checklist

- Metrics and logs cover queue, runtime, storage, and orchestrator health.
- Load tests can simulate high-burst submission windows.
- Stuck jobs can be detected and surfaced automatically.
- Operators have documented commands and workflows for common failure modes.
- Tests cover metrics emission, backlog detection, and failure-triage behavior.

### Completion Notes

Not finished yet.

## Future Goal 14: Improve Submission Lifecycle And Institution Deployment Documentation

Status: Not Started
Priority: Long Term

### Problem

The platform has setup and smoke-test documentation, but institutions still need a clearer end-to-end operating model for grader authoring, assignment setup, deployment, submission handling, retention, and failure recovery.

### Target Outcome

Instructors and operators can deploy, verify, and run the autograder through documented workflows without reverse-engineering the codebase or relying on local tribal knowledge.

### Implementation Notes

- Document the end-to-end lifecycle: create course, create assignment, attach grader version, deploy grader, verify, submit, review results, retain/export/cleanup.
- Separate documentation by audience: instructor, grader author, and platform operator.
- Expand deployment docs for local demo versus institutional cluster installation.
- Add runbooks for image build failures, queue backlog, invalid grader activation, storage issues, and stuck jobs.
- Keep smoke tests and release checks aligned with the documented workflows.

### Institutional Benefit

This reduces adoption friction. Institutions can evaluate and operate the platform with less source-code knowledge, lower support cost, and fewer one-off setup mistakes.

### What It Automates

- Standardized onboarding and deployment verification
- Repeatable smoke checks for new environments
- Predictable operator response paths for common incidents

### Acceptance Checklist

- Documentation covers instructor, grader author, and operator workflows.
- Fresh-start deployment instructions can be followed without missing hidden prerequisites.
- Smoke tests match the documented verification path.
- Runbooks exist for the main operational failure classes.
- Documentation is updated alongside new workflow features.

### Completion Notes

Not finished yet.
