const API_BASE = "http://localhost:8080/api";

export async function removeFile(fileName) {
  const response = await fetch(`${API_BASE}/files/remove`, {
    method: "DELETE",
    body: fileName
  });

  if (!response.ok) throw new Error(response);
  return response;
}
