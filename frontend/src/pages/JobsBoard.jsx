import { useEffect, useMemo, useRef, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { fetchRecentJobs, fetchGraders } from "../api/jobs";
import JobsTable from "../components/JobsTable";
import MockIdentityPanel from "../components/MockIdentityPanel";
import { subscribeToMockIdentityChanges } from "../api/mock_identity";

const REFRESH_INTERVAL = 1000;

export default function JobsBoard() {
  const navigate = useNavigate();
  const gridRef = useRef(null);
  const refreshInterval = useRef(null);

  const [jobs, setJobs] = useState([]);
  const [graders, setGraders] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [showSummary, setShowSummary] = useState(false);

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

  useEffect(() => {
    load(true);

    refreshInterval.current = setInterval(() => {
      load(false);
    }, REFRESH_INTERVAL);

    const unsubscribe = subscribeToMockIdentityChanges(() => load(true));

    return () => {
      clearInterval(refreshInterval.current);
      unsubscribe();
    };
  }, []);

  const summary = useMemo(() => {
    const activeJobs = jobs.filter((job) => job.status === "QUEUED" || job.status === "RUNNING").length;
    const failedJobs = jobs.filter((job) => job.status === "FAILED").length;
    const partialJobs = jobs.filter((job) => job.status === "PARTIAL").length;
    const invalidUploads = jobs.filter((job) => job.failureReason === "INVALID_UPLOAD").length;
    const succeededJobs = jobs.filter((job) => job.status === "SUCCEEDED").length;
    const successRate = jobs.length > 0 ? Math.round((succeededJobs / jobs.length) * 100) : 0;

    return [
      { label: "Total Recent Jobs", value: jobs.length },
      { label: "Queued + Running", value: activeJobs },
      { label: "Failed Jobs", value: failedJobs },
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
