-- Adds durable attempt, retry, and worker ownership tracking for queued grading jobs.

ALTER TABLE jobs
  DROP CONSTRAINT IF EXISTS jobs_status_check;

ALTER TABLE jobs
  ADD CONSTRAINT jobs_status_check CHECK (
    status IN ('PENDING', 'QUEUED', 'RUNNING', 'SUCCEEDED', 'PARTIAL', 'FAILED', 'DEAD_LETTERED', 'CANCELLED')
  );

ALTER TABLE jobs
  ADD COLUMN IF NOT EXISTS queued_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS attempt_count INT NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS max_attempts INT NOT NULL DEFAULT 3,
  ADD COLUMN IF NOT EXISTS last_attempt_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS queue_message_id TEXT,
  ADD COLUMN IF NOT EXISTS worker_id TEXT;

UPDATE jobs
SET queued_at = COALESCE(queued_at, created_at)
WHERE status = 'QUEUED';

CREATE INDEX IF NOT EXISTS idx_jobs_queued_at
  ON jobs(queued_at);

CREATE INDEX IF NOT EXISTS idx_jobs_worker_id
  ON jobs(worker_id);
