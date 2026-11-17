const tokenKey = "softnote.token";
const nameKey = "softnote.name";

export function getDisplayName() {
  try {
    return sessionStorage.getItem(nameKey) ?? "";
  } catch {
    return "";
  }
}

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
    sessionStorage.removeItem(nameKey);
  } catch {
    // Storage can be unavailable in some browser settings.
  }
}

export function requireLogin() {
  if (getToken()) return true;
  location.replace("/login.html");
  return false;
}

function responseErrorMessage(data, status) {
  const message = [data?.error, data?.error?.message, data?.message]
    .find((value) => typeof value === "string" && value.trim());
  if (message) return message;
  if (status === 401) return "Sign-in could not be verified. Try again.";
  return "The request failed. Try again.";
}

async function request(path, { method = "GET", body, protectedCall = false, raw, noteTitle, signal } = {}) {
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
  if (raw) {
    headers["Content-Type"] = "application/pdf";
    headers["X-Note-Title"] = encodeURIComponent(noteTitle);
  }

  let response;
  try {
    response = await fetch(path, { method, headers, body: raw ?? JSON.stringify(body), signal });
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
    throw new Error(responseErrorMessage(data, response.status));
  }
  if (data === null) throw new Error("Softnote returned an unreadable response.");
  return data;
}

export function getMe() { return request("/me", { protectedCall: true }); }
export function adminGet(section) { return request("/admin/" + section, { protectedCall: true }); }
export function setAccountActive(id, active) {
  return request("/admin/users/" + id + "/active", { method: "PATCH", body: { active }, protectedCall: true });
}
export function issueReset(id) {
  return request("/admin/resets/" + id + "/issue", { method: "POST", body: {}, protectedCall: true });
}
export function requestPasswordReset(email) {
  return request("/password-reset/request", { method: "POST", body: { email } });
}
export function confirmPasswordReset(token, password) {
  return request("/password-reset/confirm", { method: "POST", body: { token, password } });
}

export function listNotes() {
  return request("/notes", { protectedCall: true });
}

export function uploadNote(file, course = "") {
  const filename = file.name.replace(/\.pdf$/i, "").trim() || "PDF note";
  const title = course ? course + " — " + filename : filename;
  return request("/notes", { method: "POST", protectedCall: true, raw: file, noteTitle: title });
}

export function createQuiz(noteId, count) {
  return request("/notes/" + encodeURIComponent(noteId) + "/quizzes",
    { method: "POST", body: { count }, protectedCall: true });
}

export function getQuiz(id, signal) {
  return request("/quizzes/" + encodeURIComponent(id), { protectedCall: true, signal });
}

export function answerQuestion(id, questionId, chosenIndex) {
  return request("/quizzes/" + encodeURIComponent(id) + "/questions/" + encodeURIComponent(questionId) + "/answer",
    { method: "POST", body: { chosenIndex }, protectedCall: true });
}

export function getQuizScore(id) {
  return request("/quizzes/" + encodeURIComponent(id) + "/score", { protectedCall: true });
}

export function register(details) {
  return request("/register", { method: "POST", body: details });
}

export async function login(credentials) {
  const result = await request("/login", { method: "POST", body: credentials });
  if (typeof result.token !== "string" || !result.token.trim()) {
    throw new Error("Softnote did not return a sign-in token. Try again.");
  }
  try {
    sessionStorage.setItem(tokenKey, result.token);
  } catch {
    throw new Error("Allow session storage in your browser to sign in.");
  }
  try {
    sessionStorage.setItem(nameKey, result.name ?? "");
  } catch {
    // The dashboard can still greet the user without a stored name.
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
