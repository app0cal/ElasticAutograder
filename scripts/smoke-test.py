#!/usr/bin/env python3
"""
Submit one local grading job and verify it reaches the expected terminal status.

This script is intentionally mutating: it creates a real local job through the
public upload API so developers can validate the full API, queue, worker, and
Kubernetes grader path. It supports success and intentional-failure scenarios.
"""

from __future__ import annotations

import argparse
import json
import mimetypes
import sys
import time
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import Any
from urllib import error, request


REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_API_BASE = "http://localhost:8080"
DEFAULT_FIXTURE = REPO_ROOT / "mocksubmission" / "fib" / "fibpass1.py"
TERMINAL_STATUSES = {"SUCCEEDED", "PARTIAL", "FAILED", "DEAD_LETTERED", "CANCELLED"}
SCENARIOS = {
    "success": {
        "grader": "fib",
        "fixture": DEFAULT_FIXTURE,
        "expected_status": "SUCCEEDED",
    },
    "project-java-pass": {
        "grader": "fib-java-project",
        "fixture": REPO_ROOT / "mocksubmission" / "fib-java-project" / "fib_project_pass.zip",
        "expected_status": "SUCCEEDED",
    },
    "project-java-build-failure": {
        "grader": "fib-java-project",
        "fixture": REPO_ROOT / "mocksubmission" / "fib-java-project" / "fib_project_compile_error.zip",
        "expected_status": "FAILED",
    },
    "project-java-wrong-answer": {
        "grader": "fib-java-project",
        "fixture": REPO_ROOT / "mocksubmission" / "fib-java-project" / "fib_project_wrong.zip",
        "expected_status": "FAILED",
    },
    "project-java-runtime-error": {
        "grader": "fib-java-project",
        "fixture": REPO_ROOT / "mocksubmission" / "fib-java-project" / "fib_project_runtime_error.zip",
        "expected_status": "FAILED",
    },
}


@dataclass(frozen=True)
class SmokeTarget:
    scenario: str
    grader: str
    fixture: Path
    expected_status: str


def main() -> int:
    args = parse_args()
    target = resolve_target(args)

    print(f"Scenario: {target.scenario}")
    print(f"Expected status: {target.expected_status}")
    print(f"Uploading smoke fixture: {display_path(target.fixture)}")
    job_id = upload_job(args, target.grader, target.fixture)
    print(f"Created job {job_id}; polling for up to {args.timeout_seconds}s")

    job, health = wait_for_terminal(args, job_id)
    status = job.get("status") if job else None
    if status == target.expected_status:
        print(f"Smoke test passed: job {job_id} finished {status}")
        return 0

    print(f"Smoke test failed: job {job_id} finished {status or 'UNKNOWN'} but expected {target.expected_status}")
    if job:
        print(f"Failure reason: {job.get('failureReason') or 'none'}")
        print(f"Message: {job.get('message') or job.get('errorMessage') or 'none'}")
    print_diagnosis(health)
    return 1


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run one end-to-end Elastic Autograder smoke test.")
    parser.add_argument(
        "--scenario",
        default="success",
        choices=sorted(SCENARIOS.keys()),
        help="Smoke scenario to run.",
    )
    parser.add_argument("--api-base", default=DEFAULT_API_BASE)
    parser.add_argument("--grader")
    parser.add_argument("--fixture", type=Path)
    parser.add_argument(
        "--expect-status",
        choices=sorted(TERMINAL_STATUSES),
        help="Expected terminal job status for the selected smoke scenario.",
    )
    parser.add_argument("--institution", default="local")
    parser.add_argument("--user", default="smoke-tester")
    parser.add_argument("--timeout-seconds", type=int, default=120)
    parser.add_argument("--poll-interval", type=float, default=2.0)
    return parser.parse_args()


def resolve_target(args: argparse.Namespace) -> SmokeTarget:
    scenario = resolve_scenario(args.scenario)
    grader = args.grader or scenario.grader
    fixture = resolve_fixture(args.fixture or scenario.fixture)
    expected_status = args.expect_status or scenario.expected_status
    return SmokeTarget(args.scenario, grader, fixture, expected_status)


def resolve_scenario(name: str) -> SmokeTarget:
    scenario = SCENARIOS.get(name)
    if scenario is None:
        raise SystemExit(f"Unsupported scenario: {name}")
    return SmokeTarget(name, scenario["grader"], scenario["fixture"], scenario["expected_status"])


def resolve_fixture(path: Path) -> Path:
    resolved = path if path.is_absolute() else REPO_ROOT / path
    if not resolved.exists():
        raise SystemExit(f"Fixture does not exist: {resolved}")
    return resolved


def display_path(path: Path) -> str:
    try:
        return str(path.relative_to(REPO_ROOT))
    except ValueError:
        return str(path)


def upload_job(args: argparse.Namespace, grader: str, fixture: Path) -> int:
    boundary = f"----elastic-autograder-smoke-{uuid.uuid4().hex}"
    body = multipart_body(boundary, fixture, grader)
    headers = {
        "Content-Type": f"multipart/form-data; boundary={boundary}",
        **identity_headers(args),
    }
    payload = http_json(f"{args.api_base}/api/jobs/upload", method="POST", body=body, headers=headers)
    jobs = payload.get("jobs") if isinstance(payload, dict) else None
    if not isinstance(jobs, list) or not jobs:
        raise RuntimeError(f"Upload returned no jobs: {payload}")
    return int(jobs[0]["id"])


def multipart_body(boundary: str, file_path: Path, grader: str) -> bytes:
    content_type = mimetypes.guess_type(file_path.name)[0] or "application/octet-stream"
    file_bytes = file_path.read_bytes()
    return b"".join([
        (
            f"--{boundary}\r\n"
            'Content-Disposition: form-data; name="graderType"\r\n\r\n'
            f"{grader}\r\n"
        ).encode(),
        (
            f"--{boundary}\r\n"
            f'Content-Disposition: form-data; name="file"; filename="{file_path.name}"\r\n'
            f"Content-Type: {content_type}\r\n\r\n"
        ).encode(),
        file_bytes,
        f"\r\n--{boundary}--\r\n".encode(),
    ])


def wait_for_terminal(args: argparse.Namespace, job_id: int) -> tuple[dict[str, Any], dict[str, Any]]:
    deadline = time.monotonic() + args.timeout_seconds
    last_job: dict[str, Any] = {}
    last_health: dict[str, Any] = {}

    while time.monotonic() < deadline:
        last_health = fetch_health(args)
        last_job = fetch_job(args, job_id)
        status = last_job.get("status")
        queue_depth = last_health.get("queueDepth", "?") if last_health else "?"
        counts = last_health.get("jobCounts", {}) if isinstance(last_health.get("jobCounts"), dict) else {}
        print(
            "\r"
            f"status={status} queueDepth={queue_depth} "
            f"queued={counts.get('QUEUED', '?')} running={counts.get('RUNNING', '?')}",
            end="",
            flush=True,
        )
        if status in TERMINAL_STATUSES:
            print()
            return last_job, last_health
        time.sleep(args.poll_interval)

    print()
    return last_job, last_health


def fetch_job(args: argparse.Namespace, job_id: int) -> dict[str, Any]:
    payload = http_json(f"{args.api_base}/api/jobs/{job_id}", headers=identity_headers(args))
    if not isinstance(payload, dict):
        raise RuntimeError(f"Expected job object for {job_id}, got: {payload}")
    return payload


def fetch_health(args: argparse.Namespace) -> dict[str, Any]:
    try:
        payload = http_json(f"{args.api_base}/api/system/queue-health", headers=identity_headers(args))
        return payload if isinstance(payload, dict) else {}
    except Exception:
        return {}


def print_diagnosis(health: dict[str, Any]) -> None:
    if not health:
        print("Diagnosis: backend queue-health was unavailable. Check that the backend API is running on port 8080.")
        return

    counts = health.get("jobCounts") if isinstance(health.get("jobCounts"), dict) else {}
    queued = int(counts.get("QUEUED") or 0)
    running = int(counts.get("RUNNING") or 0)
    redis_connected = health.get("redisConnected")
    worker_enabled = health.get("workerEnabled")

    print(
        "Queue health: "
        f"redisConnected={redis_connected} workerEnabled={worker_enabled} "
        f"queueDepth={health.get('queueDepth')} queued={queued} running={running}"
    )

    if redis_connected is False:
        print("Diagnosis: Redis is disconnected. Run `docker compose up -d redis` and restart the backend.")
    elif worker_enabled is False:
        print("Diagnosis: API-only backend mode is active. Start a worker with `docker compose --profile app up -d --build` or run the backend without `--grading.worker.enabled=false`.")
    elif queued > 0 and running == 0:
        print("Diagnosis: jobs are queued but no worker appears to be running. Check backend worker logs and Redis queue health.")
    elif running > 0:
        print("Diagnosis: a grader job was still running or timed out. Check `kubectl get jobs,pods -n elastic-grading` and backend logs.")
    else:
        print("Diagnosis: inspect backend logs, grader images, and kind cluster state.")


def identity_headers(args: argparse.Namespace) -> dict[str, str]:
    return {
        "X-Mock-Institution": args.institution,
        "X-Mock-User": args.user,
    }


def http_json(
    url: str,
    method: str = "GET",
    body: bytes | None = None,
    headers: dict[str, str] | None = None,
) -> Any:
    req = request.Request(url, data=body, method=method, headers=headers or {})
    try:
        with request.urlopen(req, timeout=30) as response:
            raw = response.read().decode("utf-8")
            return json.loads(raw) if raw else {}
    except error.HTTPError as exc:
        raw = exc.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"{method} {url} failed with HTTP {exc.code}: {raw}") from exc
    except error.URLError as exc:
        raise RuntimeError(f"{method} {url} failed: {exc.reason}") from exc


if __name__ == "__main__":
    sys.exit(main())
