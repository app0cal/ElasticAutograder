import { getMockIdentityHeaders } from "./mock_identity";
import { API_BASE } from "./config";

export async function downloadResults(jobId) {
  const response = await fetch(`${API_BASE}/jobs/result/${jobId}`, {
    headers: getMockIdentityHeaders()
  });
  if (!response.ok) {
    const errorBody = await response.text();
    throw new Error(errorBody || `Failed to download results for job ${jobId}.`);
  }

  return response.blob();
}
