# Testing And Verification

This document lists the checks used to verify local changes. Run the smallest relevant set while developing, and run the broader set before preparing a release.

## Runtime Tests

The grader runtime tests cover manifest validation, Python function cases, Java/C++ stdio cases, compile failures, runtime failures, wrong answers, and timeouts.

```bash
python -m unittest discover graders/runtime -p 'test_*.py'
```

## Backend Tests

```bash
cd backend
./gradlew test
```

Generate coverage:

```bash
cd backend
./gradlew test jacocoTestReport
```

Coverage output:

```text
backend/build/reports/jacoco/test/html/index.html
```

## Frontend Checks

```bash
cd frontend
npm run lint
npm run build
```

The production build may warn about a large JavaScript chunk. That warning is not currently release-blocking.

## Job Details Feedback UI

Use these fixtures to manually verify job details presentation after submitting through the browser or burst script:

| Outcome | Grader | Fixture | Expected UI Check |
| --- | --- | --- | --- |
| Success | `fib` | `mocksubmission/fib/fibpass1.py` | Summary says all recorded tests passed; result cards are passed. |
| Partial | `fib` | `mocksubmission/fib/fibpartial.py` | Summary reports partial credit; failed result cards are easy to find. |
| Wrong answer | `fib` | `mocksubmission/fib/fibfail1.py` | Summary explains output mismatch; result cards show safe messages. |
| Java compile/build failure | `fib-java` | `mocksubmission/fib-java/FibCompileError.java` | Summary says the submission could not be graded; Failure Details include the compiler message. |
| Java runtime error | `fib-java` | `mocksubmission/fib-java/FibRuntimeError.java` | Result cards show failed runtime case messages without overflowing. |
| C++ wrong answer | `fib-cpp` | `mocksubmission/fib-cpp/fib_wrong.cpp` | Result cards show failed stdout comparison messages. |
| Timeout | `fib-performance` | `mocksubmission/fib-performance/fibslow_recursive.py` | Summary explains execution timeout. |
| Resource limit | `memory-demo` | `mocksubmission/memory-demo/memoryoom.py` | Summary explains resource-limit failure. |

For all completed jobs, verify `Download Results` still downloads the stored JSON when result JSON exists.

## Script Checks

```bash
python -m py_compile scripts/setup-graders.py scripts/doctor.py scripts/smoke-test.py scripts/burst-test.py scripts/requeue-stranded-jobs.py
python scripts/setup-graders.py --help
python scripts/doctor.py --help
python scripts/smoke-test.py --help
```

Smoke test one grader image build:

```bash
python scripts/setup-graders.py --grader fib-java
```

Check a running local environment without creating jobs:

```bash
python scripts/doctor.py
```

Create one real job and verify the API, queue, worker, and Kubernetes grader path:

```bash
python scripts/smoke-test.py
```

## Compose Checks

```bash
docker compose config --services
docker compose --profile app config --services
docker compose --profile full config --services
```

Expected service sets:

```text
default: postgres redis
app:     postgres redis backend-api backend-worker
full:    postgres redis backend-api backend-worker frontend
```

## Burst And Failure Testing

Use [Burst And Failure Testing](burst-testing.md) for queue pressure, mixed-language, timeout, and memory-limit scenarios.

Common smoke runs:

```bash
python scripts/burst-test.py success-burst --count 8
python scripts/burst-test.py mixed-language-burst --count 100 --seed 12345
```

## Sample Submissions

Current fixture folders live under `mocksubmission/`:

- `mocksubmission/fib/`
- `mocksubmission/fib-java/`
- `mocksubmission/fib-cpp/`
- `mocksubmission/fib-performance/`
- `mocksubmission/memory-demo/`
- `mocksubmission/twosum/`

These fixtures are used by burst scripts and manual browser uploads.

## Recommended Pre-Release Check

```bash
python -m unittest discover graders/runtime -p 'test_*.py'
cd backend && ./gradlew test
cd ../frontend && npm run lint && npm run build
cd ..
python -m py_compile scripts/setup-graders.py scripts/doctor.py scripts/smoke-test.py scripts/burst-test.py scripts/requeue-stranded-jobs.py
docker compose --profile full config --services
python scripts/setup-graders.py --grader fib-java
python scripts/doctor.py
python scripts/smoke-test.py
git diff --check
```
