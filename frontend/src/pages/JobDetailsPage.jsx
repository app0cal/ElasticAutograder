import { useEffect, useMemo, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { downloadResults } from "../api/download_file";
import { fetchGraders, fetchJobById } from "../api/jobs";
import { subscribeToMockIdentityChanges } from "../api/mock_identity";

const REFRESH_INTERVAL = 1000;
const ACTIVE_STATUSES = new Set(["QUEUED", "RUNNING"]);

export default function JobDetailsPage() {
  const { jobId } = useParams();
  const [job, setJob] = useState(null);
  const [graders, setGraders] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [downloadError, setDownloadError] = useState("");

  useEffect(() => {
    let isMounted = true;
    let intervalId;

    async function loadGraders() {
      try {
        const graderOptions = await fetchGraders();
        if (isMounted) {
          setGraders(graderOptions);
        }
      } catch {
        if (isMounted) {
          setGraders([]);
        }
      }
    }

    async function loadJob(isInitial = false) {
      try {
        if (isInitial && isMounted) {
          setLoading(true);
          setError("");
        }

        const jobData = await fetchJobById(jobId);

        if (!isMounted) {
          return;
        }

        setJob(jobData);
        setError("");

        if (ACTIVE_STATUSES.has(jobData.status)) {
          if (!intervalId) {
            intervalId = window.setInterval(() => {
              loadJob(false);
            }, REFRESH_INTERVAL);
          }
        } else if (intervalId) {
          window.clearInterval(intervalId);
          intervalId = undefined;
        }
      } catch (err) {
        if (!isMounted) {
          return;
        }

        const notFound = err.message?.includes("404");
        setError(notFound ? `Job ${jobId} was not found.` : err.message || "Failed to load job.");

        if (intervalId) {
          window.clearInterval(intervalId);
          intervalId = undefined;
        }
      } finally {
        if (isMounted) {
          setLoading(false);
        }
      }
    }

    loadGraders();
    loadJob(true);
    const unsubscribe = subscribeToMockIdentityChanges(() => {
      loadGraders();
      loadJob(true);
    });

    return () => {
      isMounted = false;
      if (intervalId) {
        window.clearInterval(intervalId);
      }
      unsubscribe();
    };
  }, [jobId]);

  const graderLabelMap = useMemo(() => {
    const map = new Map();

    for (const grader of graders) {
      map.set(grader.key, grader.label);
    }

    return map;
  }, [graders]);

  const graderLabel = graderLabelMap.get(job?.graderType) ?? job?.graderType ?? "";
  const results = parseResultEntries(job?.resultJson);
  const isActive = ACTIVE_STATUSES.has(job?.status);
  const canDownload = Boolean(job?.resultJson);
  const emptyResultMessage = getEmptyResultMessage(job);
  const outcomeSummary = getOutcomeSummary(job, results);

  async function handleDownloadResults() {
    setDownloadError("");

    try {
      const blob = await downloadResults(job.id);
      const url = URL.createObjectURL(blob);
      const link = document.createElement("a");
      link.href = url;
      link.download = `${job.originalFilename}-results.json`;
      link.click();
      URL.revokeObjectURL(url);
    } catch {
      setDownloadError("Could not download results file.");
    }
  }

  if (loading) {
    return (
      <div className="job-details-page">
        <div className="job-details-shell">
          <p>Loading job details...</p>
        </div>
      </div>
    );
  }

  if (error) {
    return (
      <div className="job-details-page">
        <div className="job-details-shell">
          <div className="job-details-header">
            <div className="job-details-header-text">
              <h1 className="page-title">Job Details</h1>
              <p className="jobs-subtitle">Review a single grading run and its execution record.</p>
            </div>

            <div className="job-details-header-actions">
              <Link to="/jobs" className="button nav-button">
                Back to Jobs
              </Link>
            </div>
          </div>

          <div className="card job-details-card">
            <p className="status-failed">{error}</p>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="job-details-page">
      <div className="job-details-shell">
        <div className="job-details-header">
          <div className="job-details-header-text">
            <h1 className="page-title">Job {job.id}</h1>
            <p className="jobs-subtitle">
              Review a single grading run and its execution record.
              {isActive ? " This page auto-refreshes while the job is active." : ""}
            </p>
          </div>

          <div className="job-details-header-actions">
            <Link to="/jobs" className="button nav-button">
              Back to Jobs
            </Link>
            <button
              className="button nav-button"
              type="button"
              onClick={handleDownloadResults}
              disabled={!canDownload}
              title={canDownload ? "Download results.json" : "Results download is available when results exist"}
            >
              Download Results
            </button>
          </div>
        </div>

        {downloadError && <p className="status-failed">{downloadError}</p>}

        <div className="job-details-grid">
          <section className="card job-details-card job-summary-card">
            <h2 className="job-details-section-title">Summary</h2>
            <div className="job-details-kpis">
              <DetailItem label="Filename" value={job.originalFilename} />
              <DetailItem label="Grader Type" value={graderLabel} />
              <DetailItem
                label="Status"
                value={<span className={`status-pill status-${job.status.toLowerCase()}`}>{job.status}</span>}
              />
              <DetailItem label="Score" value={formatScore(job.score)} />
              <DetailItem label="Tests" value={formatTests(job.testsPassed, job.testsTotal)} />
              <DetailItem label="Failure Type" value={formatFailureReason(job.failureReason)} />
            </div>
            <div className={`job-outcome-summary job-outcome-${outcomeSummary.tone}`}>
              <strong>{outcomeSummary.title}</strong>
              <p>{outcomeSummary.description}</p>
            </div>
          </section>

          <section className="card job-details-card">
            <h2 className="job-details-section-title">Metadata</h2>
            <div className="job-details-list">
              <DetailRow label="Grader Image" value={formatDiagnosticValue(job.graderImage)} />
              <DetailRow label="Submitted File" value={formatDiagnosticValue(job.originalFilename)} />
              <DetailRow label="Submission Kind" value={formatDiagnosticValue(job.submissionKind)} />
              <DetailRow label="Institution" value={formatDiagnosticValue(job.institutionId)} />
              <DetailRow label="Submitted By" value={formatDiagnosticValue(job.submittedBy)} />
              <DetailRow label="Kubernetes Job Name" value={formatDiagnosticValue(job.k8sJobName)} />
              <DetailRow label="Worker ID" value={formatDiagnosticValue(job.workerId)} />
              <DetailRow label="Queue Message ID" value={formatDiagnosticValue(job.queueMessageId)} />
            </div>
          </section>

          <section className="card job-details-card">
            <h2 className="job-details-section-title">Lifecycle</h2>
            <div className="job-details-list">
              <DetailRow label="Created At" value={formatDate(job.createdAt)} />
              <DetailRow label="Updated At" value={formatDate(job.updatedAt)} />
              <DetailRow label="Started At" value={formatDate(job.startedAt)} />
              <DetailRow label="Finished At" value={formatDate(job.finishedAt)} />
              <DetailRow label="Queued At" value={formatDate(job.queuedAt)} />
              <DetailRow label="Last Attempt At" value={formatDate(job.lastAttemptAt)} />
              <DetailRow label="Attempts" value={formatAttempts(job.attemptCount, job.maxAttempts)} />
              <DetailRow label="Run Time" value={formatDuration(job.startedAt, job.finishedAt)} />
            </div>
          </section>

          <section className="card job-details-card">
            <h2 className="job-details-section-title">Failure Details</h2>
            <div className="job-details-list">
              <DetailRow label="Failure Reason" value={formatFailureReason(job.failureReason)} />
              <DetailRow
                label="Failure Message"
                value={getFailureMessage(job)}
                multiline
              />
            </div>
          </section>
        </div>

        <section className="card job-details-card job-results-card">
          <h2 className="job-details-section-title">Results</h2>
          {results.length === 0 ? (
            <p className="muted">{emptyResultMessage}</p>
          ) : (
            <div className="job-results-list">
              {results.map((result) => (
                <ResultEntryCard key={result.id} result={result} />
              ))}
            </div>
          )}
        </section>
      </div>
    </div>
  );
}

function ResultEntryCard({ result }) {
  return (
    <article className={`job-result-card ${result.cardClassName}`}>
      <div className="job-result-card-header">
        <div>
          <h3 className="job-result-card-title">{result.name}</h3>
          <p className="job-result-card-meta">{result.kindLabel}</p>
        </div>
        <span className={`status-pill status-${result.outcomeStatusClass}`}>
          {result.outcomeLabel}
        </span>
      </div>
      <p className="job-result-message">{result.message}</p>
    </article>
  );
}

function DetailItem({ label, value }) {
  return (
    <div className="job-details-kpi">
      <span className="job-details-kpi-label">{label}</span>
      <div className="job-details-kpi-value">{value}</div>
    </div>
  );
}

function DetailRow({ label, value, multiline = false }) {
  return (
    <div className={`job-details-row${multiline ? " job-details-row-multiline" : ""}`}>
      <span className="job-details-row-label">{label}</span>
      <span className="job-details-row-value">{value}</span>
    </div>
  );
}

function parseResultEntries(resultJson) {
  if (!resultJson) {
    return [];
  }

  try {
    const parsed = JSON.parse(resultJson);
    const rawEntries = Array.isArray(parsed) ? parsed : parsed?.results;

    if (Array.isArray(rawEntries)) {
      return rawEntries.map((entry, index) => normalizeResultEntry(entry, index));
    }
  } catch {
    return [];
  }

  return [];
}

function normalizeResultEntry(entry, index) {
  const result = entry && typeof entry === "object" ? entry : {};
  const passed = typeof result.passed === "boolean" ? result.passed : null;
  const outcomeLabel = passed === true ? "Passed" : passed === false ? "Failed" : "Unknown";
  const outcomeStatusClass = passed === true ? "succeeded" : passed === false ? "failed" : "cancelled";

  return {
    id: `${result.name || "result"}-${index}`,
    name: normalizeResultName(result.name, index),
    kindLabel: formatResultKind(result.kind),
    outcomeLabel,
    outcomeStatusClass,
    outcomeClass: passed === true ? "passed" : passed === false ? "failed" : "unknown",
    cardClassName: passed === true
      ? "job-result-card-passed"
      : passed === false
        ? "job-result-card-failed"
        : "job-result-card-unknown",
    passed,
    message: formatSafeMessage(result.message)
  };
}

function normalizeResultName(name, index) {
  return typeof name === "string" && name.trim() ? name : `Result ${index + 1}`;
}

function formatResultKind(kind) {
  return typeof kind === "string" && kind.trim() ? formatValue(kind) : "Test";
}

function formatSafeMessage(message) {
  return typeof message === "string" && message.trim() ? message : "No message provided.";
}

function formatFailureReason(reason) {
  return formatValue(reason);
}

function formatDiagnosticValue(value) {
  return formatValue(value);
}

function getFailureMessage(job) {
  if (!job) {
    return "No failure message recorded for this job.";
  }

  if (job.status === "SUCCEEDED") {
    return "No failure recorded.";
  }

  if (job.status === "PARTIAL") {
    return "No terminal failure recorded. Review failed result cases if present.";
  }

  if (ACTIVE_STATUSES.has(job.status)) {
    return "Failure details will appear here if grading fails.";
  }

  if ((job.status === "FAILED" || job.status === "DEAD_LETTERED") && job.failureMessage) {
    return job.failureMessage;
  }

  return "No failure message recorded for this job.";
}

function getOutcomeSummary(job, results) {
  if (!job) {
    return {
      title: "Status unavailable",
      description: "The job status is not recognized by the current interface.",
      tone: "neutral"
    };
  }

  const testsSummary = formatOutcomeTests(job.testsPassed, job.testsTotal);

  switch (job.status) {
    case "QUEUED":
      return {
        title: "Waiting for a worker",
        description: "This job has been accepted and is waiting for background grading to begin.",
        tone: "active"
      };
    case "RUNNING":
      return {
        title: "Grading in progress",
        description: "The submission is currently running in the grader. This page will refresh automatically.",
        tone: "active"
      };
    case "SUCCEEDED":
      return {
        title: "All recorded tests passed",
        description: testsSummary ?? "The grader completed successfully.",
        tone: "success"
      };
    case "PARTIAL":
      return {
        title: "Some tests passed",
        description: testsSummary
          ? `${testsSummary} Review the failed cases below.`
          : "The grader awarded partial credit. Review the results below.",
        tone: "partial"
      };
    case "FAILED":
      return getFailedOutcomeSummary(job.failureReason, results);
    case "DEAD_LETTERED":
      return {
        title: "Retry attempts exhausted",
        description: "The job failed after its retry attempts were used. Check the failure details and worker or Kubernetes logs.",
        tone: "warning"
      };
    case "CANCELLED":
      return {
        title: "Job cancelled",
        description: "This grading job was cancelled before producing a final result.",
        tone: "neutral"
      };
    default:
      return {
        title: "Status unavailable",
        description: "The job status is not recognized by the current interface.",
        tone: "neutral"
      };
  }
}

function getFailedOutcomeSummary(failureReason, results) {
  switch (failureReason) {
    case "WRONG_ANSWER":
      return {
        title: "Output did not match",
        description: "The submission ran, but one or more results did not match the grader expectation. Review the failed cases below.",
        tone: "failed"
      };
    case "INVALID_UPLOAD":
      return {
        title: "Submission could not be graded",
        description: "The grader could not build, load, or validate this submission. Check the failure message for details.",
        tone: "failed"
      };
    case "TIMEOUT":
      return {
        title: "Execution timed out",
        description: "The submission exceeded the allowed execution time. Check for slow or non-terminating code.",
        tone: "warning"
      };
    case "RESOURCE_LIMIT":
      return {
        title: "Resource limit exceeded",
        description: "The submission used more memory or compute resources than the grader allows.",
        tone: "warning"
      };
    case "KUBERNETES_ERROR":
      return {
        title: "Grader infrastructure failed",
        description: "The grading job failed before a normal result could be produced. This is likely an environment or Kubernetes issue.",
        tone: "warning"
      };
    case "UNKNOWN":
      return {
        title: "Grading failed",
        description: "The grader failed for an uncategorized reason. Check the failure message for details.",
        tone: "warning"
      };
    default:
      return {
        title: "Grading failed",
        description: results.some((result) => result.passed === false)
          ? "Review the failed cases below."
          : "Check the failure details for the recorded reason.",
        tone: "failed"
      };
  }
}

function formatOutcomeTests(passed, total) {
  return passed != null && total != null ? `Passed ${passed} of ${total} tests.` : null;
}

function getEmptyResultMessage(job) {
  if (!job) {
    return "No saved result entries are available for this job yet.";
  }

  if (ACTIVE_STATUSES.has(job.status)) {
    return "Results will appear after background grading finishes.";
  }

  if (job.status === "FAILED") {
    return "This job finished without saved test results. Check failure details for the recorded reason.";
  }

  if (job.status === "DEAD_LETTERED") {
    return "This job exhausted retry attempts before producing saved results.";
  }

  return "No saved result entries are available for this job.";
}

function formatDate(date) {
  if (!date) {
    return "Not available";
  }

  return new Intl.DateTimeFormat("en-US", {
    year: "numeric",
    month: "long",
    day: "numeric",
    hour: "2-digit",
    minute: "numeric",
    second: "numeric"
  }).format(new Date(date));
}

function formatTests(passed, total) {
  return passed != null && total != null ? `${passed} / ${total}` : "Not available";
}

function formatAttempts(attemptCount, maxAttempts) {
  if (attemptCount == null && maxAttempts == null) {
    return "Not available";
  }

  return `${attemptCount ?? 0} / ${maxAttempts ?? "unknown"}`;
}

function formatScore(score) {
  return score != null ? score : "Not available";
}

function formatValue(value) {
  if (value == null || value === "" || value === "NONE") {
    return "Not available";
  }

  if (typeof value === "string" && value.includes("_")) {
    return value
      .toLowerCase()
      .split("_")
      .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
      .join(" ");
  }

  return value;
}

function formatDuration(startedAt, finishedAt) {
  if (!startedAt || !finishedAt) {
    return "Not available";
  }

  const durationMs = new Date(finishedAt).getTime() - new Date(startedAt).getTime();

  if (!Number.isFinite(durationMs) || durationMs < 0) {
    return "Not available";
  }

  if (durationMs < 1000) {
    return "< 1 second";
  }

  const totalSeconds = Math.floor(durationMs / 1000);
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;
  const parts = [];

  if (hours > 0) {
    parts.push(`${hours}h`);
  }

  if (minutes > 0) {
    parts.push(`${minutes}m`);
  }

  if (seconds > 0 || parts.length === 0) {
    parts.push(`${seconds}s`);
  }

  return parts.join(" ");
}
