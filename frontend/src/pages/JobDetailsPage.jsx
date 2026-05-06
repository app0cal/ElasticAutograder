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
  const results = parseResults(job?.resultJson);
  const isActive = ACTIVE_STATUSES.has(job?.status);
  const canDownload = Boolean(job?.resultJson);
  const emptyResultMessage = getEmptyResultMessage(job);

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
              <DetailItem label="Failure Type" value={formatValue(job.failureReason)} />
            </div>
          </section>

          <section className="card job-details-card">
            <h2 className="job-details-section-title">Metadata</h2>
            <div className="job-details-list">
              <DetailRow label="Grader Image" value={formatValue(job.graderImage)} />
              <DetailRow label="Submitted File" value={formatValue(job.originalFilename)} />
              <DetailRow label="Institution" value={formatValue(job.institutionId)} />
              <DetailRow label="Submitted By" value={formatValue(job.submittedBy)} />
              <DetailRow label="Kubernetes Job" value={formatValue(job.k8sJobName)} />
              <DetailRow label="Worker" value={formatValue(job.workerId)} />
              <DetailRow label="Queue Message" value={formatValue(job.queueMessageId)} />
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
              <DetailRow label="Failure Reason" value={formatValue(job.failureReason)} />
              <DetailRow
                label="Failure Message"
                value={job.failureMessage ? job.failureMessage : "No failure message recorded for this job."}
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
            <div className="job-results-table-wrapper">
              <table className="job-results-table">
                <thead>
                  <tr>
                    <th>Name</th>
                    <th>Kind</th>
                    <th>Outcome</th>
                    <th>Message</th>
                  </tr>
                </thead>
                <tbody>
                  {results.map((result, index) => (
                    <tr
                      key={`${result.name ?? "result"}-${index}`}
                      className={result.passed ? "job-result-passed" : "job-result-failed"}
                    >
                      <td>{result.name ?? "Unnamed result"}</td>
                      <td>{result.kind ?? "unknown"}</td>
                      <td>
                        <span className={`status-pill status-${result.passed ? "succeeded" : "failed"}`}>
                          {result.passed ? "Passed" : "Failed"}
                        </span>
                      </td>
                      <td>{result.message ?? "No message provided."}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </section>
      </div>
    </div>
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

function parseResults(resultJson) {
  if (!resultJson) {
    return [];
  }

  try {
    const parsed = JSON.parse(resultJson);

    if (Array.isArray(parsed)) {
      return parsed;
    }

    if (Array.isArray(parsed.results)) {
      return parsed.results;
    }
  } catch {
    return [];
  }

  return [];
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
