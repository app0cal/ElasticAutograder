# Architecture

Elastic Autograder is a local-first distributed autograding demo. It accepts submissions through a web UI, stores job state durably, queues work through Redis, and runs untrusted grader execution inside Kubernetes Jobs.

## Components

```text
React frontend
  -> Spring Boot backend API
    -> PostgreSQL for durable jobs, submissions, and results
    -> Redis for queue messages
    -> Backend worker threads/processes
      -> kind Kubernetes cluster
        -> grader Job pod
          -> grader runtime + manifest + submitted file
```

## Frontend

The frontend is a React/Vite app under `frontend/`.

- Local development uses `npm run dev`.
- The Compose `full` profile builds the app and serves it from nginx.
- API helpers use a shared `API_BASE` value so local Vite can call `http://localhost:8080/api` while the nginx container can proxy `/api`.

## Backend API

The backend is a Spring Boot app under `backend/`.

It owns:

- grader catalog loading from `config/graders.json`
- upload validation
- job creation
- job status and result APIs
- queue health APIs
- Kubernetes grader orchestration

The backend API can run through Gradle for development or inside Docker through the Compose `app` and `full` profiles.

## Queue And State

PostgreSQL is the durable source of truth. Job rows record queued, running, terminal status, worker ownership, score, failure reason, and result details.

Redis is the delivery mechanism for queued work. Redis decides which worker sees a message, but Postgres decides whether that job can be claimed. This avoids duplicate execution when multiple workers race on the same job.

## Workers

Workers can run inside the same backend process or as separate backend-worker containers.

Each worker:

1. reads a Redis queue message
2. atomically claims the matching Postgres job
3. creates a Kubernetes grader Job
4. waits for completion
5. parses grader output
6. stores the normalized result

## Kubernetes Grader Jobs

Grading execution happens in the local kind cluster. The backend creates a ConfigMap containing the submitted file and a Kubernetes Job using the selected grader image.

Each grader image contains:

- shared runtime code from `graders/runtime`
- one grader manifest from `graders/<grader-key>/manifest.json`
- language tools when needed, such as Java or g++

The grader runtime emits normalized JSON so the backend does not need language-specific result parsing.

## Grader Runtime Contract

Current supported problem types:

- Python `function_cases`
- Java/C++ single-file `stdio_cases`

The manifest format is documented in [graders/manifestGuide.md](../graders/manifestGuide.md). Institutions extend the platform by adding grader folders under `graders/` and catalog entries in `config/graders.json`.


## Boundaries

This framework does NOT provide production deployment architecture. The local setup relies on host Docker, host kind, and local kubeconfig access. Production use would need stricter sandboxing, image publishing, secret management, Kubernetes RBAC review, authentication, and retention policies. Which all depend on the institutions desir for local testing so to make this as extendible as possible we're limiting the authentication to make it as easy to remove or change to integrate with other services used.
