What is the purpose of a manifest?

Three core parts (in the current implementation)

entry_function: name or identifier for a specific grading function

comparisons: 
- exact : when a list is returned this MUST follow the exact order as answer key
- unordered_exact : can be returned in any order for the answer key

tests_cases: a list of the actual test cases with arguments being passed in (currently doesn't support certian problems like trees )

## Manifest v2 direction

`manifestVersion: 2` separates the shared assignment tests from language-specific runtime adapters. The runtime supports Python `function_cases` and single-file command-based `stdio_cases`.

Top-level fields:

- `manifestVersion`: must be `2`
- `assignmentKey`: shared assignment identifier such as `fib`
- `problemType`: one of `function_cases`, `stdio_cases`, or `project_cases`
- `comparison.mode`: currently `exact` or `unordered_exact`
- `cases`: shared test cases
- `languages`: map of supported language adapters

Example stdio adapter:

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

The selected language defaults to `python` and can be overridden with `GRADER_LANGUAGE`.

For `stdio_cases`, `runCommand` is required and `compileCommand` is optional. When `submission.fileName` is present, the mounted submission is copied to that file name before compile/run commands execute.
