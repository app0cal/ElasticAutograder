#!/usr/bin/env python3
"""
setup-graders.py

Cross-platform setup/build/load script for Elastic Autograder graders.

What it does:
1. Verifies required tools exist: docker, kind, kubectl
2. Creates the kind cluster if it does not already exist
3. Reads grader definitions from config/graders.json
4. Builds each grader image using the shared runtime Dockerfile
5. Loads each grader image into the kind cluster
6. Verifies kubectl can reach the cluster

Assumptions:
- This script lives in: elastic/scripts/setup-graders.py
- graders.json lives in: elastic/config/graders.json
- All graders share the same build context:
    graders
- All graders share the same Dockerfile:
    graders/runtime/Dockerfile
- Each grader config entry includes at least:
    key, imageName, graderFolder
"""

from __future__ import annotations

import argparse
import hashlib
import os
import json
import shutil
import subprocess
import sys
import time
from concurrent.futures import FIRST_COMPLETED, ThreadPoolExecutor, wait
from dataclasses import dataclass
from pathlib import Path
from typing import Any

CLUSTER_NAME = "elastic-autograder"
SCRIPT_DIR = Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parent

KIND_CONFIG = REPO_ROOT / "k8s" / "kind-config.yaml"
DEFAULT_GRADERS_CONFIG = REPO_ROOT / "config" / "graders.json"
DEFAULT_GRADERS_ROOT = REPO_ROOT / "graders"
SOURCE_HASH_LABEL = "org.elastic-autograder.source-hash"
SOURCE_ROLE_LABEL = "org.elastic-autograder.source-role"


@dataclass(frozen=True)
class RuntimeBase:
    language: str
    image_name: str
    runtime_packages: str


RUNTIME_BASES = {
    "python": RuntimeBase("python", "ea-grader-runtime-python-base:v1", ""),
    "java": RuntimeBase("java", "ea-grader-runtime-java-base:v1", "default-jdk-headless"),
    "cpp": RuntimeBase("cpp", "ea-grader-runtime-cpp-base:v1", "g++"),
}


def log(msg: str) -> None:
    print(msg, flush=True)


def fail(msg: str, code: int = 1) -> None:
    print(f"ERROR: {msg}", file=sys.stderr, flush=True)
    raise SystemExit(code)


def run_cmd(cmd: list[str], cwd: Path | None = None) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        cmd,
        cwd=str(cwd) if cwd else None,
        text=True,
        capture_output=True,
        check=False,
    )


def run_or_fail(cmd: list[str], cwd: Path | None = None, context: str = "") -> None:
    result = run_cmd(cmd, cwd)
    if result.returncode != 0:
        details = f"\nSTDOUT:\n{result.stdout}\nSTDERR:\n{result.stderr}".rstrip()
        fail(f"{context or 'Command failed'}\nCommand: {' '.join(cmd)}{details}")


def hash_file(hasher: Any, path: Path, root: Path) -> None:
    rel_path = path.relative_to(root).as_posix()
    hasher.update(rel_path.encode("utf-8"))
    hasher.update(b"\0")
    hasher.update(path.read_bytes())
    hasher.update(b"\0")


def hash_paths(root: Path, paths: list[Path], extra: list[str] | None = None) -> str:
    hasher = hashlib.sha256()
    for value in extra or []:
        hasher.update(value.encode("utf-8"))
        hasher.update(b"\0")

    for path in sorted(paths, key=lambda item: item.relative_to(root).as_posix()):
        if path.is_file():
            hash_file(hasher, path, root)

    return hasher.hexdigest()


def files_under(path: Path) -> list[Path]:
    return [candidate for candidate in path.rglob("*") if candidate.is_file()]


def docker_image_id(image_name: str) -> str | None:
    result = run_cmd(["docker", "image", "inspect", image_name, "--format", "{{.Id}}"])
    if result.returncode != 0:
        return None
    image_id = result.stdout.strip()
    return image_id or None


def docker_image_label(image_name: str, label: str) -> str | None:
    template = f"{{{{ index .Config.Labels {json.dumps(label)} }}}}"
    result = run_cmd(["docker", "image", "inspect", image_name, "--format", template])
    if result.returncode != 0:
        return None
    value = result.stdout.strip()
    if not value or value == "<no value>":
        return None
    return value


def docker_image_has_source_hash(image_name: str, source_hash: str) -> bool:
    return docker_image_label(image_name, SOURCE_HASH_LABEL) == source_hash


def require_tool(name: str) -> None:
    if shutil.which(name) is None:
        fail(f"Required tool '{name}' is not installed or not on PATH.")
    log(f"[OK] Found tool: {name}")


def resolve_repo_path(path: str) -> Path:
    candidate = Path(path).expanduser()
    if not candidate.is_absolute():
        candidate = REPO_ROOT / candidate
    return candidate.resolve()


def check_required_paths(
    kind_config: Path,
    graders_config: Path,
    graders_root: Path,
    dockerfile_path: Path,
    base_dockerfile_path: Path,
) -> None:
    if not kind_config.is_file():
        fail(f"kind config file not found: {kind_config}")
    if not graders_config.is_file():
        fail(f"graders config not found: {graders_config}")
    if not graders_root.is_dir():
        fail(f"graders root not found: {graders_root}")
    if not dockerfile_path.is_file():
        fail(f"Dockerfile not found: {dockerfile_path}")
    if not base_dockerfile_path.is_file():
        fail(f"Base Dockerfile not found: {base_dockerfile_path}")


def ensure_kind_cluster(cluster_name: str, kind_config: Path) -> None:
    log(f"Checking for kind cluster '{cluster_name}'...")
    result = run_cmd(["kind", "get", "clusters"])
    if result.returncode != 0:
        fail(f"Unable to list kind clusters.\nSTDERR:\n{result.stderr}".rstrip())

    clusters = {line.strip() for line in result.stdout.splitlines() if line.strip()}
    if cluster_name in clusters:
        log(f"[OK] Cluster '{cluster_name}' already exists.")
        return

    log(f"Cluster '{cluster_name}' not found. Creating from config...")
    run_or_fail(
        ["kind", "create", "cluster", "--name", cluster_name, "--config", str(kind_config)],
        context="Failed to create kind cluster",
    )
    log(f"[OK] Cluster '{cluster_name}' created.")


def load_graders_config(graders_config: Path, graders_root: Path) -> list[dict[str, Any]]:
    try:
        with graders_config.open("r", encoding="utf-8") as f:
            data = json.load(f)
    except json.JSONDecodeError as e:
        fail(f"Invalid JSON in {graders_config}: {e}")

    graders = data.get("graders")
    if not isinstance(graders, list) or not graders:
        fail(f"{graders_config} must contain a non-empty 'graders' array.")

    seen_keys: set[str] = set()
    seen_images: set[str] = set()

    for grader in graders:
        if not isinstance(grader, dict):
            fail("Each grader entry must be a JSON object.")

        key = grader.get("key")
        institution_id = grader.get("institutionId") or "local"
        image_name = grader.get("imageName")
        grader_folder = grader.get("graderFolder") or grader.get("key")

        if not isinstance(key, str) or not key.strip():
            fail("Each grader must have a non-empty string 'key'.")
        if not isinstance(image_name, str) or not image_name.strip():
            fail(f"Grader '{key}' is missing a valid 'imageName'.")
        if not isinstance(grader_folder, str) or not grader_folder.strip():
            fail(f"Grader '{key}' is missing a valid 'graderFolder'.")

        if not isinstance(institution_id, str) or not institution_id.strip():
            fail(f"Grader '{key}' is missing a valid 'institutionId'.")

        scoped_key = f"{institution_id.strip()}:{key}"
        if scoped_key in seen_keys:
            fail(f"Duplicate grader key found in config for institution '{institution_id}': {key}")
        seen_keys.add(scoped_key)

        if image_name in seen_images:
            fail(f"Duplicate imageName found in config: {image_name}")
        seen_images.add(image_name)

        grader_dir = graders_root / grader_folder
        if not grader_dir.is_dir():
            fail(f"Grader '{key}' folder not found: {grader_dir}")

        manifest_file = grader_dir / "manifest.json"
        if not manifest_file.is_file():
            fail(f"Grader '{key}' is missing manifest.json at: {manifest_file}")

    return graders


def filter_graders(graders: list[dict[str, Any]], grader_key: str | None) -> list[dict[str, Any]]:
    if not grader_key:
        return graders

    selected = [grader for grader in graders if grader.get("key") == grader_key]
    if not selected:
        fail(f"No grader with key '{grader_key}' was found in the configured graders.json.")
    return selected


def normalize_language(language: Any) -> str:
    if not isinstance(language, str) or not language.strip():
        return "python"

    normalized = language.strip().lower()
    if normalized in {"c++", "cpp"}:
        return "cpp"
    return normalized


def runtime_base_for_grader(grader: dict[str, Any]) -> RuntimeBase:
    language = normalize_language(grader.get("language"))
    runtime_base = RUNTIME_BASES.get(language)
    if runtime_base is None:
        fail(f"Grader '{grader.get('key')}' has unsupported language '{language}'.")
    return runtime_base


def required_runtime_bases(graders: list[dict[str, Any]]) -> list[RuntimeBase]:
    seen: set[str] = set()
    bases: list[RuntimeBase] = []

    for grader in graders:
        runtime_base = runtime_base_for_grader(grader)
        if runtime_base.language not in seen:
            seen.add(runtime_base.language)
            bases.append(runtime_base)

    return bases


def docker_cache_flags(no_cache: bool, pull: bool) -> list[str]:
    flags: list[str] = []
    if no_cache:
        flags.append("--no-cache")
    if pull:
        flags.append("--pull")
    return flags


def runtime_base_source_hash(runtime_base: RuntimeBase, graders_root: Path, base_dockerfile_path: Path) -> str:
    return hash_paths(
        graders_root,
        [
            base_dockerfile_path,
            graders_root / "runtime" / "main.py",
        ],
        extra=[
            "runtime-base",
            runtime_base.language,
            runtime_base.runtime_packages,
        ],
    )


def grader_source_hash(
    grader: dict[str, Any],
    graders_root: Path,
    dockerfile_path: Path,
) -> str:
    key = grader["key"]
    grader_folder = grader.get("graderFolder") or key
    runtime_base = runtime_base_for_grader(grader)
    base_image_id = docker_image_id(runtime_base.image_name)
    if base_image_id is None:
        fail(
            f"Required runtime base image '{runtime_base.image_name}' was not found for grader '{key}'. "
            "Build base images first or omit --skip-base-images."
        )

    return hash_paths(
        graders_root,
        [dockerfile_path, *files_under(graders_root / grader_folder)],
        extra=[
            "grader-image",
            key,
            grader_folder,
            runtime_base.language,
            base_image_id,
        ],
    )


def build_runtime_base_image(
    runtime_base: RuntimeBase,
    graders_root: Path,
    base_dockerfile_path: Path,
    no_cache: bool,
    pull: bool,
    force_rebuild: bool,
) -> None:
    source_hash = runtime_base_source_hash(runtime_base, graders_root, base_dockerfile_path)
    if not force_rebuild and not no_cache and not pull and docker_image_has_source_hash(runtime_base.image_name, source_hash):
        log(f"[base:{runtime_base.language}] Skipping {runtime_base.image_name}; source unchanged.")
        return

    start = time.monotonic()
    log(f"[base:{runtime_base.language}] Building {runtime_base.image_name}...")

    build_cmd = [
        "docker",
        "build",
        *docker_cache_flags(no_cache, pull),
        "-f",
        str(base_dockerfile_path),
        "-t",
        runtime_base.image_name,
        "--label",
        f"{SOURCE_HASH_LABEL}={source_hash}",
        "--label",
        f"{SOURCE_ROLE_LABEL}=runtime-base",
        "--build-arg",
        f"RUNTIME_PACKAGES={runtime_base.runtime_packages}",
        ".",
    ]

    run_or_fail(
        build_cmd,
        cwd=graders_root,
        context=f"Failed to build runtime base image for '{runtime_base.language}'",
    )

    elapsed = time.monotonic() - start
    log(f"[base:{runtime_base.language}] Built {runtime_base.image_name} in {elapsed:.1f}s")


def build_grader_image(
    grader: dict[str, Any],
    graders_root: Path,
    dockerfile_path: Path,
    no_cache: bool,
    pull: bool,
    force_rebuild: bool,
) -> None:
    key = grader["key"]
    image_name = grader["imageName"]
    grader_folder = grader.get("graderFolder") or grader["key"]
    runtime_base = runtime_base_for_grader(grader)
    source_hash = grader_source_hash(grader, graders_root, dockerfile_path)
    if not force_rebuild and not no_cache and not pull and docker_image_has_source_hash(image_name, source_hash):
        log(f"[build:{key}] Skipping {image_name}; source unchanged.")
        return

    start = time.monotonic()

    log(f"[build:{key}] Building {image_name} from {runtime_base.image_name}...")
    build_cmd = [
        "docker",
        "build",
        *docker_cache_flags(no_cache, pull),
        "-f",
        str(dockerfile_path),
        "-t",
        image_name,
        "--label",
        f"{SOURCE_HASH_LABEL}={source_hash}",
        "--label",
        f"{SOURCE_ROLE_LABEL}=grader",
        "--build-arg",
        f"BASE_IMAGE={runtime_base.image_name}",
        "--build-arg",
        f"GRADER_NAME={grader_folder}",
    ]
    build_cmd.append(".")

    run_or_fail(
        build_cmd,
        cwd=graders_root,
        context=f"Failed to build image for grader '{key}'",
    )

    elapsed = time.monotonic() - start
    log(f"[build:{key}] Built {image_name} in {elapsed:.1f}s")


def kind_node_names(cluster_name: str) -> list[str]:
    result = run_cmd(["kind", "get", "nodes", "--name", cluster_name])
    if result.returncode != 0:
        return []
    return [line.strip() for line in result.stdout.splitlines() if line.strip()]


def kind_node_image_source_hash(node_name: str, image_name: str) -> str | None:
    result = run_cmd(["docker", "exec", node_name, "crictl", "inspecti", image_name])
    if result.returncode != 0:
        return None
    try:
        data = json.loads(result.stdout)
    except json.JSONDecodeError:
        return None

    labels = data.get("info", {}).get("imageSpec", {}).get("config", {}).get("Labels", {})
    if not isinstance(labels, dict):
        return None
    value = labels.get(SOURCE_HASH_LABEL)
    if not isinstance(value, str) or not value.strip():
        return None
    return value.strip()


def kind_cluster_has_image_source_hash(cluster_name: str, image_name: str, source_hash: str) -> bool:
    nodes = kind_node_names(cluster_name)
    if not nodes:
        return False
    return all(kind_node_image_source_hash(node, image_name) == source_hash for node in nodes)


def load_image_into_kind(grader: dict[str, Any], cluster_name: str, force_kind_load: bool) -> None:
    key = grader["key"]
    image_name = grader["imageName"]
    local_source_hash = docker_image_label(image_name, SOURCE_HASH_LABEL)
    if local_source_hash is None:
        fail(f"Cannot load missing local Docker image for grader '{key}': {image_name}")

    if not force_kind_load and kind_cluster_has_image_source_hash(cluster_name, image_name, local_source_hash):
        log(f"[load:{key}] Skipping {image_name}; already loaded into kind.")
        return

    start = time.monotonic()

    log(f"[load:{key}] Loading {image_name} into kind...")
    run_or_fail(
        ["kind", "load", "docker-image", image_name, "--name", cluster_name],
        context=f"Failed to load image into kind for grader '{key}'",
    )
    elapsed = time.monotonic() - start
    log(f"[load:{key}] Loaded {image_name} into kind in {elapsed:.1f}s")


def verify_cluster_context(cluster_name: str) -> None:
    context_name = f"kind-{cluster_name}"
    log("Verifying cluster context...")
    run_or_fail(
        ["kubectl", "cluster-info", "--context", context_name],
        context="kubectl could not verify the kind cluster context",
    )
    log(f"[OK] kubectl can reach cluster context: {context_name}")


def default_build_workers(grader_count: int) -> int:
    cpu_count = os.cpu_count() or 1
    return min(4, max(1, cpu_count // 2), max(1, grader_count))


def validate_worker_count(value: int, name: str) -> int:
    if value <= 0:
        fail(f"{name} must be greater than zero.")
    return value


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Build and load Elastic Autograder grader images into kind.")
    parser.add_argument("--config", default=str(DEFAULT_GRADERS_CONFIG), help="Path to graders.json.")
    parser.add_argument("--graders-root", default=str(DEFAULT_GRADERS_ROOT), help="Path to the grader build workspace.")
    parser.add_argument("--cluster-name", default=CLUSTER_NAME, help="kind cluster name.")
    parser.add_argument("--kind-config", default=str(KIND_CONFIG), help="Path to the kind cluster config.")
    parser.add_argument("--grader", help="Build/load only the grader with this key.")
    parser.add_argument("--no-cache", action="store_true", help="Disable Docker layer cache for base and grader image builds.")
    parser.add_argument("--pull", action="store_true", help="Ask Docker to pull newer base images while building.")
    parser.add_argument("--parallel", action="store_true", help="Build grader images concurrently after base images are ready.")
    parser.add_argument("--build-workers", type=int, help="Number of concurrent grader image builds when --parallel is used.")
    parser.add_argument("--load-workers", type=int, default=1, help="Number of concurrent kind image loads when --parallel is used.")
    parser.add_argument("--skip-base-images", action="store_true", help="Skip runtime base image builds and assume they already exist locally.")
    parser.add_argument("--force-rebuild", action="store_true", help="Rebuild images even when source-hash labels say they are unchanged.")
    parser.add_argument("--force-kind-load", action="store_true", help="Load images into kind even when every node already has the same source hash.")
    return parser.parse_args()


def build_runtime_bases(
    runtime_bases: list[RuntimeBase],
    graders_root: Path,
    base_dockerfile_path: Path,
    no_cache: bool,
    pull: bool,
    force_rebuild: bool,
) -> None:
    for runtime_base in runtime_bases:
        build_runtime_base_image(runtime_base, graders_root, base_dockerfile_path, no_cache, pull, force_rebuild)


def setup_graders_serial(
    graders: list[dict[str, Any]],
    graders_root: Path,
    dockerfile_path: Path,
    cluster_name: str,
    no_cache: bool,
    pull: bool,
    force_rebuild: bool,
    force_kind_load: bool,
) -> None:
    for grader in graders:
        build_grader_image(grader, graders_root, dockerfile_path, no_cache, pull, force_rebuild)

    for grader in graders:
        load_image_into_kind(grader, cluster_name, force_kind_load)


def setup_graders_parallel(
    graders: list[dict[str, Any]],
    graders_root: Path,
    dockerfile_path: Path,
    cluster_name: str,
    no_cache: bool,
    pull: bool,
    force_rebuild: bool,
    force_kind_load: bool,
    build_workers: int,
    load_workers: int,
) -> None:
    failures: list[str] = []

    with ThreadPoolExecutor(max_workers=build_workers) as build_pool, ThreadPoolExecutor(max_workers=load_workers) as load_pool:
        build_futures = {
            build_pool.submit(build_grader_image, grader, graders_root, dockerfile_path, no_cache, pull, force_rebuild): grader
            for grader in graders
        }
        load_futures = {}

        while build_futures:
            done, _ = wait(build_futures, return_when=FIRST_COMPLETED)
            for future in done:
                grader = build_futures.pop(future)
                key = grader["key"]
                try:
                    future.result()
                except BaseException as exc:
                    if isinstance(exc, KeyboardInterrupt):
                        raise
                    failures.append(f"[build:{key}] {exc}")
                    continue

                load_future = load_pool.submit(load_image_into_kind, grader, cluster_name, force_kind_load)
                load_futures[load_future] = grader

        for future, grader in load_futures.items():
            key = grader["key"]
            try:
                future.result()
            except BaseException as exc:
                if isinstance(exc, KeyboardInterrupt):
                    raise
                failures.append(f"[load:{key}] {exc}")

    if failures:
        details = "\n".join(failures)
        fail(f"One or more grader images failed to build or load:\n{details}")


def main() -> None:
    start = time.monotonic()
    args = parse_args()
    graders_config = resolve_repo_path(args.config)
    graders_root = resolve_repo_path(args.graders_root)
    kind_config = resolve_repo_path(args.kind_config)
    dockerfile_path = graders_root / "runtime" / "Dockerfile"
    base_dockerfile_path = graders_root / "runtime" / "Dockerfile.base"
    force_kind_load = args.force_kind_load or args.force_rebuild or args.no_cache or args.pull

    log("Elastic Autograder grader setup")
    log(f"Repo root: {REPO_ROOT}")
    log(f"Graders root: {graders_root}")
    log(f"Graders config: {graders_config}")

    require_tool("docker")
    require_tool("kind")
    require_tool("kubectl")

    check_required_paths(kind_config, graders_config, graders_root, dockerfile_path, base_dockerfile_path)
    ensure_kind_cluster(args.cluster_name, kind_config)

    graders = filter_graders(load_graders_config(graders_config, graders_root), args.grader)
    log(f"Loaded {len(graders)} grader definition(s) from {graders_config}")
    log(f"Docker cache: {'disabled' if args.no_cache else 'enabled'}")
    log(f"Docker pull: {'enabled' if args.pull else 'disabled'}")
    log(f"Force rebuild: {'enabled' if args.force_rebuild else 'disabled'}")
    log(f"Force kind load: {'enabled' if force_kind_load else 'disabled'}")

    runtime_bases = required_runtime_bases(graders)
    if args.skip_base_images:
        base_names = ", ".join(runtime_base.image_name for runtime_base in runtime_bases)
        log(f"Skipping runtime base image build. Required base image(s): {base_names}")
    else:
        build_runtime_bases(
            runtime_bases,
            graders_root,
            base_dockerfile_path,
            args.no_cache,
            args.pull,
            args.force_rebuild,
        )

    if args.parallel:
        build_workers = validate_worker_count(args.build_workers or default_build_workers(len(graders)), "--build-workers")
        load_workers = validate_worker_count(args.load_workers, "--load-workers")
        log(f"Parallel grader setup enabled: build_workers={build_workers}, load_workers={load_workers}")
        setup_graders_parallel(
            graders,
            graders_root,
            dockerfile_path,
            args.cluster_name,
            args.no_cache,
            args.pull,
            args.force_rebuild,
            force_kind_load,
            build_workers,
            load_workers,
        )
    else:
        if args.build_workers is not None:
            log("Ignoring --build-workers because --parallel was not set.")
        if args.load_workers != 1:
            log("Ignoring --load-workers because --parallel was not set.")

        log("Serial grader setup enabled.")
        setup_graders_serial(
            graders,
            graders_root,
            dockerfile_path,
            args.cluster_name,
            args.no_cache,
            args.pull,
            args.force_rebuild,
            force_kind_load,
        )

    verify_cluster_context(args.cluster_name)

    elapsed = time.monotonic() - start
    log("Setup complete.")
    log(f"Cluster '{args.cluster_name}' is ready and configured grader images are loaded in {elapsed:.1f}s.")


if __name__ == "__main__":
    main()
