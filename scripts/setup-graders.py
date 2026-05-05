#!/usr/bin/env python3
"""
setup_graders.py

Cross-platform setup/build/load script for Elastic Autograder graders.

What it does:
1. Verifies required tools exist: docker, kind, kubectl
2. Creates the kind cluster if it does not already exist
3. Reads grader definitions from config/graders.json
4. Builds each grader image using the shared runtime Dockerfile
5. Loads each grader image into the kind cluster
6. Verifies kubectl can reach the cluster

Assumptions:
- This script lives in: elastic/scripts/setup_graders.py
- graders.json lives in: elastic/config/graders.json
- All graders share the same build context:
    backend/grading/image-build
- All graders share the same Dockerfile:
    backend/grading/image-build/runtime/Dockerfile
- Each grader config entry includes at least:
    key, imageName, graderFolder
"""

from __future__ import annotations

import json
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Any

CLUSTER_NAME = "elastic-autograder"
SCRIPT_DIR = Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parent

KIND_CONFIG = REPO_ROOT / "k8s" / "kind-config.yaml"
GRADERS_CONFIG = REPO_ROOT / "config" / "graders.json"

IMAGE_BUILD_ROOT = REPO_ROOT / "backend" / "grading" / "image-build"
DOCKERFILE_PATH = IMAGE_BUILD_ROOT / "runtime" / "Dockerfile"


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


def require_tool(name: str) -> None:
    if shutil.which(name) is None:
        fail(f"Required tool '{name}' is not installed or not on PATH.")
    log(f"[OK] Found tool: {name}")


def check_required_paths() -> None:
    if not KIND_CONFIG.is_file():
        fail(f"kind config file not found: {KIND_CONFIG}")
    if not GRADERS_CONFIG.is_file():
        fail(f"graders config not found: {GRADERS_CONFIG}")
    if not IMAGE_BUILD_ROOT.is_dir():
        fail(f"image build root not found: {IMAGE_BUILD_ROOT}")
    if not DOCKERFILE_PATH.is_file():
        fail(f"Dockerfile not found: {DOCKERFILE_PATH}")


def ensure_kind_cluster() -> None:
    log(f"Checking for kind cluster '{CLUSTER_NAME}'...")
    result = run_cmd(["kind", "get", "clusters"])
    if result.returncode != 0:
        fail(f"Unable to list kind clusters.\nSTDERR:\n{result.stderr}".rstrip())

    clusters = {line.strip() for line in result.stdout.splitlines() if line.strip()}
    if CLUSTER_NAME in clusters:
        log(f"[OK] Cluster '{CLUSTER_NAME}' already exists.")
        return

    log(f"Cluster '{CLUSTER_NAME}' not found. Creating from config...")
    run_or_fail(
        ["kind", "create", "cluster", "--name", CLUSTER_NAME, "--config", str(KIND_CONFIG)],
        context="Failed to create kind cluster",
    )
    log(f"[OK] Cluster '{CLUSTER_NAME}' created.")


def load_graders_config() -> list[dict[str, Any]]:
    try:
        with GRADERS_CONFIG.open("r", encoding="utf-8") as f:
            data = json.load(f)
    except json.JSONDecodeError as e:
        fail(f"Invalid JSON in {GRADERS_CONFIG}: {e}")

    graders = data.get("graders")
    if not isinstance(graders, list) or not graders:
        fail(f"{GRADERS_CONFIG} must contain a non-empty 'graders' array.")

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

        grader_dir = IMAGE_BUILD_ROOT / grader_folder
        if not grader_dir.is_dir():
            fail(f"Grader '{key}' folder not found: {grader_dir}")

        manifest_file = grader_dir / "manifest.json"
        if not manifest_file.is_file():
            fail(f"Grader '{key}' is missing manifest.json at: {manifest_file}")

    return graders


def build_grader_image(grader: dict[str, Any]) -> None:
    key = grader["key"]
    image_name = grader["imageName"]
    grader_folder = grader.get("graderFolder") or grader["key"]

    log(f"Building grader image for '{key}'...")
    run_or_fail(
        [
            "docker",
            "build",
            "--no-cache",
            "-f",
            str(DOCKERFILE_PATH),
            "-t",
            image_name,
            "--build-arg",
            f"GRADER_NAME={grader_folder}",
            ".",
        ],
        cwd=IMAGE_BUILD_ROOT,
        context=f"Failed to build image for grader '{key}'",
    )
    log(f"[OK] Built image: {image_name}")


def load_image_into_kind(grader: dict[str, Any]) -> None:
    key = grader["key"]
    image_name = grader["imageName"]

    log(f"Loading image into kind for '{key}'...")
    run_or_fail(
        ["kind", "load", "docker-image", image_name, "--name", CLUSTER_NAME],
        context=f"Failed to load image into kind for grader '{key}'",
    )
    log(f"[OK] Loaded image into kind: {image_name}")


def verify_cluster_context() -> None:
    context_name = f"kind-{CLUSTER_NAME}"
    log("Verifying cluster context...")
    run_or_fail(
        ["kubectl", "cluster-info", "--context", context_name],
        context="kubectl could not verify the kind cluster context",
    )
    log(f"[OK] kubectl can reach cluster context: {context_name}")


def main() -> None:
    log("Elastic Autograder grader setup")
    log(f"Repo root: {REPO_ROOT}")

    require_tool("docker")
    require_tool("kind")
    require_tool("kubectl")

    check_required_paths()
    ensure_kind_cluster()

    graders = load_graders_config()
    log(f"Loaded {len(graders)} grader definition(s) from {GRADERS_CONFIG}")

    for grader in graders:
        build_grader_image(grader)

    for grader in graders:
        load_image_into_kind(grader)

    verify_cluster_context()

    log("Setup complete.")
    log(f"Cluster '{CLUSTER_NAME}' is ready and configured grader images are loaded.")


if __name__ == "__main__":
    main()
