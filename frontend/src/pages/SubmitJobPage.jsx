import { Link, useNavigate } from "react-router-dom";
import { useEffect, useRef, useState } from "react";
import { uploadFile } from "../api/upload_file";
import { fetchGraders } from "../api/jobs";
import MockIdentityPanel from "../components/MockIdentityPanel";
import { subscribeToMockIdentityChanges } from "../api/mock_identity";

export default function SubmitJobPage() {
  const [file, setFile] = useState(null);
  const [status, setStatus] = useState("");
  const [graders, setGraders] = useState([]);
  const [selectedGrader, setSelectedGrader] = useState("");
  const [isSubmitting, setIsSubmitting] = useState(false);
  const fileInputRef = useRef(null);
  const navigate = useNavigate();

  const selectedGraderInfo = graders.find(
    (grader) => grader.key === selectedGrader
  );
  const acceptedFormat = getAcceptedFormat(selectedGraderInfo);
  const isReadyToSubmit = Boolean(selectedGrader && file);
  const progressSteps = [
    { label: "Grader", isComplete: Boolean(selectedGrader), isActive: !selectedGrader },
    { label: "File", isComplete: Boolean(file), isActive: Boolean(selectedGrader && !file) },
    { label: "Submit", isComplete: false, isActive: isReadyToSubmit || isSubmitting }
  ];

  useEffect(() => {
    const loadGraders = async () => {
      try {
        setGraders(await fetchGraders());
      } catch (err) {
        setStatus(err.message);
      }
    };

    loadGraders();
    return subscribeToMockIdentityChanges(() => {
      setSelectedGrader("");
      loadGraders();
    });
  }, []);

  const handleFileChange = (e) => {
    const selectedFile = e.target.files[0] ?? null;
    if (selectedFile && !isFileAllowed(selectedFile, acceptedFormat)) {
      setFile(null);
      setStatus(acceptedFormat.errorMessage);
      if (fileInputRef.current) {
        fileInputRef.current.value = "";
      }
      return;
    }

    setFile(selectedFile);
    setStatus("");
  };

  const handleGraderChange = (e) => {
    const nextGrader = e.target.value;
    const nextGraderInfo = graders.find((grader) => grader.key === nextGrader);
    const nextAcceptedFormat = getAcceptedFormat(nextGraderInfo);
    setSelectedGrader(nextGrader);
    if (file && !isFileAllowed(file, nextAcceptedFormat)) {
      setFile(null);
      if (fileInputRef.current) {
        fileInputRef.current.value = "";
      }
    }
    setStatus("");
  };

  const handleDrop = (e) => {
    e.preventDefault();

    if (isSubmitting) {
      return;
    }

    const droppedFile = e.dataTransfer.files[0] ?? null;
    if (droppedFile && !isFileAllowed(droppedFile, acceptedFormat)) {
      setFile(null);
      setStatus(acceptedFormat.errorMessage);
      return;
    }

    setFile(droppedFile);
    setStatus("");
  };

  const handleClear = () => {
    setFile(null);
    setSelectedGrader("");
    setStatus("");

    if (fileInputRef.current) {
      fileInputRef.current.value = "";
    }
  };

  const handleSubmit = async () => {
    if (!file) {
      setStatus("Please select a file to upload.");
      return;
    }

    if (!selectedGrader) {
      setStatus("Please select a grader.");
      return;
    }

    if (!isFileAllowed(file, acceptedFormat)) {
      setStatus(acceptedFormat.errorMessage);
      return;
    }

    try {
      setIsSubmitting(true);
      setStatus("Uploading for background grading...");

      const uploadResponse = await uploadFile(file, selectedGrader);
      const jobs = Array.isArray(uploadResponse.jobs) ? uploadResponse.jobs : [];

      if (jobs.length === 0) {
        throw new Error("Upload did not return any jobs.");
      }

      setStatus(
        jobs.length === 1
          ? "Queued for background grading."
          : `Batch queued with ${jobs.length} jobs.`
      );

      navigate("/jobs", {
        state: {
          queuedJobs: jobs,
          queuedAt: new Date().toISOString(),
          graderType: selectedGrader
        }
      });
    } catch (err) {
      setStatus(err.message || "Failed to submit job.");
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <div className="submit-page">
      <div className="submit-shell">
        <div className="submit-header">
          <div className="submit-header-text">
            <h1 className="page-title">New Submission</h1>
            <p className="submit-subtitle">Run a grader against a source file or batch archive.</p>
          </div>
          <Link to="/jobs" className="button nav-button">
            Recent Jobs
          </Link>
        </div>

        <div className="submit-console-grid">
          <section className="submit-console-card submit-form-panel">
            <div className="submit-progress" aria-label="Submission progress">
              {progressSteps.map((step, index) => (
                <div
                  className={[
                    "submit-progress-step",
                    step.isComplete ? "is-complete" : "",
                    step.isActive ? "is-active" : ""
                  ].filter(Boolean).join(" ")}
                  key={step.label}
                >
                  <span className="submit-progress-dot">
                    {step.isComplete ? <CheckIcon /> : index + 1}
                  </span>
                  <span className="submit-progress-label">{step.label}</span>
                </div>
              ))}
            </div>

            <div className="submit-section-heading">
              <h2>Submission</h2>
            </div>

            <div className="form-group">
              <label className="label" htmlFor="grader-select">
                Grader
              </label>
              <select
                id="grader-select"
                className="input submit-select"
                value={selectedGrader}
                onChange={handleGraderChange}
                disabled={isSubmitting}
              >
                <option value="">Select a grader</option>
                {graders.map((grader) => (
                  <option key={grader.key} value={grader.key}>
                    {grader.label}
                  </option>
                ))}
              </select>
            </div>

            <div className="submit-upload-group">
              <span className="label">Upload file</span>
              <label
                className="submit-upload-card"
                htmlFor="submission-file"
                onDragOver={(e) => e.preventDefault()}
                onDrop={handleDrop}
              >
                <span className="submit-upload-icon">
                  <FileIcon />
                </span>
                <span className="submit-upload-title">{acceptedFormat.dropLabel}</span>
                <span className="submit-upload-copy">or browse from your device</span>
                <span className="submit-browse-button">Browse files</span>
                <input
                  id="submission-file"
                  ref={fileInputRef}
                  className="submit-upload-input"
                  type="file"
                  accept={acceptedFormat.accept}
                  onChange={handleFileChange}
                  disabled={isSubmitting}
                />
              </label>
            </div>

            <div className="submit-file-card">
              <span className="submit-file-icon">
                <FileIcon />
              </span>
              <div className="submit-file-text">
                <span className="file-meta-label">Selected file</span>
                <span className="file-meta-name">{file ? file.name : "No file selected"}</span>
              </div>
              <span className="submit-file-size">{file ? formatFileSize(file.size) : acceptedFormat.shortLabel}</span>
            </div>

            <div className="submit-actions">
              <button
                className="button submit-primary-button"
                onClick={handleSubmit}
                disabled={isSubmitting}
                type="button"
              >
                <SendIcon />
                {isSubmitting ? "Submitting..." : "Submit"}
              </button>
              <button
                className="button submit-secondary-button"
                onClick={handleClear}
                disabled={isSubmitting}
                type="button"
              >
                Clear
              </button>
            </div>

            <div className="submit-status-message" aria-live="polite">
              <InfoIcon />
              <span>{status || (isReadyToSubmit ? "Ready to submit." : "Select a grader and file to continue.")}</span>
            </div>
          </section>

          <aside className="submit-console-card submit-assignment-panel">
            <MockIdentityPanel />
            <div className="submit-divider" />

            <div className="submit-section-heading submit-assignment-heading">
              <span className="submit-section-label">Assignment</span>
              <h2>{selectedGraderInfo ? selectedGraderInfo.label : "Select a grader"}</h2>
            </div>

            <p className="submit-assignment-summary">
              {selectedGraderInfo
                ? selectedGraderInfo.summary
                : "Assignment requirements will appear here."}
            </p>

            <div className="submit-divider" />

            <div className="submit-context-block">
              <span className="submit-context-label">Requirements</span>
              {selectedGraderInfo?.details?.length > 0 ? (
                <ul className="submit-requirements-list">
                  {selectedGraderInfo.details.slice(0, 4).map((detail, index) => (
                    <li key={`${selectedGraderInfo.key}-detail-${index}`}>
                      <span className="submit-requirement-icon">
                        <CodeIcon />
                      </span>
                      <span>{detail}</span>
                    </li>
                  ))}
                </ul>
              ) : (
                <div className="submit-muted-row">Choose a grader to see requirements.</div>
              )}
            </div>

            <div className="submit-divider" />

            <div className="submit-context-block">
              <span className="submit-context-label">Accepted formats</span>
              <div className="submit-format-list">
                <span>{acceptedFormat.sourceExtension}</span>
                <span>.zip</span>
              </div>
            </div>

            <div className="submit-divider" />

            <div className="submit-context-block">
              <span className="submit-context-label">Runtime environment</span>
              <div className="submit-runtime">
                <strong>{acceptedFormat.runtimeLabel}</strong>
                <span>Isolated container execution</span>
              </div>
            </div>
          </aside>
        </div>
      </div>
    </div>
  );
}

function isFileAllowed(file, acceptedFormat) {
  if (!file?.name) {
    return false;
  }

  const fileName = file.name.toLowerCase();
  return fileName.endsWith(acceptedFormat.sourceExtension) || fileName.endsWith(".zip");
}

function getAcceptedFormat(grader) {
  const language = (grader?.language || "python").toLowerCase();

  if (language === "java") {
    return buildAcceptedFormat(".java", "Java grading image");
  }

  if (language === "cpp" || language === "c++") {
    return buildAcceptedFormat(".cpp", "C++ grading image");
  }

  return buildAcceptedFormat(".py", "Python grading image");
}

function buildAcceptedFormat(sourceExtension, runtimeLabel) {
  const shortLabel = `${sourceExtension} or .zip`;
  return {
    sourceExtension,
    runtimeLabel,
    accept: `${sourceExtension},.zip`,
    shortLabel,
    dropLabel: `Drop a ${sourceExtension} file or .zip archive here`,
    errorMessage: `This grader accepts ${shortLabel} files.`
  };
}

function formatFileSize(size) {
  if (!Number.isFinite(size)) {
    return "";
  }

  if (size < 1024) {
    return `${size} B`;
  }

  if (size < 1024 * 1024) {
    return `${(size / 1024).toFixed(1)} KB`;
  }

  return `${(size / (1024 * 1024)).toFixed(1)} MB`;
}

function IconBase({ children }) {
  return (
    <svg
      width="18"
      height="18"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      {children}
    </svg>
  );
}

function CheckIcon() {
  return (
    <IconBase>
      <path d="M20 6 9 17l-5-5" />
    </IconBase>
  );
}

function CodeIcon() {
  return (
    <IconBase>
      <path d="m16 18 6-6-6-6" />
      <path d="m8 6-6 6 6 6" />
    </IconBase>
  );
}

function FileIcon() {
  return (
    <IconBase>
      <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
      <path d="M14 2v6h6" />
    </IconBase>
  );
}

function InfoIcon() {
  return (
    <IconBase>
      <circle cx="12" cy="12" r="10" />
      <path d="M12 16v-4" />
      <path d="M12 8h.01" />
    </IconBase>
  );
}

function SendIcon() {
  return (
    <IconBase>
      <path d="m22 2-7 20-4-9-9-4Z" />
      <path d="M22 2 11 13" />
    </IconBase>
  );
}
