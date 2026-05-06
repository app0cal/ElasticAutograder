# Distributed Worker Demo

Future Goal 1 is demonstrated with Docker Compose worker replicas:

- `backend-api` accepts uploads and writes durable queued jobs to Postgres.
- `backend-api` publishes Redis queue messages.
- `backend-worker` containers consume Redis messages independently.
- Postgres `claimQueuedJob` atomically moves one queued job to `RUNNING`, so duplicate Redis delivery or competing workers do not execute the same job twice.
- Kubernetes grader Jobs still run in the local kind cluster.

This is a distributed worker architecture demo on one machine. It is not yet a Kubernetes-native backend deployment. A later stage should add backend API and worker Deployments, Services, config, secrets, and RBAC for running the backend itself inside Kubernetes.

## Prerequisites

Create the kind cluster and load grader images first:

```bash
python scripts/setup-graders.py
```

Initialize Postgres if this is a fresh database:

```bash
docker compose up -d postgres redis
docker exec -i ea-postgres psql -U postgres -d elastic_autograder < init/create_job.sql
```

The Compose backend services mount `${HOME}/.kube` and use host networking so the containers can reach the same local kind API server that `kubectl` uses. They also mount `./config` at `/config` because the backend loads `graders.json` from `/app/../config/graders.json` inside the container. This matches the default kind kubeconfig on Linux. On Docker Desktop, enable host networking or run the backend locally with the same properties if your Docker version cannot expose the host kind API server to containers.

## Start One API And Three Workers

Build and start the API plus worker replicas:

```bash
docker compose up --build --scale backend-worker=3 backend-api backend-worker
```

The API process runs with:

```text
GRADING_WORKER_ENABLED=false
```

Each worker process runs with:

```text
SPRING_MAIN_WEB_APPLICATION_TYPE=none
GRADING_WORKER_ENABLED=true
GRADING_WORKER_ID_PREFIX=compose-worker
```

Worker IDs are unique values such as `compose-worker-<uuid>`.

The worker process is intentionally non-web, so the Redis polling thread keeps the JVM alive while it waits for queue messages. Postgres and Redis health checks gate backend startup to avoid transient connection failures during container boot.

## Run A Burst

In another terminal:

```bash
python scripts/burst-test.py queue-pressure --count 50
```

During the run, inspect queue health:

```bash
curl http://localhost:8080/api/system/queue-health
```

Because this endpoint is served by `backend-api`, `workerEnabled` should be `false` there. The distributed worker evidence is in `recentRunningJobs[].workerId`, Redis queue depth, durable Postgres job counts, and the worker container logs.

The burst script summary prints:

- submitted job count
- elapsed time and throughput
- max Redis queue depth
- max durable queued jobs in Postgres
- max running jobs
- stale running job count
- dead-letter count
- observed running worker IDs

Multiple `compose-worker-...` IDs in `recentRunningJobs` or in the burst summary demonstrate that more than one worker process claimed work.

## Capacity Terms

`queueDepth` is Redis list depth. It shows messages waiting to be consumed by workers.

`jobCounts.QUEUED` is durable Postgres backlog. It is the source of truth for jobs that still need a successful atomic claim.

`backend-worker` replica count is the number of independent backend worker processes.

`grading.worker.concurrency` is the local thread pool size inside each worker process.

Total backend execution slots are:

```text
backend-worker replicas * grading.worker.concurrency
```

`grading.kubernetes.max-active-jobs` is the cap for active grader pods/jobs in the kind cluster. This can be lower than backend execution slots and still limit actual grader pod concurrency.

The correct claim is "processed a burst through distributed workers", not "ran all submitted jobs concurrently".

## Duplicate Execution Guard

Redis decides which worker receives a queue message, but Postgres decides whether execution may start. Workers call `claimQueuedJob`, which only updates rows currently in `QUEUED` state. If two workers race on the same job id, one update succeeds and the other returns zero rows; the losing worker logs and skips execution.
