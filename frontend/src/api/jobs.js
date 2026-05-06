import { getMockIdentityHeaders } from "./mock_identity";

const API_BASE = "http://localhost:8080/api";

export async function fetchRecentJobs() {
  const res = await fetch(`${API_BASE}/jobs/recent`, {
    headers: getMockIdentityHeaders()
  });

  if (!res.ok) {
    throw new Error(`Failed to fetch jobs: ${res.status}`);
  }

  return res.json();
}

export async function fetchGraders() {
  const res = await fetch(`${API_BASE}/graders`, {
    headers: getMockIdentityHeaders()
  });

  if (!res.ok) {
    throw new Error(`Failed to fetch graders: ${res.status}`);
  }

  return res.json();
}

export async function fetchJobById(jobId) {
  const res = await fetch(`${API_BASE}/jobs/${jobId}`, {
    headers: getMockIdentityHeaders()
  });

  if (!res.ok) {
    throw new Error(`Failed to fetch job ${jobId}: ${res.status}`);
  }

  return res.json();
}

export async function fetchQueueHealth() {
  const res = await fetch(`${API_BASE}/system/queue-health`, {
    headers: getMockIdentityHeaders()
  });

  if (!res.ok) {
    throw new Error(`Failed to fetch queue health: ${res.status}`);
  }

  return res.json();
}
