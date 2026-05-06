import { useEffect, useMemo, useRef, useState } from "react";
import { Link, useLocation, useNavigate } from "react-router-dom";
import { fetchRecentJobs, fetchGraders, fetchQueueHealth } from "../api/jobs";
import JobsTable from "../components/JobsTable";
import MockIdentityPanel from "../components/MockIdentityPanel";
import { subscribeToMockIdentityChanges } from "../api/mock_identity";

const REFRESH_INTERVAL = 1000;

export default function JobsBoard() {
  const navigate = useNavigate();
  const location = useLocation();
  const gridRef = useRef(null);
  const refreshInterval = useRef(null);

  const [jobs, setJobs] = useState([]);
  const [graders, setGraders] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [queueHealth, setQueueHealth] = useState(null);
  const [queueHealthError, setQueueHealthError] = useState("");
  const [showSummary, setShowSummary] = useState(false);
  const [queuedHandoff, setQueuedHandoff] = useState(() => normalizeQueuedHandoff(location.state));

  async function load(isInitial = false) {
    try {
      if (isInitial) {
        setLoading(true);
        setError("");
      }

      if (isInitial) {
        const [jobsData, gradersData] = await Promise.all([
          fetchRecentJobs(),
          fetchGraders()
        ]);

        setJobs(jobsData);
        setGraders(gradersData);
      } else {
        const jobsData = await fetchRecentJobs();
        setJobs(jobsData);
      }
    } catch (err) {
      setError(err.message || "Failed to load jobs.");
    } finally {
      setLoading(false);
    }
  }

  async function loadQueueHealth() {
    try {
      const health = await fetchQueueHealth();
      setQueueHealth(health);
      setQueueHealthError("");
    } catch (err) {
      setQueueHealthError(err.message || "Failed to load queue health.");
    }
  }

  useEffect(() => {
    load(true);
    loadQueueHealth();

    refreshInterval.current = setInterval(() => {
      load(false);
      loadQueueHealth();
    }, REFRESH_INTERVAL);

    const unsubscribe = subscribeToMockIdentityChanges(() => load(true));

    return () => {
      clearInterval(refreshInterval.current);
      unsubscribe();
    };
  }, []);

  useEffect(() => {
    setQueuedHandoff(normalizeQueuedHandoff(location.state));
  }, [location.state]);

  const summary = useMemo(() => {
    const activeJobs = jobs.filter((job) => job.status === "QUEUED" || job.status === "RUNNING").length;
    const failedJobs = jobs.filter((job) => job.status === "FAILED").length;
    const deadLetteredJobs = jobs.filter((job) => job.status === "DEAD_LETTERED").length;
    const partialJobs = jobs.filter((job) => job.status === "PARTIAL").length;
    const invalidUploads = jobs.filter((job) => job.failureReason === "INVALID_UPLOAD").length;
    const succeededJobs = jobs.filter((job) => job.status === "SUCCEEDED").length;
    const successRate = jobs.length > 0 ? Math.round((succeededJobs / jobs.length) * 100) : 0;

    return [
      { label: "Total Recent Jobs", value: jobs.length },
      { label: "Queued + Running", value: activeJobs },
      { label: "Failed Jobs", value: failedJobs },
      { label: "Dead Lettered", value: deadLetteredJobs },
      { label: "Partial Jobs", value: partialJobs },
      { label: "Invalid Uploads", value: invalidUploads },
      { label: "Success Rate", value: `${successRate}%` }
    ];
  }, [jobs]);

  return (
    <div className="jobs-page">
      <div className="jobs-board-shell">
        <div className="jobs-top-bar">
          <div className="jobs-top-bar-text">
            <h1 className="page-title">Recent Jobs</h1>
            <p className="jobs-subtitle">Track grading progress and review completed runs.</p>
          </div>

          <div className="jobs-top-bar-actions">
            <MockIdentityPanel />
            <Link to="/" className="button nav-button">
              Home
            </Link>
            <Link to="/submit" className="button nav-button">
              New Job
            </Link>
            <button
              type="button"
              className="button nav-button jobs-summary-toggle"
              onClick={() => setShowSummary((current) => !current)}
            >
              {showSummary ? "Hide Summary" : "Show Summary"}
            </button>
          </div>
        </div>

        {loading && <p>Loading jobs...</p>}
        {error && <p className="status-failed">{error}</p>}
        {queuedHandoff && (
          <section className="card jobs-queued-banner" aria-label="Queued upload summary">
            <div className="jobs-queued-banner-main">
              <span className="jobs-queued-label">Queued</span>
              <strong>{formatQueuedTitle(queuedHandoff.jobs.length)}</strong>
              <span>{formatQueuedSubtitle(queuedHandoff)}</span>
            </div>
            <button
              type="button"
              className="jobs-queued-dismiss"
              onClick={() => {
                setQueuedHandoff(null);
                navigate(".", { replace: true, state: null });
              }}
              aria-label="Dismiss queued upload summary"
            >
              Dismiss
            </button>
          </section>
        )}
        {!loading && !error && jobs.length === 0 && (
          <div className="card jobs-empty-card">
            <h2 className="jobs-empty-title">No jobs yet</h2>
            <p className="muted jobs-empty-copy">
              Submit a new grading run to start building your recent jobs dashboard.
            </p>
            <div className="jobs-empty-actions">
              <Link to="/submit" className="button nav-button">
                New Job
              </Link>
            </div>
          </div>
        )}

        {!loading && !error && <SystemHealthPanel health={queueHealth} error={queueHealthError} />}

        {!loading && !error && jobs.length > 0 && (
          <>
            {showSummary && (
              <section className="card jobs-summary-panel" aria-label="Job summaries">
                <div className="jobs-summary-grid">
                  {summary.map((item) => (
                    <article key={item.label} className="jobs-summary-card">
                      <span className="jobs-summary-label">{item.label}</span>
                      <strong className="jobs-summary-value">{item.value}</strong>
                    </article>
                  ))}
                </div>
              </section>
            )}

            <div className="card jobs-board-card">
              <JobsTable
                jobs={jobs}
                graders={graders}
                gridRef={gridRef}
                onViewDetails={(job) => navigate(`/jobs/${job.id}`)}
              />
            </div>
          </>
        )}
      </div>
    </div>
  );
}

function SystemHealthPanel({ health, error }) {
  const jobCounts = health?.jobCounts || {};
  const staleRunningJobs = Array.isArray(health?.staleRunningJobs) ? health.staleRunningJobs : [];
  const recentDeadLetteredJobs = Array.isArray(health?.recentDeadLetteredJobs)
    ? health.recentDeadLetteredJobs
    : [];
  const redisOnline = health?.redisConnected === true;
  const redisKnown = Boolean(health);

  return (
    <section className="card system-health-panel" aria-label="System health">
      <div className="system-health-header">
        <div>
          <h2 className="system-health-title">System Health</h2>
          <p className="system-health-subtitle">Queue and worker visibility for background grading.</p>
        </div>
        <span className={`system-health-badge ${redisOnline ? "is-ok" : "is-warning"}`}>
          {redisKnown ? (redisOnline ? "Redis connected" : "Redis disconnected") : "Health pending"}
        </span>
      </div>

      {error && <p className="system-health-error">{error}</p>}
      {health && (
        <>
          <div className="system-health-grid">
            <HealthMetric label="Queue Depth" value={health.queueDepth ?? 0} />
            <HealthMetric label="Queued Jobs" value={jobCounts.QUEUED ?? 0} />
            <HealthMetric label="Running Jobs" value={jobCounts.RUNNING ?? 0} />
            <HealthMetric label="Dead Lettered" value={jobCounts.DEAD_LETTERED ?? 0} />
            <HealthMetric label="Stale Running" value={staleRunningJobs.length} />
            <HealthMetric label="Worker Concurrency" value={health.workerConcurrency ?? 0} />
            <HealthMetric label="K8s Max Active" value={health.maxActiveKubernetesJobs ?? "n/a"} />
            <HealthMetric label="Worker Enabled" value={health.workerEnabled ? "Yes" : "No"} />
          </div>

          <div className="system-health-meta">
            <span>Queue: {health.queueName || "unknown"}</span>
            <span>Namespace: {health.kubernetesNamespace || "unknown"}</span>
            {!redisOnline && health.redisError && <span>Redis error: {health.redisError}</span>}
          </div>

          {staleRunningJobs.length > 0 && (
            <div className="system-health-list">
              <h3>Stale Running Jobs</h3>
              {staleRunningJobs.map((job) => (
                <div key={job.id} className="system-health-job">
                  <span>#{job.id}</span>
                  <span>{job.graderType || "unknown"}</span>
                  <span>{job.workerId || "unclaimed"}</span>
                  <span>
                    Attempt {job.attemptCount ?? 0}/{job.maxAttempts ?? 0}
                  </span>
                </div>
              ))}
            </div>
          )}

          {recentDeadLetteredJobs.length > 0 && (
            <div className="system-health-list">
              <h3>Recent Dead-Lettered Jobs</h3>
              {recentDeadLetteredJobs.slice(0, 3).map((job) => (
                <div key={job.id} className="system-health-job">
                  <span>#{job.id}</span>
                  <span>{job.graderType || "unknown"}</span>
                  <span>{job.institutionId || "unknown"}</span>
                  <span>{job.submittedBy || "unknown"}</span>
                </div>
              ))}
            </div>
          )}
        </>
      )}
    </section>
  );
}

function HealthMetric({ label, value }) {
  return (
    <div className="system-health-metric">
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  );
}

function normalizeQueuedHandoff(state) {
  const queuedJobs = Array.isArray(state?.queuedJobs) ? state.queuedJobs : [];
  if (queuedJobs.length === 0) {
    return null;
  }

  return {
    jobs: queuedJobs,
    queuedAt: state?.queuedAt,
    graderType: state?.graderType
  };
}

function formatQueuedTitle(count) {
  return count === 1 ? "Queued 1 job" : `Queued ${count} jobs`;
}

function formatQueuedSubtitle(handoff) {
  const names = handoff.jobs
    .map((job) => job.fileName)
    .filter(Boolean);

  if (names.length === 0) {
    return "Background grading has started.";
  }

  const visibleNames = names.slice(0, 3).join(", ");
  const remaining = names.length - 3;
  return remaining > 0 ? `${visibleNames}, +${remaining} more` : visibleNames;
}
