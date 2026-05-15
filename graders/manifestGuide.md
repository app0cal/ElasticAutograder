# Manifest Guide

Grader manifests define the assignment tests and the runtime adapter used to execute a submission. The backend selects a grader from `config/graders.json`; the grader image contains the matching `graders/<grader-key>/manifest.json`.

## Supported Manifest Styles

Elastic Autograder currently supports:

- legacy Python function manifests
- v2 Python `function_cases`
- v2 command-based `stdio_cases` for single-file Java and C++ submissions
- v2 command-based `project_cases` for project zip submissions

Grader upload behavior is configured outside the manifest in `config/graders.json` with `uploadMode`:

- `single_file`: accept only the grader language's source extension.
- `batch_zip`: accept one source file or a zip that expands into one job per file. This is the default.
- `project_zip`: accept a zip intended to become one multi-file project job.

## Legacy Python Manifest

Legacy manifests are still supported for existing Python function graders.

```json
{
  "entry_function": "fib",
  "comparison": { "mode": "exact" },
  "test_cases": [
    { "name": "case_0", "args": [0], "expected": 0 }
  ]
}
```

The submitted Python file must define the configured function.

## Manifest v2

Manifest v2 separates shared test cases from language-specific adapters.

Top-level fields:

- `manifestVersion`: use `2`
- `assignmentKey`: shared assignment identifier, such as `fib`
- `problemType`: `function_cases`, `stdio_cases`, or `project_cases`
- `comparison.mode`: currently `exact` or `unordered_exact`
- `cases`: test cases
- `languages`: map of language adapters

## v2 Python Function Cases

```json
{
  "manifestVersion": 2,
  "assignmentKey": "fib",
  "problemType": "function_cases",
  "comparison": { "mode": "exact" },
  "cases": [
    { "name": "case_0", "args": [0], "expected": 0 }
  ],
  "languages": {
    "python": {
      "adapter": {
        "mode": "function",
        "entryFunction": "fib"
      }
    }
  }
}
```

## v2 Stdin/Stdout Cases

Use `stdio_cases` for single-file command-based submissions.

```json
{
  "manifestVersion": 2,
  "assignmentKey": "fib",
  "problemType": "stdio_cases",
  "comparison": { "mode": "exact" },
  "cases": [
    {
      "name": "case_0",
      "input": { "stdin": "0\n" },
      "expected": { "stdout": "0\n" }
    }
  ],
  "languages": {
    "java": {
      "adapter": {
        "mode": "stdio",
        "compileCommand": ["javac", "Main.java"],
        "runCommand": ["java", "Main"]
      },
      "submission": {
        "fileName": "Main.java"
      }
    }
  }
}
```

For `stdio_cases`:

- `runCommand` is required.
- `compileCommand` is optional.
- `submission.fileName` is optional but recommended for compiled single-file languages.
- stdout comparison is exact for the current runtime.
- stderr is captured for failed compile/run results.

## v2 Project Cases

Use `project_cases` with `uploadMode: "project_zip"` for multi-file project submissions. The runtime receives the mounted project directory as its submission path, copies it into a writable workspace, and runs commands from that writable project copy.

```json
{
  "manifestVersion": 2,
  "assignmentKey": "project-fib",
  "problemType": "project_cases",
  "comparison": { "mode": "exact" },
  "cases": [
    {
      "name": "case_0",
      "input": { "stdin": "0\n" },
      "expected": { "stdout": "0\n" }
    }
  ],
  "languages": {
    "java": {
      "adapter": {
        "mode": "project",
        "compileCommand": ["javac", "src/Main.java"],
        "runCommand": ["java", "-cp", "src", "Main"]
      }
    }
  }
}
```

For `project_cases`:

- `adapter.mode` must be `project`.
- `runCommand` is required and must be a non-empty list of strings.
- `compileCommand` is optional and must be a non-empty list of strings when present.
- `cases[].input.stdin` is optional and defaults to empty stdin.
- `cases[].expected.stdout` is required and must be a string.
- stdout comparison is exact.
- a compile timeout or nonzero compile exit is reported as a build failure.
- a run timeout, nonzero run exit, or wrong stdout is reported as a failed test result.

The included `fib-java-project` grader is the canonical sample for this mode. Its fixtures live under `mocksubmission/fib-java-project/` and use a zip containing `src/Main.java` and `src/Fibonacci.java`.

## Language Selection

The selected language defaults to `python`. The backend sets `GRADER_LANGUAGE` from the grader definition when `language` is configured in `config/graders.json`.

Example:

```json
{
  "key": "fib-java",
  "language": "java",
  "graderFolder": "fib-java",
  "imageName": "ea-grader-fib-java:v1",
  "manifestPath": "/app/grader/manifest.json"
}
```

## Add A New Grader

1. Create `graders/<grader-key>/manifest.json`.
2. Add a matching entry to `config/graders.json`.
3. Build and load the image:

```bash
python scripts/setup-graders.py --grader <grader-key>
```

4. Restart the backend if it is already running so the grader catalog is reloaded.
