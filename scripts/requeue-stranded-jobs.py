#!/usr/bin/env python3
"""
Requeue local jobs stranded by a drained Redis queue.

This is a local development recovery tool for the Docker Compose Postgres/Redis
setup. It re-publishes Redis messages for durable Postgres jobs that are still
QUEUED and have not been claimed by a worker.
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from typing import Any


DEFAULT_POSTGRES_CONTAINER = "ea-postgres"
DEFAULT_REDIS_CONTAINER = "ea-redis"
DEFAULT_DATABASE = "elastic_autograder"
DEFAULT_POSTGRES_USER = "postgres"
DEFAULT_QUEUE = "grading-jobs"


def main() -> int:
    args = parse_args()
    jobs = fetch_stranded_jobs(args)

    if not jobs:
        print("No stranded queued jobs found.")
        return 0

    print(f"Found {len(jobs)} stranded queued job(s).")
    if args.dry_run:
        for job in jobs[:20]:
            print(json.dumps(to_message(job), sort_keys=True))
        if len(jobs) > 20:
            print(f"... {len(jobs) - 20} more")
        return 0

    for job in jobs:
        push_message(args, to_message(job))

    print(f"Requeued {len(jobs)} job message(s) to Redis list '{args.queue}'.")
    return 0


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Requeue stranded local grading jobs.")
    parser.add_argument("--postgres-container", default=DEFAULT_POSTGRES_CONTAINER)
    parser.add_argument("--redis-container", default=DEFAULT_REDIS_CONTAINER)
    parser.add_argument("--database", default=DEFAULT_DATABASE)
    parser.add_argument("--postgres-user", default=DEFAULT_POSTGRES_USER)
    parser.add_argument("--queue", default=DEFAULT_QUEUE)
    parser.add_argument("--limit", type=int)
    parser.add_argument("--dry-run", action="store_true")
    return parser.parse_args()


def fetch_stranded_jobs(args: argparse.Namespace) -> list[dict[str, Any]]:
    limit_clause = f" limit {args.limit}" if args.limit and args.limit > 0 else ""
    query = f"""
        select coalesce(json_agg(row_to_json(stranded)), '[]'::json)
        from (
            select
                id as "jobId",
                queue_message_id as "queueMessageId",
                submission_path as "submissionKey",
                grader_type as "graderType",
                institution_id as "institutionId",
                submitted_by as "requestedBy",
                coalesce(attempt_count, 0) + 1 as attempt
            from jobs
            where status = 'QUEUED'
              and coalesce(attempt_count, 0) = 0
              and worker_id is null
              and queue_message_id is not null
            order by id
            {limit_clause}
        ) stranded;
    """
    result = run_cmd([
        "docker",
        "exec",
        args.postgres_container,
        "psql",
        "-U",
        args.postgres_user,
        "-d",
        args.database,
        "-t",
        "-A",
        "-c",
        query,
    ])
    return json.loads(result.stdout.strip() or "[]")


def to_message(job: dict[str, Any]) -> dict[str, Any]:
    return {
        "jobId": job["jobId"],
        "queueMessageId": job["queueMessageId"],
        "submissionKey": job["submissionKey"],
        "graderType": job["graderType"],
        "institutionId": job["institutionId"],
        "requestedBy": job["requestedBy"],
        "attempt": job["attempt"],
    }


def push_message(args: argparse.Namespace, message: dict[str, Any]) -> None:
    run_cmd([
        "docker",
        "exec",
        args.redis_container,
        "redis-cli",
        "LPUSH",
        args.queue,
        json.dumps(message, separators=(",", ":")),
    ])


def run_cmd(cmd: list[str]) -> subprocess.CompletedProcess[str]:
    result = subprocess.run(cmd, text=True, capture_output=True, check=False)
    if result.returncode != 0:
        print(result.stderr or result.stdout, file=sys.stderr)
        raise SystemExit(result.returncode)
    return result


if __name__ == "__main__":
    sys.exit(main())
