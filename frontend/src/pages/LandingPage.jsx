import { useNavigate } from "react-router-dom";
import "../styles/LandingPage.css";
import {LightningIcon, ShieldIcon, ChartIcon} from "../components/Icons.jsx";


export default function LandingPage() {
  const navigate = useNavigate();

  const workflowSteps = [
    {
      step: "1",
      title: "Upload Submission",
      text: "Course staff upload a student submission through the web interface.",
    },
    {
      step: "2",
      title: "Select Grader",
      text: "Choose the grader type so the backend can route the submission to the correct grading image.",
    },
    {
      step: "3",
      title: "Run in Kubernetes",
      text: "The backend creates an isolated grading job that executes the submission in a containerized environment.",
    },
    {
      step: "4",
      title: "Review Results",
      text: "Track job status changes and inspect outcomes directly from the jobs view.",
    },
  ];

  const supportedGraders = [
    {
      title: "Fibonacci",
      text: "A classic problem that compares naive recursion, memoization, and iterative dynamic programming.",
    },
    {
      title: "Two Sum",
      text: "A common interview problem that tests array logic, hash map use, and exact output correctness.",
    },
  ];

  return (
    <div className="landing-page">
      <div className="landing-hero">
        <h1 className="landing-title">Elastic Autograder</h1>

        <p className="landing-subtitle">
          A scalable, automated grading pipeline for course staff. Upload
          submissions, run graders, and track job status and results in real
          time.
        </p>

        <div className="landing-feature-grid">
          <div className="landing-card">
            <div className="landing-card-icon">
              <LightningIcon />
            </div>
            <h3>Fast Grading</h3>
            <p>
              Jobs move automatically through queued, running, and completed
              states with live status updates.
            </p>
          </div>

          <div className="landing-card">
            <div className="landing-card-icon">
              <ShieldIcon />
            </div>
            <h3>Secure Execution</h3>
            <p>
              Student code runs in isolated containers, helping protect the host
              system during grading.
            </p>
          </div>

          <div className="landing-card">
            <div className="landing-card-icon">
              <ChartIcon />
            </div>
            <h3>Real-Time Results</h3>
            <p>
              View job progress, grading outcomes, and failure states as backend
              execution updates are recorded.
            </p>
          </div>
        </div>

        <div className="landing-actions">
          <button
            className="button landing-primary-button"
            onClick={() => navigate("/jobs")}
          >
            Get Started
          </button>

          <a href="#how-it-works" className="landing-more-details">
            More details below ↓
          </a>
        </div>
      </div>

      <section id="how-it-works" className="landing-section">
        <h2 className="landing-section-title">How It Works</h2>

        <div className="workflow-grid">
          {workflowSteps.map((item) => (
            <div key={item.step} className="workflow-card">
              <div className="workflow-step">{item.step}</div>
              <h3>{item.title}</h3>
              <p>{item.text}</p>
            </div>
          ))}
        </div>
      </section>

      <section className="landing-section">
        <h2 className="landing-section-title">Supported Graders</h2>

        <div className="graders-grid">
          {supportedGraders.map((grader) => (
            <div key={grader.title} className="grader-card">
              <h3>{grader.title}</h3>
              <p>{grader.text}</p>
            </div>
          ))}
        </div>
      </section>
    </div>
  );
}