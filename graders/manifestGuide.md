# Manifest Guide

Grader manifests define the assignment tests and the runtime adapter used to execute a submission. The backend selects a grader from `config/graders.json`; the grader image contains the matching `graders/<grader-key>/manifest.json`.

## Supported Manifest Styles

Elastic Autograder currently supports:

- legacy Python function manifests
- v2 Python `function_cases`
- v2 command-based `stdio_cases` for single-file Java and C++ submissions

`project_cases` is reserved for future project/zip-style submissions and is not implemented yet.

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
- `problemType`: `function_cases` or `stdio_cases`
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
