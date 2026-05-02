# Grading Runtime Guide

The `backend/grading` directory contains the files used to run submissions through grader containers. It is part of the backend runtime, not a separate application.

## Directory Purpose

`image-build/runtime`

Contains the Python grading runtime. The runtime reads a submitted Python file and a grader manifest, executes the configured test cases, and prints JSON output for the backend to parse.

`image-build/<grader-key>/manifest.json`

Defines the grader contract for one supported problem. Each manifest identifies the callable function, test cases, expected answers, comparison style, timeout/resource expectations, and scoring behavior needed by the runtime.

`image-build/manifestGuide.md`

Explains the manifest format for developers who need to add or modify graders.

`uploads`

Temporary runtime staging location for uploaded submissions. The backend writes files here before turning them into Kubernetes ConfigMaps. These files are cleaned up after grading and should not be committed.

## Grading Flow

1. A user uploads a submission from the frontend.
2. The backend stores the upload temporarily under `grading/uploads`.
3. The backend reads the selected grader definition from `config/graders.json`.
4. `Fabric8GradingOrchestrator` creates a Kubernetes ConfigMap containing the submission.
5. The orchestrator creates a Kubernetes Job using the selected grader image.
6. The grader container runs `python /app/main.py /work/submission.py <manifest-path>`.
7. The runtime prints JSON results to stdout.
8. The backend reads the pod logs, parses the JSON, stores the result, and deletes temporary resources.

## Running The Runtime Directly

The integration/system test runs the Python grading runtime directly without Kubernetes. This is useful when debugging manifests or runtime behavior.

From the `backend` directory:

```bash
python grading/image-build/runtime/main.py ../mocksubmission/fib/fibpass1.py grading/image-build/fib/manifest.json
```

Expected behavior: the command prints a JSON object with fields such as `status`, `tests_passed`, `tests_total`, `score`, and `results`.

## Adding Or Updating A Grader

1. Add or update a grader entry in `../config/graders.json`.
2. Create or update `image-build/<grader-key>/manifest.json`.
3. Make sure the manifest points to the callable function expected in submitted files.
4. Add sample submissions under `../mocksubmission/<grader-key>/` when helpful.
5. Run backend tests:

```bash
cd ..
./gradlew test --no-daemon
```

6. Rebuild/load grader images for Kubernetes:

```bash
cd ..
python3 ../scripts/setup-graders.py
```

## Troubleshooting

- If a submission cannot be found, confirm the backend wrote it under `grading/uploads`.
- If a grader fails before running tests, confirm the manifest path in `config/graders.json`.
- If Kubernetes jobs do not start, confirm the kind cluster exists and the grader images were loaded into it.
- If runtime JSON cannot be parsed, run `main.py` directly and inspect its stdout.
