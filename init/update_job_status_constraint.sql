-- Updates the existing jobs.status check constraint to match the current backend enum.
-- Use this on an already-running database so you do not need to drop and recreate jobs.

ALTER TABLE jobs
  DROP CONSTRAINT IF EXISTS jobs_status_check;

ALTER TABLE jobs
  ADD CONSTRAINT jobs_status_check
  CHECK (
    status IN ('PENDING', 'QUEUED', 'RUNNING', 'SUCCEEDED', 'PARTIAL', 'FAILED', 'CANCELLED')
  );

ALTER TABLE jobs
  DROP CONSTRAINT IF EXISTS jobs_failure_reason_check;

ALTER TABLE jobs
  ADD CONSTRAINT jobs_failure_reason_check
  CHECK (
    failure_reason IN (
      'NONE',
      'INVALID_UPLOAD',
      'WRONG_ANSWER',
      'TIMEOUT',
      'RESOURCE_LIMIT',
      'KUBERNETES_ERROR',
      'RESULT_PARSE_ERROR',
      'CONFIG_ERROR',
      'UNKNOWN'
    )
  );
