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

## Script Checks

```bash
python -m py_compile scripts/setup-graders.py scripts/burst-test.py scripts/requeue-stranded-jobs.py
python scripts/setup-graders.py --help
```

Smoke test one grader image:

```bash
python scripts/setup-graders.py --grader fib-java
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
python -m py_compile scripts/setup-graders.py scripts/burst-test.py scripts/requeue-stranded-jobs.py
docker compose --profile full config --services
python scripts/setup-graders.py --grader fib-java
git diff --check
```
