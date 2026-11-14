import { changeStatus, clearToken, createExam, getHistory, getPlan, getProgress, listExams, login, register, requireLogin } from "./api.js";

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

function selectedExam() {
  const examId = new URLSearchParams(location.search).get("examId");
  if (!examId || !/^[1-9][0-9]*$/.test(examId)) throw new Error("Choose an exam from the dashboard.");
  return examId;
}

function addCells(row, values) {
  for (const value of values) row.insertCell().textContent = value;
}

async function loadPlan(focusSessionId) {
  const status = byId("plan-status");
  const table = byId("plan-table");
  try {
    const examId = selectedExam();
    byId("progress-link").href = "/progress.html?examId=" + encodeURIComponent(examId);
    const { sessions, unscheduled } = await getPlan(examId);
    status.textContent = sessions.length === 0 ? "There are no study sessions for this exam." : "";
    const rows = table.tBodies[0];
    rows.replaceChildren();
    const list = byId("unscheduled-list");
    list.replaceChildren();
    for (const item of unscheduled) {
      const line = document.createElement("li");
      line.textContent = item.chapterName + " · " + item.minutes + " min";
      list.append(line);
    }
    byId("unscheduled-status").textContent = unscheduled.length ? "" : "All work is scheduled.";

    for (const session of sessions) {
      const row = rows.insertRow();
      addCells(row, [session.sessionDate, session.chapterName, session.plannedMinutes,
        session.status, session.isRevision ? "Yes" : "No"]);
      const actions = row.insertCell();
      if (session.status === "planned") {
        for (const [label, value] of [["Completed", "completed"], ["Missed", "missed"]]) {
          const button = document.createElement("button");
          button.type = "button";
          button.textContent = label;
          button.addEventListener("click", async () => {
            error.hidden = true;
            const buttons = table.querySelectorAll("button");
            buttons.forEach((item) => { item.disabled = true; });
            try {
              await changeStatus(session.id, value);
              await loadPlan(session.id);
            } catch (failure) {
              showError(failure);
            } finally {
              buttons.forEach((item) => { item.disabled = false; });
            }
          });
          actions.append(button);
        }
      }
      if (session.id === focusSessionId) row.tabIndex = -1;
    }
    table.hidden = sessions.length === 0;
    rows.querySelector('[tabindex="-1"]')?.focus();
  } catch (failure) {
    status.textContent = "";
    showError(failure);
  }
}

async function loadProgress() {
  const progressStatus = byId("progress-status");
  try {
    const examId = selectedExam();
    const back = byId("back-to-plan");
    back.href = "/plan.html?examId=" + encodeURIComponent(examId);
    back.textContent = "Back to plan";
    const [progress, history] = await Promise.all([getProgress(examId), getHistory()]);
    progressStatus.textContent = progress.percentage + "% complete (" + progress.completedMinutes
      + " of " + progress.totalPlannedMinutes + " min)";
    const table = byId("history-table");
    for (const session of history) {
      addCells(table.tBodies[0].insertRow(), [session.sessionDate, session.examTitle,
        session.chapterName, session.plannedMinutes, session.status]);
    }
    table.hidden = history.length === 0;
    byId("history-status").textContent = history.length ? "" : "No completed or missed sessions yet.";
  } catch (failure) {
    progressStatus.textContent = "";
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
  if (byId("history-table")) loadProgress();
}

if (byId("login-form")) setupLogin(byId("login-form"));
if (byId("register-form")) {
  handleSubmit(byId("register-form"), async (details) => {
    await register(details);
    location.replace("/login.html?registered=1");
  });
}
