# Burst And Failure Testing

This project includes a local burst/failure script for exercising the Redis queue, worker pool, Kubernetes grader jobs, and Goal 9 queue-health endpoint.

The script is a local reproducibility tool, not a production benchmark and not a CI gate. It submits normal jobs through public APIs and does not reset Postgres, purge Redis, or delete Kubernetes resources.

## Prerequisites

From the project root:

```bash
docker compose up -d
python scripts/setup-graders.py
cd backend
./gradlew bootRun --args='--spring.profiles.active=local'
```

Use the `dev` profile instead of `local` if you want backend startup to run grader setup automatically.

## Scenarios

Fast smoke check:

```bash
python scripts/burst-test.py success-burst --count 8
```

Default success burst with 50 jobs:

```bash
python scripts/burst-test.py success-burst
```

Queue pressure with 50 jobs, validating that Redis queue depth or durable queued backlog rises while workers drain the work:

```bash
python scripts/burst-test.py queue-pressure
```

Mixed outcomes:

```bash
python scripts/burst-test.py mixed-burst --count 12
```

Failure scenarios:

```bash
python scripts/burst-test.py performance-timeout --count 2
python scripts/burst-test.py memory-limit --count 2
```

Useful overrides:

```bash
python scripts/burst-test.py success-burst --count 50 --concurrency 10 --timeout-seconds 300
python scripts/burst-test.py success-burst --fixture mocksubmission/fib/fibpass1.py --grader fib
python scripts/burst-test.py success-burst --institution local --user burst-tester
```

## Output

The script prints upload progress, queue depth, queued/running counts, terminal status totals, elapsed time, throughput, failure reasons, stale running job count, and global dead-letter count.

When queue health includes running jobs, the script also prints the distinct worker IDs observed during polling. In the distributed Compose demo, seeing multiple `compose-worker-...` values proves that separate worker containers participated while the burst drained.

It exits non-zero when uploads fail, submitted jobs do not reach terminal states before timeout, expected statuses/failure reasons do not match, stale running jobs are observed, or `queue-pressure` never observes Redis queue depth or durable queued backlog above worker capacity.
