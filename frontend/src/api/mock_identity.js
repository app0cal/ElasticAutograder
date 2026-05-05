const STORAGE_KEY = "elastic-autograder.mockIdentity";
const IDENTITY_CHANGED_EVENT = "mock-identity-changed";

const DEFAULT_IDENTITY = {
  institution: "local",
  user: "anonymous"
};

export function getMockIdentity() {
  try {
    const stored = JSON.parse(localStorage.getItem(STORAGE_KEY));
    return normalizeIdentity(stored);
  } catch {
    return DEFAULT_IDENTITY;
  }
}

export function setMockIdentity(identity) {
  const normalized = normalizeIdentity(identity);
  localStorage.setItem(STORAGE_KEY, JSON.stringify(normalized));
  window.dispatchEvent(new CustomEvent(IDENTITY_CHANGED_EVENT, { detail: normalized }));
  return normalized;
}

export function getMockIdentityHeaders() {
  const identity = getMockIdentity();
  return {
    "X-Mock-Institution": identity.institution,
    "X-Mock-User": identity.user
  };
}

export function subscribeToMockIdentityChanges(listener) {
  const handleChange = (event) => listener(event.detail ?? getMockIdentity());
  window.addEventListener(IDENTITY_CHANGED_EVENT, handleChange);
  window.addEventListener("storage", handleChange);
  return () => {
    window.removeEventListener(IDENTITY_CHANGED_EVENT, handleChange);
    window.removeEventListener("storage", handleChange);
  };
}

function normalizeIdentity(identity) {
  return {
    institution: cleanValue(identity?.institution, DEFAULT_IDENTITY.institution),
    user: cleanValue(identity?.user, DEFAULT_IDENTITY.user)
  };
}

function cleanValue(value, fallback) {
  return typeof value === "string" && value.trim() ? value.trim() : fallback;
}
