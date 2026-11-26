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

async function unsubscribeBrowser() {
  if (!("serviceWorker" in navigator)) return;
  const registration = await navigator.serviceWorker.getRegistration("/");
  const subscription = await registration?.pushManager?.getSubscription();
  if (subscription) await subscription.unsubscribe();
}

export function clearToken({ disconnectBrowser = true } = {}) {
  // Stop delivery to this browser when its signed-in account changes or signs out.
  if (disconnectBrowser) unsubscribeBrowser().catch(() => {});
  try {
    sessionStorage.removeItem(tokenKey);
    sessionStorage.removeItem(nameKey);
  } catch {
    // Storage can be unavailable in some browser settings.
  }
}

export async function endSession() {
  try {
    if ("serviceWorker" in navigator) {
      const registration = await navigator.serviceWorker.getRegistration("/");
      const subscription = await registration?.pushManager?.getSubscription();
      if (subscription) {
        try { await disconnectPush(subscription.endpoint); } catch { /* Still remove the local subscription. */ }
        await subscription.unsubscribe();
      }
    }
  } catch { /* Sign out even when browser notification APIs are unavailable. */ }
  finally { clearToken({ disconnectBrowser: false }); }
}

export function requireLogin() {
  if (getToken()) return true;
  location.replace("/index.html");
  return false;
}

function responseErrorMessage(data, status) {
  const message = [data?.error, data?.error?.message, data?.message]
    .find((value) => typeof value === "string" && value.trim());
  if (message) return message;
  if (status === 401) return "Sign-in could not be verified. Try again.";
  return "The request failed. Try again.";
}

async function request(path, { method = "GET", body, protectedCall = false, signal } = {}) {
  const headers = { Accept: "application/json" };
  if (protectedCall) {
    const token = getToken();
    if (!token) {
      location.replace("/index.html");
      throw new Error("Sign in to continue.");
    }
    headers.Authorization = "Bearer " + token;
  }
  if (body !== undefined) headers["Content-Type"] = "application/json";

  let response;
  try {
    response = await fetch(path, { method, headers, body: JSON.stringify(body), signal });
  } catch {
    throw new Error("Could not reach Softnote. Try again.");
  }
  const data = await response.json().catch(() => null);
  if (!response.ok) {
    if (protectedCall && response.status === 401) {
      // An expired login does not opt the user out of their scheduled reminders.
      clearToken({ disconnectBrowser: false });
      const destination = new URL("/index.html", location.origin);
      destination.searchParams.set("expired", "1");
      location.replace(destination);
    }
    throw new Error(responseErrorMessage(data, response.status));
  }
  if (data === null) throw new Error("Softnote returned an unreadable response.");
  return data;
}

export function getMe() { return request("/me", { protectedCall: true }); }
export function onlineExams(admin = false) { return request(admin ? '/admin/online-exams' : '/online-exams', { protectedCall: true }); }
export function publishOnlineExam(paper) { return request('/admin/online-exams', { method: 'POST', body: paper, protectedCall: true }); }
export function onlineExamReport(id) { return request('/admin/online-exams/' + encodeURIComponent(id) + '/report', { protectedCall: true }); }
export function viewOnlineExam(id) { return request('/online-exams/' + encodeURIComponent(id), { protectedCall: true }); }
export function startOnlineExam(id) { return request('/online-exams/' + encodeURIComponent(id) + '/start', { method: 'POST', body: {}, protectedCall: true }); }
export function saveOnlineAnswers(id, answers) { return request('/online-exams/' + encodeURIComponent(id) + '/answers', { method: 'PUT', body: { answers }, protectedCall: true }); }
export function submitOnlineExam(id, answers) { return request('/online-exams/' + encodeURIComponent(id) + '/submit', { method: 'POST', body: { answers }, protectedCall: true }); }
export function getReminders() { return request("/reminders", { protectedCall: true }); }
export function saveReminders(settings) { return request("/reminders/settings", { method: "PUT", body: settings, protectedCall: true }); }
export function connectPush(subscription) { return request("/reminders/subscription", { method: "POST", body: {
  endpoint: subscription.endpoint, p256dh: subscription.keys.p256dh, auth: subscription.keys.auth,
}, protectedCall: true }); }
export function disconnectPush(endpoint) { return request("/reminders/subscription", { method: "DELETE", body: { endpoint }, protectedCall: true, signal: AbortSignal.timeout(5000) }); }
export function testReminder() { return request("/reminders/test", { method: "POST", body: {}, protectedCall: true }); }
export function readReminder(id) { return request("/reminders/" + encodeURIComponent(id) + "/read", { method: "PATCH", body: {}, protectedCall: true }); }
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
  // Rebind an existing browser subscription to this login before showing any account data.
  // Switching accounts cannot leave reminders assigned to the previous account.
  let subscription;
  try {
    const registration = "serviceWorker" in navigator ? await navigator.serviceWorker.getRegistration("/") : null;
    subscription = await registration?.pushManager?.getSubscription();
    if (subscription) await connectPush(subscription.toJSON());
  } catch {
    if (subscription) await subscription.unsubscribe().catch(() => {});
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

export function deleteExam(examId) {
  return request("/exams/" + encodeURIComponent(examId), { method: "DELETE", protectedCall: true });
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
