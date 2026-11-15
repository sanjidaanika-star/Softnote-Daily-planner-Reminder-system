import { clearToken, createExam, getExam, getHistory, getPlan, getProgress, listExams, login, register, requireLogin, updateExam, updateSession } from "./api.js";

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
    const today = new Intl.DateTimeFormat("sv-SE").format(new Date());
    const upcoming = exams.filter((exam) => exam.examDate >= today);
    const next = upcoming.reduce((earliest, exam) =>
      earliest == null || exam.examDate < earliest.examDate ? exam : earliest, null);
    byId("exam-total").textContent = exams.length;
    byId("exam-upcoming").textContent = upcoming.length;
    byId("next-exam").textContent = next?.examDate ?? "None scheduled";
    byId("exam-summary").hidden = false;
    status.textContent = exams.length === 0 ? "Add your first exam to create a study plan." : "";
    status.hidden = exams.length > 0;
    byId("exam-list").replaceChildren();
    for (const exam of exams) {
      const item = document.createElement("li");
      const query = "?examId=" + encodeURIComponent(exam.id);
      const title = document.createElement("h3");
      title.textContent = exam.title;
      const meta = document.createElement("p");
      meta.className = "exam-meta";
      meta.textContent = exam.examDate + " · " + exam.dailyMinutes + " min/day";
      const navigation = document.createElement("div");
      navigation.className = "exam-navigation";
      const link = document.createElement("a");
      link.href = "/plan.html" + query;
      link.className = "primary-button";
      link.textContent = "Study plan";
      const progress = document.createElement("a");
      progress.href = "/progress.html" + query;
      progress.textContent = "Progress & history";
      const actions = document.createElement("details");
      const summary = document.createElement("summary");
      summary.textContent = "Actions";
      summary.setAttribute("aria-label", "Actions for " + exam.title);
      const edit = document.createElement("a");
      edit.href = "/new-exam.html" + query;
      edit.textContent = "Edit exam";
      actions.append(summary, edit);
      navigation.append(link, progress);
      item.append(title, meta, navigation, actions);
      byId("exam-list").append(item);
    }
  } catch (failure) {
    status.textContent = "";
    showError(failure);
  }
}

async function setupExam(form) {
  const chapters = byId("chapters");
  const editing = new URLSearchParams(location.search).has("examId");
  let examId;
  function updateRemoveButtons() {
    for (const button of chapters.querySelectorAll(".remove-chapter")) {
      button.disabled = chapters.children.length === 1;
    }
  }

  function addChapter(focus, chapter) {
    const row = byId("chapter-template").content.firstElementChild.cloneNode(true);
    if (chapter) {
      row.dataset.chapterId = chapter.id;
      row.querySelector('[name="chapterName"]').value = chapter.name;
      row.querySelector('[name="difficulty"]').value = chapter.difficulty;
      row.querySelector('[name="estimatedMinutes"]').value = chapter.estimatedMinutes;
    }
    row.querySelector(".remove-chapter").addEventListener("click", () => {
      row.remove();
      updateRemoveButtons();
      byId("add-chapter").focus();
    });
    chapters.append(row);
    updateRemoveButtons();
    if (focus) row.querySelector("input").focus();
  }

  if (editing) {
    form.hidden = true;
    document.querySelector("main h1").textContent = "Edit exam";
    document.title = "Edit exam | Softnote";
    try {
      examId = selectedExam();
      const exam = await getExam(examId);
      for (const key of ["title", "examDate", "dailyMinutes"]) form.elements.namedItem(key).value = exam[key];
      exam.chapters.forEach((chapter) => addChapter(false, chapter));
      form.querySelector('[type="submit"]').textContent = "Save changes";
      form.hidden = false;
    } catch (failure) {
      showError(failure);
      return;
    }
  }
  if (!chapters.children.length) addChapter(false);
  byId("add-chapter").addEventListener("click", () => addChapter(true));
  handleSubmit(form, async (fields) => {
    const payload = {
      title: fields.title,
      examDate: fields.examDate,
      dailyMinutes: Number(fields.dailyMinutes),
      chapters: Array.from(chapters.children, (row) => ({
        id: row.dataset.chapterId ? Number(row.dataset.chapterId) : undefined,
        name: row.querySelector('[name="chapterName"]').value,
        difficulty: row.querySelector('[name="difficulty"]').value,
        estimatedMinutes: Number(row.querySelector('[name="estimatedMinutes"]').value),
      })),
    };
    const exam = editing ? await updateExam(examId, payload) : await createExam(payload);
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

const statusLabels = { planned: "Planned", completed: "Completed", missed: "Missed" };

function addSessionEditor(revisionCell, actions, session, table, pending) {
  const form = document.createElement("form");
  form.className = "session-editor";
  form.id = "session-editor-" + session.id;
  form.dataset.sessionId = session.id;
  const select = document.createElement("select");
  select.setAttribute("aria-label", "Status for " + session.chapterName + " on " + session.sessionDate);
  for (const [value, label] of Object.entries(statusLabels)) {
    select.add(new Option(label, value));
  }
  select.name = "status";
  select.value = pending?.status ?? session.status;

  const revision = document.createElement("select");
  revision.className = "session-revision";
  revision.name = "revision";
  revision.setAttribute("form", form.id);
  revision.setAttribute("aria-label", "Revision for " + session.chapterName + " on " + session.sessionDate);
  revision.add(new Option("Yes", "true"));
  revision.add(new Option("No", "false"));
  revision.value = pending?.revision ?? String(session.isRevision);

  const save = document.createElement("button");
  save.type = "submit";
  save.textContent = "Save";
  const updateSave = () => {
    save.disabled = select.value === session.status && revision.value === String(session.isRevision);
  };
  select.addEventListener("change", updateSave);
  revision.addEventListener("change", updateSave);
  updateSave();

  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    if (save.disabled) return;
    error.hidden = true;
    const changes = {};
    if (select.value !== session.status) changes.status = select.value;
    if (revision.value !== String(session.isRevision)) changes.isRevision = revision.value === "true";
    const controls = Array.from(table.querySelectorAll(
      ".session-editor :is(select, button):enabled, .session-revision:enabled"));
    controls.forEach((control) => { control.disabled = true; });
    try {
      await updateSession(session.id, changes);
      await loadPlan(session.id);
    } catch (failure) {
      showError(failure);
    } finally {
      controls.forEach((control) => { control.disabled = false; });
    }
  });
  form.append(select, save);
  revisionCell.append(revision);
  actions.append(form);
}

async function loadPlan(focusSessionId) {
  const status = byId("plan-status");
  const table = byId("plan-table");
  try {
    const examId = selectedExam();
    byId("progress-link").href = "/progress.html?examId=" + encodeURIComponent(examId);
    const { sessions, unscheduled } = await getPlan(examId);
    const editLink = byId("edit-exam-link");
    editLink.href = "/new-exam.html?examId=" + encodeURIComponent(examId);
    editLink.hidden = false;
    status.textContent = sessions.length === 0 ? "There are no study sessions for this exam."
      : focusSessionId == null ? "" : "Session changes saved.";
    const rows = table.tBodies[0];
    const pendingEdits = new Map(Array.from(rows.querySelectorAll(".session-editor"), (form) => [
      form.dataset.sessionId, {
        status: form.elements.namedItem("status").value,
        revision: form.elements.namedItem("revision").value,
      },
    ]));
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
        statusLabels[session.status]]);
      const revisionCell = row.insertCell();
      const actions = row.insertCell();
      if (session.status === "planned") {
        addSessionEditor(revisionCell, actions, session, table, pendingEdits.get(String(session.id)));
      } else {
        revisionCell.textContent = session.isRevision ? "Yes" : "No";
        actions.textContent = "—";
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
    if (event.persisted && requireLogin()) {
      if (byId("exam-list")) loadDashboard();
      if (byId("plan-table")) loadPlan();
      if (byId("history-table")) location.reload();
    }
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
