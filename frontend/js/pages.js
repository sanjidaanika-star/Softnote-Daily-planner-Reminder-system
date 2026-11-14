import { clearToken, createExam, getPlan, listExams, login, register, requireLogin } from "./api.js";

const byId = (id) => document.getElementById(id);
const error = byId("error");

function showError(failure) {
  error.textContent = failure.message;
  error.hidden = false;
}

function handleSubmit(form, submit) {
  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    const button = form.querySelector('button[type="submit"]');
    button.disabled = true;
    error.hidden = true;
    try {
      await submit(Object.fromEntries(new FormData(form)));
    } catch (failure) {
      showError(failure);
    } finally {
      button.disabled = false;
    }
  });
}

function setupLogin(form) {
  const params = new URLSearchParams(location.search);
  const notice = byId("notice");
  if (params.has("registered")) {
    notice.textContent = "Account created. Sign in to continue.";
    notice.hidden = false;
  }
  if (params.has("expired")) {
    showError(new Error("A valid login token is required. Sign in again."));
  }
  handleSubmit(form, async (credentials) => {
    await login(credentials);
    location.replace("/dashboard.html");
  });
}

async function loadDashboard() {
  const status = byId("exam-status");
  try {
    const exams = await listExams();
    status.textContent = exams.length === 0 ? "You have no exams yet." : "";
    for (const exam of exams) {
      const item = document.createElement("li");
      const link = document.createElement("a");
      link.href = "/plan.html?examId=" + encodeURIComponent(exam.id);
      link.textContent = exam.title;
      item.append(link, " · " + exam.examDate + " · " + exam.dailyMinutes + " min/day");
      byId("exam-list").append(item);
    }
  } catch (failure) {
    status.textContent = "";
    showError(failure);
  }
}

function setupExam(form) {
  const chapters = byId("chapters");
  function updateRemoveButtons() {
    for (const button of chapters.querySelectorAll(".remove-chapter")) {
      button.disabled = chapters.children.length === 1;
    }
  }

  function addChapter(focus) {
    const row = byId("chapter-template").content.firstElementChild.cloneNode(true);
    row.querySelector(".remove-chapter").addEventListener("click", () => {
      row.remove();
      updateRemoveButtons();
      byId("add-chapter").focus();
    });
    chapters.append(row);
    updateRemoveButtons();
    if (focus) row.querySelector("input").focus();
  }

  addChapter(false);
  byId("add-chapter").addEventListener("click", () => addChapter(true));
  handleSubmit(form, async (fields) => {
    const exam = await createExam({
      title: fields.title,
      examDate: fields.examDate,
      dailyMinutes: Number(fields.dailyMinutes),
      chapters: Array.from(chapters.children, (row) => ({
        name: row.querySelector('[name="chapterName"]').value,
        difficulty: row.querySelector('[name="difficulty"]').value,
        estimatedMinutes: Number(row.querySelector('[name="estimatedMinutes"]').value),
      })),
    });
    location.assign("/plan.html?examId=" + encodeURIComponent(exam.id));
  });
}

async function loadPlan() {
  const status = byId("plan-status");
  try {
    const examId = new URLSearchParams(location.search).get("examId");
    if (!examId || !/^[1-9][0-9]*$/.test(examId)) throw new Error("Choose an exam from the dashboard.");
    const sessions = await getPlan(examId);
    status.textContent = sessions.length === 0 ? "There are no study sessions for this exam." : "";
    if (sessions.length === 0) return;

    const table = byId("plan-table");
    const rows = table.tBodies[0];
    for (const session of sessions) {
      const row = rows.insertRow();
      const cells = [session.sessionDate, session.chapterName, session.plannedMinutes,
        session.status, session.isRevision ? "Yes" : "No"];
      for (const value of cells) row.insertCell().textContent = value;
    }
    table.hidden = false;
  } catch (failure) {
    status.textContent = "";
    showError(failure);
  }
}

const signOut = byId("sign-out");
if (signOut && requireLogin()) {
  signOut.addEventListener("click", () => {
    clearToken();
    location.replace("/login.html");
  });
  window.addEventListener("pageshow", (event) => {
    if (event.persisted) requireLogin();
  });
  if (byId("exam-list")) loadDashboard();
  if (byId("exam-form")) setupExam(byId("exam-form"));
  if (byId("plan-table")) loadPlan();
}

if (byId("login-form")) setupLogin(byId("login-form"));
if (byId("register-form")) {
  handleSubmit(byId("register-form"), async (details) => {
    await register(details);
    location.replace("/login.html?registered=1");
  });
}
