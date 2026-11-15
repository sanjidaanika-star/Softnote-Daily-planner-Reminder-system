const tokenKey = "softnote.token";

export function getToken() {
  try {
    return sessionStorage.getItem(tokenKey);
  } catch {
    return null;
  }
}

export function clearToken() {
  try {
    sessionStorage.removeItem(tokenKey);
  } catch {
    // Storage can be unavailable in some browser settings.
  }
}

export function requireLogin() {
  if (getToken()) return true;
  location.replace("/login.html");
  return false;
}

async function request(path, { method = "GET", body, protectedCall = false } = {}) {
  const headers = { Accept: "application/json" };
  if (protectedCall) {
    const token = getToken();
    if (!token) {
      location.replace("/login.html");
      throw new Error("Sign in to continue.");
    }
    headers.Authorization = "Bearer " + token;
  }
  if (body !== undefined) headers["Content-Type"] = "application/json";

  let response;
  try {
    response = await fetch(path, { method, headers, body: JSON.stringify(body) });
  } catch {
    throw new Error("Could not reach Softnote. Try again.");
  }
  const data = await response.json().catch(() => null);
  if (!response.ok) {
    if (protectedCall && response.status === 401) {
      clearToken();
      const destination = new URL("/login.html", location.origin);
      destination.searchParams.set("expired", "1");
      location.replace(destination);
    }
    throw new Error(data?.error || "The request failed. Try again.");
  }
  if (data === null) throw new Error("Softnote returned an unreadable response.");
  return data;
}

export function register(details) {
  return request("/register", { method: "POST", body: details });
}

export async function login(credentials) {
  const result = await request("/login", { method: "POST", body: credentials });
  try {
    sessionStorage.setItem(tokenKey, result.token);
  } catch {
    throw new Error("Allow session storage in your browser to sign in.");
  }
  return result;
}

export function listExams() {
  return request("/exams", { protectedCall: true });
}

export function createExam(exam) {
  return request("/exams", { method: "POST", body: exam, protectedCall: true });
}

export function getExam(examId) {
  return request("/exams/" + encodeURIComponent(examId), { protectedCall: true });
}

export function updateExam(examId, exam) {
  return request("/exams/" + encodeURIComponent(examId), {
    method: "PUT", body: exam, protectedCall: true,
  });
}

export function getPlan(examId) {
  return request("/plan?examId=" + encodeURIComponent(examId), { protectedCall: true });
}

export function updateSession(sessionId, changes) {
  return request("/sessions/" + encodeURIComponent(sessionId) + "/status", {
    method: "PATCH", body: changes, protectedCall: true,
  });
}

export function getProgress(examId) {
  return request("/progress?examId=" + encodeURIComponent(examId), { protectedCall: true });
}

export function getHistory() {
  return request("/history", { protectedCall: true });
}
