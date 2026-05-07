#!/usr/bin/env python3
"""
Run local burst and failure scenarios against a running Elastic Autograder API.

The script only uses public HTTP APIs. It does not reset Postgres, purge Redis,
or delete Kubernetes resources.
"""

from __future__ import annotations

import argparse
from collections import Counter
import json
import mimetypes
import random
import sys
import time
import uuid
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
from pathlib import Path
from typing import Any
from urllib import error, request


REPO_ROOT = Path(__file__).resolve().parents[1]
TERMINAL_STATUSES = {"SUCCEEDED", "PARTIAL", "FAILED", "DEAD_LETTERED", "CANCELLED"}


@dataclass(frozen=True)
class ScenarioItem:
    grader: str
    fixture: Path
    expected_statuses: set[str]
    expected_failure_reasons: set[str] | None = None
    weight: int = 1


@dataclass(frozen=True)
class SubmittedJob:
    id: int
    file_name: str
    expected_statuses: set[str]
    expected_failure_reasons: set[str] | None


def main() -> int:
    args = parse_args()
    scenario_items = build_scenario(args)
    submitted = upload_jobs(args, scenario_items)
    final_jobs, health_samples, elapsed = wait_for_terminal_jobs(args, submitted)
    return summarize(args, submitted, final_jobs, health_samples, elapsed)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run local Elastic Autograder burst/failure scenarios.")
    parser.add_argument(
        "scenario",
        nargs="?",
        default="success-burst",
        choices=[
            "success-burst",
            "mixed-burst",
            "mixed-language-burst",
            "performance-timeout",
            "memory-limit",
            "queue-pressure",
        ],
    )
    parser.add_argument("--api-base", default="http://localhost:8080")
    parser.add_argument("--grader", default="fib")
    parser.add_argument("--count", type=int, default=50)
    parser.add_argument("--concurrency", type=int, default=10)
    parser.add_argument("--institution", default="local")
    parser.add_argument("--user", default="burst-tester")
    parser.add_argument("--timeout-seconds", type=int, default=300)
    parser.add_argument("--poll-interval", type=float, default=2.0)
    parser.add_argument("--fixture", type=Path)
    parser.add_argument("--seed", type=int)
    parser.add_argument("--selection-mode", choices=["random", "round-robin"])
    args = parser.parse_args()
    configure_selection(args)
    return args


def configure_selection(args: argparse.Namespace) -> None:
    if args.selection_mode is None:
        args.selection_mode = "random" if args.scenario == "mixed-language-burst" else "round-robin"

    if args.selection_mode == "random" and args.seed is None:
        args.seed = random.SystemRandom().randrange(0, 2**32)


def build_scenario(args: argparse.Namespace) -> list[ScenarioItem]:
    if args.count <= 0:
        raise SystemExit("--count must be greater than 0")
    if args.concurrency <= 0:
        raise SystemExit("--concurrency must be greater than 0")

    if args.fixture:
        fixture_path = resolve_path(args.fixture)
        return [ScenarioItem(args.grader, fixture_path, {"SUCCEEDED", "PARTIAL", "FAILED", "DEAD_LETTERED"})]

    if args.scenario == "success-burst":
        return [ScenarioItem("fib", fixture("fib", "fibpass1.py"), {"SUCCEEDED"})]

    if args.scenario == "queue-pressure":
        return [ScenarioItem("fib", fixture("fib", "fibpass1.py"), {"SUCCEEDED"})]

    if args.scenario == "mixed-burst":
        return [
            ScenarioItem("fib", fixture("fib", "fibpass1.py"), {"SUCCEEDED"}),
            ScenarioItem("fib", fixture("fib", "fibfail1.py"), {"FAILED"}),
            ScenarioItem("fib", fixture("fib", "fibpartial.py"), {"PARTIAL"}),
            ScenarioItem("fib", fixture("emptyfile.py"), {"FAILED"}),
        ]

    if args.scenario == "mixed-language-burst":
        return [
            ScenarioItem("fib", fixture("fib", "fibpass1.py"), {"SUCCEEDED"}, weight=12),
            ScenarioItem("fib", fixture("fib", "fibfail1.py"), {"FAILED"}, weight=5),
            ScenarioItem("fib", fixture("fib", "fibpartial.py"), {"PARTIAL"}, weight=5),
            ScenarioItem("fib", fixture("emptyfile.py"), {"FAILED"}, {"INVALID_UPLOAD"}, weight=3),
            ScenarioItem("fib-java", fixture("fib-java", "FibPass.java"), {"SUCCEEDED"}, weight=12),
            ScenarioItem("fib-java", fixture("fib-java", "FibWrong.java"), {"FAILED"}, {"WRONG_ANSWER"}, weight=5),
            ScenarioItem("fib-java", fixture("fib-java", "FibCompileError.java"), {"FAILED"}, {"INVALID_UPLOAD"}, weight=4),
            ScenarioItem("fib-java", fixture("fib-java", "FibRuntimeError.java"), {"FAILED"}, {"WRONG_ANSWER"}, weight=4),
            ScenarioItem("fib-cpp", fixture("fib-cpp", "fib_pass.cpp"), {"SUCCEEDED"}, weight=12),
            ScenarioItem("fib-cpp", fixture("fib-cpp", "fib_wrong.cpp"), {"FAILED"}, {"WRONG_ANSWER"}, weight=5),
            ScenarioItem("fib-cpp", fixture("fib-cpp", "fib_compile_error.cpp"), {"FAILED"}, {"INVALID_UPLOAD"}, weight=4),
            ScenarioItem("fib-cpp", fixture("fib-cpp", "fib_runtime_error.cpp"), {"FAILED"}, {"WRONG_ANSWER"}, weight=4),
            ScenarioItem(
                "fib-performance",
                fixture("fib-performance", "fibslow_recursive.py"),
                {"FAILED", "DEAD_LETTERED"},
                {"TIMEOUT", "KUBERNETES_ERROR", "UNKNOWN"},
                weight=1,
            ),
            ScenarioItem(
                "memory-demo",
                fixture("memory-demo", "memoryoom.py"),
                {"FAILED", "DEAD_LETTERED"},
                {"RESOURCE_LIMIT", "KUBERNETES_ERROR", "UNKNOWN"},
                weight=1,
            ),
        ]

    if args.scenario == "performance-timeout":
        return [
            ScenarioItem(
                "fib-performance",
                fixture("fib-performance", "fibslow_recursive.py"),
                {"FAILED", "DEAD_LETTERED"},
                {"TIMEOUT", "KUBERNETES_ERROR", "UNKNOWN"},
            )
        ]

    if args.scenario == "memory-limit":
        return [
            ScenarioItem(
                "memory-demo",
                fixture("memory-demo", "memoryoom.py"),
                {"FAILED", "DEAD_LETTERED"},
                {"RESOURCE_LIMIT", "KUBERNETES_ERROR", "UNKNOWN"},
            )
        ]

    raise SystemExit(f"Unsupported scenario: {args.scenario}")


def fixture(*parts: str) -> Path:
    return resolve_path(REPO_ROOT / "mocksubmission" / Path(*parts))


def resolve_path(path: Path) -> Path:
    resolved = path if path.is_absolute() else REPO_ROOT / path
    if not resolved.exists():
        raise SystemExit(f"Fixture does not exist: {resolved}")
    return resolved


def upload_jobs(args: argparse.Namespace, scenario_items: list[ScenarioItem]) -> list[SubmittedJob]:
    total = args.count
    selected_items = select_upload_items(args, scenario_items)
    print(f"Uploading {total} jobs to {args.api_base} with concurrency={args.concurrency}")
    print_selection_summary(args, selected_items)
    submitted: list[SubmittedJob] = []

    with ThreadPoolExecutor(max_workers=args.concurrency) as executor:
        futures = []
        for index, item in enumerate(selected_items):
            futures.append(executor.submit(upload_one, args, item, index + 1))

        for future in as_completed(futures):
            submitted.extend(future.result())
            print(f"\rUploaded jobs: {len(submitted)}/{total}", end="", flush=True)

    print()
    return sorted(submitted, key=lambda job: job.id)


def select_upload_items(args: argparse.Namespace, scenario_items: list[ScenarioItem]) -> list[ScenarioItem]:
    if args.selection_mode == "random":
        rng = random.Random(args.seed)
        return rng.choices(
            scenario_items,
            weights=[item.weight for item in scenario_items],
            k=args.count,
        )

    return [scenario_items[index % len(scenario_items)] for index in range(args.count)]


def print_selection_summary(args: argparse.Namespace, selected_items: list[ScenarioItem]) -> None:
    print(f"Selection mode: {args.selection_mode}")
    if args.selection_mode == "random":
        print(f"Selection seed: {args.seed}")

    print("Selected workload:")
    for label, count in sorted(Counter(item_label(item) for item in selected_items).items()):
        print(f"  {label}: {count}")


def upload_one(args: argparse.Namespace, item: ScenarioItem, upload_number: int) -> list[SubmittedJob]:
    boundary = f"----elastic-autograder-{uuid.uuid4().hex}"
    body = multipart_body(boundary, item.fixture, item.grader)
    headers = {
        "Content-Type": f"multipart/form-data; boundary={boundary}",
        "X-Mock-Institution": args.institution,
        "X-Mock-User": args.user,
    }
    payload = http_json(f"{args.api_base}/api/jobs/upload", method="POST", body=body, headers=headers)
    jobs = payload.get("jobs")
    if not isinstance(jobs, list) or not jobs:
        raise RuntimeError(f"Upload {upload_number} returned no jobs: {payload}")

    submitted: list[SubmittedJob] = []
    for job in jobs:
        submitted.append(
            SubmittedJob(
                id=int(job["id"]),
                file_name=str(job.get("fileName") or item.fixture.name),
                expected_statuses=item.expected_statuses,
                expected_failure_reasons=item.expected_failure_reasons,
            )
        )
    return submitted


def multipart_body(boundary: str, file_path: Path, grader: str) -> bytes:
    content_type = mimetypes.guess_type(file_path.name)[0] or "application/octet-stream"
    file_bytes = file_path.read_bytes()
    parts = [
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
    ]
    return b"".join(parts)


def wait_for_terminal_jobs(
    args: argparse.Namespace,
    submitted: list[SubmittedJob],
) -> tuple[dict[int, dict[str, Any]], list[dict[str, Any]], float]:
    target_ids = {job.id for job in submitted}
    deadline = time.monotonic() + args.timeout_seconds
    start = time.monotonic()
    health_samples: list[dict[str, Any]] = []
    final_jobs: dict[int, dict[str, Any]] = {}

    print(f"Polling {len(target_ids)} jobs for up to {args.timeout_seconds}s")
    while time.monotonic() < deadline:
        health = fetch_health(args)
        if health:
            health_samples.append(health)

        recent_jobs = fetch_recent_jobs(args)
        for job in recent_jobs:
            job_id = job.get("id")
            if job_id in target_ids:
                final_jobs[int(job_id)] = job

        terminal_count = sum(
            1 for job_id in target_ids
            if final_jobs.get(job_id, {}).get("status") in TERMINAL_STATUSES
        )
        latest_counts = health.get("jobCounts", {}) if health else {}
        queue_depth = health.get("queueDepth", "?") if health else "?"
        print(
            "\r"
            f"Terminal {terminal_count}/{len(target_ids)} "
            f"queueDepth={queue_depth} "
            f"queued={latest_counts.get('QUEUED', '?')} "
            f"running={latest_counts.get('RUNNING', '?')}",
            end="",
            flush=True,
        )

        if terminal_count == len(target_ids):
            print()
            return final_jobs, health_samples, time.monotonic() - start

        time.sleep(args.poll_interval)

    print()
    return final_jobs, health_samples, time.monotonic() - start


def fetch_health(args: argparse.Namespace) -> dict[str, Any]:
    try:
        return http_json(f"{args.api_base}/api/system/queue-health", headers=identity_headers(args))
    except Exception as exc:
        print(f"\nWarning: queue-health fetch failed: {exc}")
        return {}


def fetch_recent_jobs(args: argparse.Namespace) -> list[dict[str, Any]]:
    payload = http_json(f"{args.api_base}/api/jobs/recent", headers=identity_headers(args))
    if not isinstance(payload, list):
        raise RuntimeError(f"Expected recent jobs list, got: {payload}")
    return payload


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


def summarize(
    args: argparse.Namespace,
    submitted: list[SubmittedJob],
    final_jobs: dict[int, dict[str, Any]],
    health_samples: list[dict[str, Any]],
    elapsed: float,
) -> int:
    submitted_by_id = {job.id: job for job in submitted}
    status_counts: dict[str, int] = {}
    reason_counts: dict[str, int] = {}
    errors: list[str] = []

    for submitted_job in submitted:
        job = final_jobs.get(submitted_job.id)
        if not job:
            errors.append(f"Job {submitted_job.id} did not appear in recent jobs.")
            continue

        status = job.get("status")
        reason = job.get("failureReason")
        status_key = str(status)
        status_counts[status_key] = status_counts.get(status_key, 0) + 1
        if reason:
            reason_key = str(reason)
            reason_counts[reason_key] = reason_counts.get(reason_key, 0) + 1

        if status not in TERMINAL_STATUSES:
            errors.append(f"Job {submitted_job.id} did not reach a terminal state: {status}")
        elif status not in submitted_job.expected_statuses:
            errors.append(
                f"Job {submitted_job.id} expected {sorted(submitted_job.expected_statuses)} "
                f"but finished as {status}"
            )

        if submitted_job.expected_failure_reasons and reason not in submitted_job.expected_failure_reasons:
            errors.append(
                f"Job {submitted_job.id} expected failure reason "
                f"{sorted(submitted_job.expected_failure_reasons)} but got {reason}"
            )

    max_queue_depth = max((int(sample.get("queueDepth") or 0) for sample in health_samples), default=0)
    max_queued = max(
        (int(sample.get("jobCounts", {}).get("QUEUED") or 0) for sample in health_samples),
        default=0,
    )
    max_running = max(
        (int(sample.get("jobCounts", {}).get("RUNNING") or 0) for sample in health_samples),
        default=0,
    )
    max_stale = max((len(sample.get("staleRunningJobs") or []) for sample in health_samples), default=0)
    max_dead_lettered = max(
        (int(sample.get("jobCounts", {}).get("DEAD_LETTERED") or 0) for sample in health_samples),
        default=0,
    )
    final_health = health_samples[-1] if health_samples else {}
    final_queue_depth = int(final_health.get("queueDepth") or 0)
    final_queued = int(final_health.get("jobCounts", {}).get("QUEUED") or 0)
    observed_worker_ids = sorted(
        {
            str(job.get("workerId"))
            for sample in health_samples
            for job in sample.get("recentRunningJobs", [])
            if job.get("workerId")
        }
    )
    if args.scenario == "queue-pressure" and max_queue_depth == 0 and max_queued <= args.concurrency:
        errors.append("queue-pressure did not observe Redis queue depth or durable queued backlog above worker capacity.")
    if final_queue_depth == 0 and final_queued > 0:
        errors.append(
            "Redis queue drained while durable queued jobs remain; possible dropped worker messages."
        )
    if max_stale > 0:
        errors.append(f"Observed stale running jobs during scenario: max={max_stale}")

    print("\nSummary")
    print(f"  Scenario: {args.scenario}")
    print(f"  Selection mode: {args.selection_mode}")
    if args.selection_mode == "random":
        print(f"  Selection seed: {args.seed}")
    print(f"  Submitted jobs: {len(submitted)}")
    print(f"  Claim: processed a {len(submitted)}-job burst through distributed workers")
    print(f"  Elapsed seconds: {elapsed:.1f}")
    print(f"  Throughput: {len(submitted) / elapsed:.2f} jobs/s" if elapsed > 0 else "  Throughput: n/a")
    print(f"  Status counts: {format_counts(status_counts)}")
    print(f"  Failure reasons: {format_counts(reason_counts)}")
    print(f"  Max queue depth: {max_queue_depth}")
    print(f"  Max queued jobs: {max_queued}")
    print(f"  Max running jobs: {max_running}")
    print(f"  Max stale running jobs: {max_stale}")
    print(f"  Max global dead-lettered jobs: {max_dead_lettered}")
    print(f"  Observed running worker IDs: {', '.join(observed_worker_ids) if observed_worker_ids else 'none'}")

    if errors:
        print("\nFailures")
        for message in errors[:20]:
            job_id = first_int(message)
            suffix = ""
            if job_id in submitted_by_id:
                suffix = f" ({submitted_by_id[job_id].file_name})"
            print(f"  - {message}{suffix}")
        if len(errors) > 20:
            print(f"  - ... {len(errors) - 20} more")
        return 1

    return 0


def format_counts(counts: dict[str, int]) -> str:
    if not counts:
        return "none"
    return ", ".join(f"{key}={counts[key]}" for key in sorted(counts))


def item_label(item: ScenarioItem) -> str:
    try:
        fixture_path = item.fixture.relative_to(REPO_ROOT)
    except ValueError:
        fixture_path = item.fixture
    return f"{item.grader}:{fixture_path}"


def first_int(text: str) -> int | None:
    for token in text.split():
        if token.isdigit():
            return int(token)
    return None


if __name__ == "__main__":
    sys.exit(main())
