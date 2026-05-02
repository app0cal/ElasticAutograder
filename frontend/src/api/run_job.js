const API_BASE = "http://localhost:8080/api";

export async function runJob(jobId, fileName) {
  const response = await fetch(`${API_BASE}/jobs/run/${jobId}`, {
    method: "POST",
    body: fileName
  })

  if (!response.ok) throw new Error("Unable to run job");
  return response.json();
}
