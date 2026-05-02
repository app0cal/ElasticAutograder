TRUNCATE TABLE jobs RESTART IDENTITY;

INSERT INTO jobs (
  grader_type,
  original_filename,
  submission_path,
  grader_image,
  status,
  failure_reason,
  created_at,
  updated_at,
  started_at,
  finished_at,
  score,
  tests_passed,
  tests_total,
  failure_message,
  result_json,
  k8s_job_name
)
VALUES
(
  'two_sum',
  'submission1.py',
  'grading/work/jobs/job-1/submission.py',
  'python:3.12',
  'FAILED',
  'WRONG_ANSWER',
  NOW() - INTERVAL '55 minutes',
  NOW() - INTERVAL '50 minutes',
  NOW() - INTERVAL '54 minutes',
  NOW() - INTERVAL '50 minutes',
  0.00,
  0,
  3,
  'No test cases passed.',
  '{
    "status": "FAILED",
    "validation_passed": true,
    "tests_passed": 0,
    "tests_total": 3,
    "score": 0.00,
    "error_message": "No test cases passed.",
    "results": [
      {
        "kind": "validation",
        "name": "validation_check",
        "passed": true,
        "message": "submission and key imported successfully; found callable expected function"
      },
      {
        "kind": "test",
        "name": "case_1",
        "passed": false,
        "message": "Expected [0,1], got [1,2]"
      },
      {
        "kind": "test",
        "name": "case_2",
        "passed": false,
        "message": "Expected [1,2], got []"
      },
      {
        "kind": "test",
        "name": "case_3",
        "passed": false,
        "message": "Expected [0,1], got null"
      }
    ]
  }'::jsonb,
  'job-two-sum-1'
),
(
  'valid_parentheses',
  'submission2.py',
  'grading/work/jobs/job-2/submission.py',
  'python:3.12',
  'SUCCEEDED',
  'NONE',
  NOW() - INTERVAL '45 minutes',
  NOW() - INTERVAL '40 minutes',
  NOW() - INTERVAL '44 minutes',
  NOW() - INTERVAL '40 minutes',
  100.00,
  3,
  3,
  NULL,
  '{
    "status": "SUCCEEDED",
    "validation_passed": true,
    "tests_passed": 3,
    "tests_total": 3,
    "score": 100.00,
    "error_message": null,
    "results": [
      {
        "kind": "validation",
        "name": "validation_check",
        "passed": true,
        "message": "submission and key imported successfully; found callable expected function"
      },
      {
        "kind": "test",
        "name": "case_1",
        "passed": true,
        "message": "Passed"
      },
      {
        "kind": "test",
        "name": "case_2",
        "passed": true,
        "message": "Passed"
      },
      {
        "kind": "test",
        "name": "case_3",
        "passed": true,
        "message": "Passed"
      }
    ]
  }'::jsonb,
  'job-valid-parentheses-2'
),
(
  'binary_search',
  'submission3.py',
  'grading/work/jobs/job-3/submission.py',
  'python:3.12',
  'PARTIAL',
  'NONE',
  NOW() - INTERVAL '35 minutes',
  NOW() - INTERVAL '30 minutes',
  NOW() - INTERVAL '34 minutes',
  NOW() - INTERVAL '30 minutes',
  33.33,
  1,
  3,
  NULL,
  '{
    "status": "PARTIAL",
    "validation_passed": true,
    "tests_passed": 1,
    "tests_total": 3,
    "score": 33.33,
    "error_message": null,
    "results": [
      {
        "kind": "validation",
        "name": "validation_check",
        "passed": true,
        "message": "submission and key imported successfully; found callable expected function"
      },
      {
        "kind": "test",
        "name": "case_1",
        "passed": true,
        "message": "Passed"
      },
      {
        "kind": "test",
        "name": "case_2",
        "passed": false,
        "message": "Expected 7, got -1"
      },
      {
        "kind": "test",
        "name": "case_3",
        "passed": false,
        "message": "Expected 10, got 4"
      }
    ]
  }'::jsonb,
  'job-binary-search-3'
),
(
  'merge_sorted_array',
  'submission4.py',
  'grading/work/jobs/job-4/submission.py',
  'python:3.12',
  'SUCCEEDED',
  'NONE',
  NOW() - INTERVAL '25 minutes',
  NOW() - INTERVAL '20 minutes',
  NOW() - INTERVAL '24 minutes',
  NOW() - INTERVAL '20 minutes',
  100.00,
  4,
  4,
  NULL,
  '{
    "status": "SUCCEEDED",
    "validation_passed": true,
    "tests_passed": 4,
    "tests_total": 4,
    "score": 100.00,
    "error_message": null,
    "results": [
      {
        "kind": "validation",
        "name": "validation_check",
        "passed": true,
        "message": "submission and key imported successfully; found callable expected function"
      },
      {
        "kind": "test",
        "name": "case_1",
        "passed": true,
        "message": "Passed"
      },
      {
        "kind": "test",
        "name": "case_2",
        "passed": true,
        "message": "Passed"
      },
      {
        "kind": "test",
        "name": "case_3",
        "passed": true,
        "message": "Passed"
      },
      {
        "kind": "test",
        "name": "case_4",
        "passed": true,
        "message": "Passed"
      }
    ]
  }'::jsonb,
  'job-merge-sorted-array-4'
),
(
  'reverse_linked_list',
  'submission5.py',
  'grading/work/jobs/job-5/submission.py',
  'python:3.12',
  'PARTIAL',
  'NONE',
  NOW() - INTERVAL '15 minutes',
  NOW() - INTERVAL '10 minutes',
  NOW() - INTERVAL '14 minutes',
  NOW() - INTERVAL '10 minutes',
  50.00,
  2,
  4,
  NULL,
  '{
    "status": "PARTIAL",
    "validation_passed": true,
    "tests_passed": 2,
    "tests_total": 4,
    "score": 50.00,
    "error_message": null,
    "results": [
      {
        "kind": "validation",
        "name": "validation_check",
        "passed": true,
        "message": "submission and key imported successfully; found callable expected function"
      },
      {
        "kind": "test",
        "name": "case_1",
        "passed": true,
        "message": "Passed"
      },
      {
        "kind": "test",
        "name": "case_2",
        "passed": true,
        "message": "Passed"
      },
      {
        "kind": "test",
        "name": "case_3",
        "passed": false,
        "message": "Expected [5,4,3,2,1], got [1,2,3,4,5]"
      },
      {
        "kind": "test",
        "name": "case_4",
        "passed": false,
        "message": "Expected [2,1], got [1,2]"
      }
    ]
  }'::jsonb,
  'job-reverse-linked-list-5'
);
