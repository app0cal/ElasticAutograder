const API_BASE = "http://localhost:8080/api";

export async function downloadResults(jobId) {
  const response = await fetch(`${API_BASE}/jobs/result/${jobId}`);
  if (!response.ok) {
    const errorBody = await response.text();
    throw new Error(errorBody || `Failed to download results for job ${jobId}.`);
  }

  return response.blob();
}
