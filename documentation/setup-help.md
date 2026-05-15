# Setup Help

Use this guide when local setup gets stuck or stale containers/images are left behind.

Start with the read-only diagnostic script:

```bash
python scripts/doctor.py
```

After the backend and at least one worker are running, validate the full grading path:

```bash
python scripts/smoke-test.py
```

If you are working on project submissions, load the project grader and run the project smoke scenario:

```bash
python scripts/setup-graders.py --grader fib-java-project
python scripts/smoke-test.py --scenario project-java-pass
```

## Stop Running Compose Services

```bash
# Default infrastructure only
docker compose down

# Backend profile
docker compose --profile app down

# Full stack profile
docker compose --profile full down
```

Use the same profile you used to start the services. Profiled containers may stay running if you run plain `docker compose down`.

## Reset The Database

This deletes the local Postgres volume:

```bash
docker compose --profile full down -v
docker compose up -d
docker exec -i ea-postgres psql -U postgres -d elastic_autograder < init/create_job.sql
```

Do not use `-v` if you want to keep local job history.

## Delete The kind Cluster

```bash
kind delete cluster --name elastic-autograder
```

Recreate it and reload grader images:

```bash
python scripts/setup-graders.py
```

## Remove Grader Images

Runtime base images:

```bash
docker image rm \
  ea-grader-runtime-python-base:v1 \
  ea-grader-runtime-java-base:v1 \
  ea-grader-runtime-cpp-base:v1
```

Sample grader images:

```bash
docker image rm \
  ea-grader-fibbonaci:v1 \
  ea-grader-fib-performance:v1 \
  ea-grader-fib-java:v1 \
  ea-grader-fib-cpp:v1 \
  ea-grader-twosum:v1 \
  ea-grader-memory-demo:v1
```

If an image is in use, stop Compose services first.

## Rebuild Grader Images

Use Docker cache by default:

```bash
python scripts/setup-graders.py
```

Force a clean rebuild:

```bash
python scripts/setup-graders.py --no-cache
```

Build/load one grader:

```bash
python scripts/setup-graders.py --grader fib-java
```

## Jobs Stuck In QUEUED

Check that a worker is running:

```bash
docker compose --profile app ps
curl http://localhost:8080/api/system/queue-health
```

If an older worker dropped Redis messages while Postgres jobs remained queued, inspect stranded jobs:

```bash
python scripts/requeue-stranded-jobs.py --dry-run
```

Requeue them:

```bash
python scripts/requeue-stranded-jobs.py
```

## Port Conflicts

If port 5173 is busy:

```bash
FRONTEND_PORT=5174 docker compose --profile full up -d --build
```

If port 8080 is busy, stop either the Gradle backend or the Compose backend. Do not run both at the same time.
