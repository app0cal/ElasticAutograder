#!/usr/bin/env python3
"""
Read-only local environment checks for Elastic Autograder.

This script diagnoses the common setup pieces without creating jobs, changing
Docker state, or writing to Kubernetes.
"""

from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any
from urllib import error, request


REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_API_BASE = "http://localhost:8080"
DEFAULT_CLUSTER_NAME = "elastic-autograder"
DEFAULT_NAMESPACE = "elastic-grading"
DEFAULT_GRADERS_CONFIG = REPO_ROOT / "config" / "graders.json"
RUNTIME_BASE_IMAGES = [
    "ea-grader-runtime-python-base:v1",
    "ea-grader-runtime-java-base:v1",
    "ea-grader-runtime-cpp-base:v1",
]


@dataclass
class CheckResult:
    level: str
    name: str
    detail: str
    hint: str = ""


def main() -> int:
    args = parse_args()
    results: list[CheckResult] = []

    check_tools(results)
    check_docker_services(results)
    check_kind(results, args.cluster_name, args.namespace)
    check_images(results, args.graders_config)
    check_api(results, args.api_base)

    for result in results:
        print(f"[{result.level}] {result.name}: {result.detail}")
        if result.hint:
            print(f"       Hint: {result.hint}")

    counts = {level: sum(1 for result in results if result.level == level) for level in ("OK", "WARN", "FAIL")}
    print(f"\nSummary: OK={counts['OK']} WARN={counts['WARN']} FAIL={counts['FAIL']}")
    return 1 if counts["FAIL"] else 0


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Diagnose a local Elastic Autograder setup.")
    parser.add_argument("--api-base", default=DEFAULT_API_BASE)
    parser.add_argument("--cluster-name", default=DEFAULT_CLUSTER_NAME)
    parser.add_argument("--namespace", default=DEFAULT_NAMESPACE)
    parser.add_argument("--graders-config", type=Path, default=DEFAULT_GRADERS_CONFIG)
    return parser.parse_args()


def check_tools(results: list[CheckResult]) -> None:
    for tool in ["docker", "kind", "kubectl", "python", "java", "node", "npm"]:
        if shutil.which(tool):
            results.append(ok(f"tool:{tool}", "found on PATH"))
        else:
            results.append(fail(f"tool:{tool}", "not found on PATH", install_hint(tool)))

    docker_compose = run(["docker", "compose", "version"])
    if docker_compose.returncode == 0:
        results.append(ok("tool:docker compose", first_line(docker_compose.stdout)))
    else:
        results.append(fail("tool:docker compose", "not available", "Install Docker Compose v2 or update Docker Desktop."))


def check_docker_services(results: list[CheckResult]) -> None:
    docker = run(["docker", "info"])
    if docker.returncode != 0:
        results.append(fail("docker daemon", "not reachable", "Start Docker, then run `docker compose up -d`."))
        return
    results.append(ok("docker daemon", "reachable"))

    for container, service in [("ea-postgres", "Postgres"), ("ea-redis", "Redis")]:
        inspect = run([
            "docker",
            "inspect",
            container,
            "--format",
            "{{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{end}}",
        ])
        if inspect.returncode != 0:
            results.append(warn(
                f"compose:{service}",
                f"container `{container}` not found",
                "Run `docker compose up -d` from the project root.",
            ))
            continue

        state = inspect.stdout.strip()
        if "running" in state and ("healthy" in state or service == "Redis"):
            results.append(ok(f"compose:{service}", state))
        elif "running" in state:
            results.append(warn(f"compose:{service}", state, "Wait for health checks or inspect logs with `docker compose logs`."))
        else:
            results.append(fail(f"compose:{service}", state or "not running", "Run `docker compose up -d`."))


def check_kind(results: list[CheckResult], cluster_name: str, namespace: str) -> None:
    clusters = run(["kind", "get", "clusters"])
    if clusters.returncode != 0:
        results.append(warn("kind cluster", "could not list clusters", "Run `python scripts/setup-graders.py`."))
        return

    cluster_names = {line.strip() for line in clusters.stdout.splitlines() if line.strip()}
    if cluster_name not in cluster_names:
        results.append(warn("kind cluster", f"`{cluster_name}` not found", "Run `python scripts/setup-graders.py`."))
        return
    results.append(ok("kind cluster", f"`{cluster_name}` exists"))

    context = f"kind-{cluster_name}"
    cluster_info = run(["kubectl", "cluster-info", "--context", context])
    if cluster_info.returncode == 0:
        results.append(ok("kubectl context", context))
    else:
        results.append(fail("kubectl context", f"cannot reach `{context}`", "Check `kubectl config get-contexts`."))

    ns = run(["kubectl", "get", "namespace", namespace, "--context", context])
    if ns.returncode == 0:
        results.append(ok("kubernetes namespace", namespace))
    else:
        results.append(warn("kubernetes namespace", f"`{namespace}` not found", "Run `python scripts/setup-graders.py`."))

    auth = run(["kubectl", "auth", "can-i", "create", "jobs", "-n", namespace, "--context", context])
    if auth.returncode == 0 and auth.stdout.strip().lower() == "yes":
        results.append(ok("kubernetes RBAC", "current context can create jobs"))
    else:
        results.append(warn("kubernetes RBAC", "job creation permission not confirmed", "Run `kubectl apply -f k8s/grading-namespace-rbac.yaml`."))


def check_images(results: list[CheckResult], graders_config: Path) -> None:
    docker = run(["docker", "info"])
    if docker.returncode != 0:
        results.append(warn("grader images", "skipped because Docker is not reachable", "Start Docker, then run `python scripts/doctor.py` again."))
        return

    image_names = list(RUNTIME_BASE_IMAGES)
    try:
        data = json.loads(graders_config.read_text(encoding="utf-8"))
        image_names.extend(
            grader["imageName"]
            for grader in data.get("graders", [])
            if isinstance(grader, dict) and isinstance(grader.get("imageName"), str)
        )
    except Exception as exc:
        results.append(fail("grader config", f"could not read {graders_config}: {exc}", "Check `config/graders.json`."))
        return

    missing: list[str] = []
    for image_name in image_names:
        inspect = run(["docker", "image", "inspect", image_name])
        if inspect.returncode != 0:
            missing.append(image_name)

    if not missing:
        results.append(ok("grader images", f"{len(image_names)} expected local image(s) found"))
    else:
        results.append(warn(
            "grader images",
            f"missing {len(missing)} image(s): {', '.join(missing[:5])}{'...' if len(missing) > 5 else ''}",
            "Run `python scripts/setup-graders.py` or `python scripts/setup-graders.py --grader <key>`.",
        ))


def check_api(results: list[CheckResult], api_base: str) -> None:
    graders = http_json(f"{api_base}/api/graders")
    if graders.ok:
        count = len(graders.value) if isinstance(graders.value, list) else "unknown"
        results.append(ok("api:/api/graders", f"reachable; graders={count}"))
    else:
        results.append(warn("api:/api/graders", graders.detail, "Start the backend with Gradle or `docker compose --profile app up -d --build`."))

    health = http_json(f"{api_base}/api/system/queue-health")
    if not health.ok:
        results.append(warn("api:/api/system/queue-health", health.detail, "Start the backend API and check port 8080."))
        return

    value = health.value if isinstance(health.value, dict) else {}
    redis_connected = value.get("redisConnected")
    worker_enabled = value.get("workerEnabled")
    queue_depth = value.get("queueDepth")
    counts = value.get("jobCounts") if isinstance(value.get("jobCounts"), dict) else {}
    detail = (
        f"redisConnected={redis_connected} workerEnabled={worker_enabled} "
        f"queueDepth={queue_depth} queued={counts.get('QUEUED')} running={counts.get('RUNNING')}"
    )
    if redis_connected is False:
        results.append(fail("api:queue health", detail, "Start Redis with `docker compose up -d redis`."))
    elif worker_enabled is False:
        results.append(warn(
            "api:queue health",
            detail,
            "API-only mode is active; jobs need a separate worker process or `docker compose --profile app up -d --build`.",
        ))
    else:
        results.append(ok("api:queue health", detail))


@dataclass(frozen=True)
class HttpResult:
    ok: bool
    value: Any = None
    detail: str = ""


def http_json(url: str) -> HttpResult:
    try:
        with request.urlopen(url, timeout=5) as response:
            raw = response.read().decode("utf-8")
            return HttpResult(True, json.loads(raw) if raw else {})
    except error.HTTPError as exc:
        raw = exc.read().decode("utf-8", errors="replace")
        return HttpResult(False, detail=f"HTTP {exc.code}: {raw[:200]}")
    except error.URLError as exc:
        return HttpResult(False, detail=str(exc.reason))
    except TimeoutError:
        return HttpResult(False, detail="request timed out")
    except json.JSONDecodeError as exc:
        return HttpResult(False, detail=f"invalid JSON: {exc}")


def run(cmd: list[str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(cmd, text=True, capture_output=True, check=False)


def first_line(value: str) -> str:
    return next((line.strip() for line in value.splitlines() if line.strip()), "ok")


def install_hint(tool: str) -> str:
    hints = {
        "docker": "Install Docker Desktop or Docker Engine.",
        "kind": "Install kind from https://kind.sigs.k8s.io/.",
        "kubectl": "Install kubectl from Kubernetes tools documentation.",
        "python": "Install Python 3 and ensure `python` is on PATH.",
        "java": "Install Java 21.",
        "node": "Install Node.js LTS.",
        "npm": "Install npm with Node.js.",
    }
    return hints.get(tool, "Install the missing tool.")


def ok(name: str, detail: str) -> CheckResult:
    return CheckResult("OK", name, detail)


def warn(name: str, detail: str, hint: str = "") -> CheckResult:
    return CheckResult("WARN", name, detail, hint)


def fail(name: str, detail: str, hint: str = "") -> CheckResult:
    return CheckResult("FAIL", name, detail, hint)


if __name__ == "__main__":
    sys.exit(main())
