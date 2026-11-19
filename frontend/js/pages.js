import { endSession, createExam, deleteExam, getDisplayName, getExam, getHistory, getPlan, getProgress, getReminders, listExams, login, register, requireLogin, updateExam, updateSession } from "./api.js";

import { populateCourses } from "./courses.js";
import { getMe } from "./api.js";
import { countdownText, refreshCountdowns } from "./countdown.js";

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

const DAY = 86_400_000;
const dashboardDate = new Intl.DateTimeFormat(undefined, { dateStyle: "full" });
const shortDate = new Intl.DateTimeFormat(undefined, { month: "short", day: "numeric" });

function dateKey(date) {
  return [date.getFullYear(), String(date.getMonth() + 1).padStart(2, "0"),
    String(date.getDate()).padStart(2, "0")].join("-");
}

function calendarDate(key) {
  return new Date(key + "T00:00:00");
}

function dashNode(tag, content, className) {
  const node = document.createElement(tag);
  if (content != null) node.textContent = content;
  if (className) node.className = className;
  return node;
}

function examLink(exam, path, label, className) {
  const link = dashNode("a", label, className);
  link.href = path + "?examId=" + encodeURIComponent(exam.id);
  link.setAttribute("aria-label", label + " for " + exam.title);
  return link;
}

function populateList(id, items, emptyMessage) {
  byId(id).replaceChildren(...(items.length ? items : [dashNode("li", emptyMessage, "dash-muted")]));
}

function dashboardSvg(tag, attributes) {
  const shape = document.createElementNS("http://www.w3.org/2000/svg", tag);
  for (const [name, value] of Object.entries(attributes)) shape.setAttribute(name, value);
  return shape;
}

function completionRing(percentage) {
  const ring = dashNode("div", null, "dash-ring");
  const svg = dashboardSvg("svg", { viewBox: "0 0 40 40", "aria-hidden": "true" });
  const track = dashboardSvg("circle", { cx: 20, cy: 20, r: 16, class: "ring-track" });
  const fill = dashboardSvg("circle", { cx: 20, cy: 20, r: 16, pathLength: 100,
    "stroke-dasharray": percentage + " 100", transform: "rotate(-90 20 20)" });
  if (percentage > 0) svg.append(track, fill);
  else svg.append(track);
  const value = dashNode("strong", percentage + "%");
  value.setAttribute("aria-label", percentage + "% complete");
  ring.append(svg, value);
  return ring;
}

function examProgress(exam) {
  const bar = dashNode("progress");
  bar.max = 100;
  bar.value = exam.progress.percentage;
  bar.setAttribute("aria-label", "Completion for " + exam.title);
  return bar;
}

function renderToday(exams, today) {
  const items = [];
  for (const exam of exams) {
    for (const session of exam.plan.sessions) {
      if (session.status !== "planned" || session.sessionDate !== today) continue;
      const item = dashNode("li", null, "dash-today-item");
      item.dataset.accent = exam.accent;
      const detail = dashNode("div");
      detail.append(dashNode("strong", session.chapterName),
        dashNode("span", exam.title + " · " + session.plannedMinutes + " min", "dash-muted"));
      const done = dashNode("button", "Done", "dash-secondary");
      done.type = "button";
      done.setAttribute("aria-label", "Done: " + session.chapterName + " (" + exam.title + "), "
        + session.plannedMinutes + " minutes");
      done.addEventListener("click", async () => {
        const buttons = [...byId("today-sessions").querySelectorAll("button")];
        buttons.forEach((button) => { button.disabled = true; });
        byId("dashboard-error").hidden = true;
        error.hidden = true;
        try {
          await updateSession(session.id, { status: "completed" });
          await loadDashboard("Session marked complete.");
          byId("today-heading").focus();
        } catch (failure) {
          showError(failure);
          byId("dashboard-error").hidden = false;
        } finally {
          buttons.forEach((button) => { button.disabled = false; });
        }
      });
      item.append(detail, done);
      items.push(item);
    }
  }
  populateList("today-sessions", items, "Nothing planned today.");
}

function renderWeek(exams, now) {
  const days = Array.from({ length: 7 }, (_, index) => {
    const date = new Date(now.getFullYear(), now.getMonth(), now.getDate() + index);
    return { date, sessions: [], minutes: 0 };
  });
  const byDate = new Map(days.map((day) => [dateKey(day.date), day]));
  for (const exam of exams) {
    for (const session of exam.plan.sessions) {
      const day = byDate.get(session.sessionDate);
      if (day && session.status !== "missed") {
        day.sessions.push({ ...session, exam });
        day.minutes += session.plannedMinutes;
      }
    }
  }
  const max = Math.max(1, ...days.map((day) => day.minutes));
  const weekday = new Intl.DateTimeFormat(undefined, { weekday: "short" });
  byId("week-days").replaceChildren(...days.map((day, index) => {
    const column = dashNode("li", null, "dash-day");
    const heading = dashNode("strong", index === 0 ? "Today" : weekday.format(day.date));
    const svg = dashboardSvg("svg", { viewBox: "0 0 100 8", preserveAspectRatio: "none", "aria-hidden": "true" });
    svg.append(dashboardSvg("rect", { width: 100, height: 8, rx: 3, class: "week-track" }),
      dashboardSvg("rect", { width: 100 * day.minutes / max, height: 8, rx: 3, class: "week-fill" }));
    const sessions = dashNode("ul");
    for (const session of day.sessions) {
      const item = dashNode("li", null, "dash-week-session");
      item.dataset.accent = session.exam.accent;
      item.dataset.status = session.status;
      const course = examLink(session.exam, "/plan.html", session.exam.title, "week-course-title");
      course.title = session.exam.title;
      course.setAttribute("aria-label", "Open study plan for " + session.exam.title);
      const meta = dashNode("div", null, "week-session-meta");
      meta.append(dashNode("span", session.plannedMinutes + " min", "week-session-minutes"),
        dashNode("span", statusLabels[session.status], "dash-pill week-session-status"));
      item.append(dashNode("strong", session.chapterName), course, meta);
      sessions.append(item);
    }
    column.append(heading, dashNode("span", shortDate.format(day.date) + " · " + day.minutes + " min", "dash-muted"), svg);
    if (day.sessions.length) column.append(sessions);
    return column;
  }));
}

function renderCatchUp(exams, history) {
  const items = [];
  let missedCount = 0, missedMinutes = 0, unscheduledCount = 0, unscheduledMinutes = 0;
  const examsById = new Map(exams.map((exam) => [exam.id, exam]));
  for (const session of history) {
    const exam = examsById.get(session.examId);
    if (!exam || session.status !== "missed") continue;
    missedCount++;
    missedMinutes += session.plannedMinutes;
    const item = dashNode("li");
    item.append(dashNode("span", "Missed", "dash-pill dash-missed"),
      dashNode("strong", session.chapterName), dashNode("span", exam.title + " · "
        + session.sessionDate + " · " + session.plannedMinutes + " min", "dash-muted"),
      examLink(exam, "/plan.html", "Open plan"));
    items.push(item);
  }
  for (const exam of exams) {
    for (const work of exam.plan.unscheduled) {
      unscheduledCount++;
      unscheduledMinutes += work.minutes;
      const item = dashNode("li");
      item.append(dashNode("span", "Unscheduled", "dash-pill dash-missed"),
        dashNode("strong", work.chapterName), dashNode("span", exam.title + " · " + work.minutes + " min", "dash-muted"),
        examLink(exam, "/plan.html", "Open plan"));
      items.push(item);
    }
  }
  byId("catch-count").textContent = missedCount + " missed · " + missedMinutes
    + " min originally planned | " + unscheduledCount + " unscheduled · " + unscheduledMinutes + " min left";
  populateList("catch-list", items, "You’re all caught up.");
}

function renderOverview(exams, now) {
  const today = dateKey(now);
  const horizon = dateKey(new Date(now.getFullYear(), now.getMonth(), now.getDate() + 14));
  const next = exams.filter((exam) => exam.examDate >= today)
    .sort((left, right) => left.examDate.localeCompare(right.examDate))[0];
  const completed = exams.reduce((sum, exam) => sum + exam.progress.completedMinutes, 0);
  const planned = exams.reduce((sum, exam) => sum + exam.progress.totalPlannedMinutes, 0);
  byId("exam-total").textContent = exams.length;
  byId("exam-upcoming").textContent = exams.filter((exam) => exam.examDate >= today && exam.examDate <= horizon).length;
  byId("exam-completion").textContent = (planned ? Math.round(100 * completed / planned) : 0) + "%";
  byId("overall-minutes").textContent = completed + " of " + planned + " min";
  const nextCountdown = byId("exam-countdown");
  delete nextCountdown.dataset.countdownDate;
  nextCountdown.textContent = "—";
  if (next) {
    nextCountdown.dataset.countdownDate = next.examDate;
    nextCountdown.textContent = countdownText(next.examDate, now);
  }

  renderAnalytics(exams);
}

function renderNextExam(exams, now) {
  const today = dateKey(now);
  const next = exams.filter((exam) => exam.examDate >= today).sort((a, b) => a.examDate.localeCompare(b.examDate))[0];
  const nextCard = byId("next-exam");
  nextCard.replaceChildren();
  if (next) {
    const details = dashNode("div");
    details.append(dashNode("h3", next.title), dashNode("p", next.examDate + " · " + daysLeft(next.examDate, today),
      "dash-muted"), examLink(next, "/plan.html", "Open plan"));
    nextCard.append(details, completionRing(next.progress.percentage));
  } else {
    nextCard.append(dashNode("p", "No upcoming exam.", "dash-muted"));
  }

}

function renderExamList(exams, now) {
  const today = dateKey(now);
  byId("exam-empty").hidden = exams.length > 0;
  byId("exam-list").replaceChildren(...exams.map((exam) => {
    const item = dashNode("li", null, "dash-card dash-exam");
    item.dataset.accent = exam.accent;
    const actions = dashNode("div", null, "dash-actions");
    actions.append(examLink(exam, "/plan.html", "Study plan", "dash-primary"),
      examLink(exam, "/progress.html", "Progress & history"),
      examLink(exam, "/new-exam.html", "Edit exam"));
    const remove = dashNode("button", "Delete", "dash-secondary dash-danger");
    remove.type = "button";
    remove.setAttribute("aria-label", "Delete exam for " + exam.title);
    remove.addEventListener("click", () => openDeleteExam(exam, remove));
    actions.append(remove);
    item.append(dashNode("span", academicLabel(exam), "dash-pill"), dashNode("h3", exam.title),
      dashNode("p", exam.examDate + " · " + daysLeft(exam.examDate, today)
        + " · " + exam.dailyMinutes + " min/day", "dash-muted"),
      countdownNode(exam), dashNode("p", exam.progress.percentage + "% complete", "dash-muted"), examProgress(exam), actions);
    return item;
  }));
}

let examToDelete = null;
let deleteTrigger = null;
let deletingExam = false;
function openDeleteExam(exam, trigger) {
  if (deletingExam) return;
  examToDelete = exam;
  deleteTrigger = trigger;
  byId("delete-exam-name").textContent = exam.title;
  byId("delete-exam-error").hidden = true;
  byId("delete-exam-dialog").showModal();
}

if (byId("delete-exam-dialog")) {
  const dialog = byId("delete-exam-dialog");
  byId("delete-exam-cancel").addEventListener("click", () => dialog.close());
  dialog.addEventListener("cancel", (event) => { if (deletingExam) event.preventDefault(); });
  dialog.addEventListener("close", () => {
    if (deleteTrigger?.isConnected) deleteTrigger.focus();
    examToDelete = null;
  });
  byId("delete-exam-confirm").addEventListener("click", async () => {
    if (!examToDelete || deletingExam) return;
    const exam = examToDelete;
    deletingExam = true;
    byId("delete-exam-confirm").disabled = true;
    byId("delete-exam-cancel").disabled = true;
    byId("delete-exam-confirm").textContent = "Deleting…";
    byId("delete-exam-error").hidden = true;
    try {
      await deleteExam(exam.id);
      dialog.close();
      await loadDashboard("Exam and study plan deleted.");
      byId("exams-heading").focus();
    } catch (failure) {
      byId("delete-exam-error").textContent = failure.message;
      byId("delete-exam-error").hidden = false;
    } finally {
      deletingExam = false;
      byId("delete-exam-confirm").disabled = false;
      byId("delete-exam-cancel").disabled = false;
      byId("delete-exam-confirm").textContent = "Delete permanently";
    }
  });
}

function renderSemesterCourses(exams) {
  const groups = new Map();
  for (const exam of exams) {
    const key = exam.semester + "|" + (exam.courseName || exam.title);
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key).push(exam);
  }
  populateList("semester-courses", [...groups.values()].map((assessments) => {
    const item = dashNode("li");
    const first = assessments[0];
    item.append(dashNode("small", first.semester ? "Semester " + first.semester : "Semester not assigned", "dash-muted"),
      dashNode("h3", first.courseName || first.title));
    const links = dashNode("div", null, "dash-actions");
    for (const exam of assessments.sort((a, b) => a.examDate.localeCompare(b.examDate))) {
      const assessment = dashNode("div", null, "assessment-countdown");
      assessment.append(examLink(exam, "/plan.html", exam.assessment + " · " + exam.examDate), countdownNode(exam));
      links.append(assessment);
    }
    item.append(links);
    return item;
  }), "No saved exams in this selection. Use the form above to add one.");
}

function renderDashboard(exams, history, now) {
  switch (document.body.dataset.view) {
    case "exams":
      renderSemesterCourses(exams);
      renderExamList(exams, now);
      break;
    case "study":
      renderToday(exams, dateKey(now));
      renderNextExam(exams, now);
      renderWeek(exams, now);
      renderCatchUp(exams, history);
      break;
    case "chapters":
      renderChapterGroups(byId("active-chapters"), exams, false);
      renderChapterGroups(byId("completed-chapters"), exams, true);
      break;
    default:
      renderOverview(exams, now);
  }
}

function renderAnalytics(exams) {
  const sessions = exams.flatMap((exam) => exam.plan.sessions);
  const chapters = exams.flatMap((exam) => exam.plan.chapters || []);
  renderPie("session-chart", [
    { label: "Completed", value: sessions.filter((session) => session.status === "completed").length, colour: "#285a42" },
    { label: "Planned", value: sessions.filter((session) => session.status === "planned").length, colour: "#a5bda0" },
    { label: "Missed", value: sessions.filter((session) => session.status === "missed").length, colour: "#b67559" },
  ], "No study sessions yet.");
  renderPie("chapter-chart", [
    { label: "Completed", value: chapters.filter((chapter) => chapter.completed).length, colour: "#285a42" },
    { label: "In progress", value: chapters.filter((chapter) => !chapter.completed).length, colour: "#c6ba99" },
  ], "No chapters yet.");
}

function renderPie(id, segments, emptyMessage) {
  const container = byId(id);
  const total = segments.reduce((sum, segment) => sum + segment.value, 0);
  const svg = dashboardSvg("svg", {
    viewBox: "0 0 120 120", role: "img",
    "aria-label": total ? segments.map((segment) => segment.label + ": " + segment.value).join(", ") : emptyMessage,
  });
  let angle = -Math.PI / 2;
  if (!total) svg.append(dashboardSvg("circle", { cx: 60, cy: 60, r: 50, class: "pie-empty" }));
  for (const segment of segments) {
    if (!segment.value) continue;
    const end = angle + 2 * Math.PI * segment.value / total;
    if (segment.value === total) {
      svg.append(dashboardSvg("circle", { cx: 60, cy: 60, r: 50, fill: segment.colour }));
    } else {
      const x1 = 60 + 50 * Math.cos(angle), y1 = 60 + 50 * Math.sin(angle);
      const x2 = 60 + 50 * Math.cos(end), y2 = 60 + 50 * Math.sin(end);
      const large = end - angle > Math.PI ? 1 : 0;
      svg.append(dashboardSvg("path", {
        d: "M60 60 L" + x1 + " " + y1 + " A50 50 0 " + large + " 1 " + x2 + " " + y2 + " Z",
        fill: segment.colour,
      }));
    }
    angle = end;
  }
  const legend = dashNode("ul", null, "pie-legend");
  for (const segment of segments) {
    const item = dashNode("li");
    const swatch = dashboardSvg("svg", { viewBox: "0 0 12 12", "aria-hidden": "true", class: "pie-swatch" });
    swatch.append(dashboardSvg("rect", { width: 12, height: 12, rx: 3, fill: segment.colour }));
    item.append(swatch, dashNode("span", segment.label), dashNode("strong", String(segment.value)));
    legend.append(item);
  }
  container.replaceChildren(svg, legend);
  if (!total) container.append(dashNode("p", emptyMessage, "dash-muted pie-empty-label"));
}
function countdownNode(exam) {
  const node = dashNode("span", countdownText(exam.examDate), "live-countdown");
  node.dataset.countdownDate = exam.examDate;
  node.title = "Time until the start of " + exam.examDate + " in your local time";
  return node;
}

function renderChapterGroups(container, exams, completed) {
  const expanded = new Set([...container.querySelectorAll("details[open]")].map((node) => node.dataset.course));
  const courses = new Map();
  for (const exam of exams) {
    for (const chapter of exam.plan.chapters || []) {
      if (chapter.completed !== completed) continue;
      const key = exam.semester + "|" + (exam.courseName || exam.title);
      if (!courses.has(key)) courses.set(key, { exam, chapters: [] });
      courses.get(key).chapters.push({ chapter, exam });
    }
  }
  const groups = [...courses].map(([key, course]) => {
    const details = dashNode("details");
    details.dataset.course = key;
    details.open = expanded.has(key);
    const summary = dashNode("summary", (course.exam.courseName || course.exam.title)
      + " · " + (course.exam.semester ? "Semester " + course.exam.semester : "Unassigned semester")
      + " · " + course.chapters.length + (course.chapters.length === 1 ? " chapter" : " chapters"));
    const list = dashNode("ul", null, "dash-list");
    for (const { chapter, exam } of course.chapters) {
      const item = dashNode("li");
      item.append(dashNode("strong", chapter.name), dashNode("span", (exam.assessment || "OTHER") + " · " + exam.title, "dash-muted"),
        dashNode("span", chapter.completedMinutes + " / " + chapter.requiredMinutes + " study min · "
          + chapter.revisionCompletedMinutes + " revision min completed", "dash-muted"),
        examLink(exam, "/plan.html", completed ? "View study history" : "Open study plan"));
      list.append(item);
    }
    details.append(summary, list);
    return details;
  });
  container.replaceChildren(...groups);
  if (!groups.length) container.append(dashNode("p", completed ? "No completed chapters in this selection yet."
    : "No chapters left to study in this selection.", "dash-muted"));
}

function academicLabel(exam) {
  return (exam.semester ? "Semester " + exam.semester : "Unassigned semester") + " · " + (exam.assessment || "OTHER");
}

let dashboardExams = [], dashboardHistory = [];
let dashboardLoading = false;

function applyDashboardFilters() {
  const semester = byId("semester-filter")?.value ?? "all";
  const assessment = byId("assessment-filter")?.value ?? "all";
  const filtered = dashboardExams.filter((exam) => (semester === "all" || String(exam.semester) === semester)
    && (assessment === "all" || exam.assessment === assessment));
  renderDashboard(filtered, dashboardHistory, new Date());
}

function daysLeft(date, today) {
  const days = (Date.parse(date) - Date.parse(today)) / DAY;
  if (days < 0) return "Past exam";
  if (days === 0) return "Today";
  return days + (days === 1 ? " day left" : " days left");
}

async function loadDashboard(message = "") {
  if (dashboardLoading) return;
  dashboardLoading = true;
  const status = byId("dashboard-status");
  const content = byId("dashboard-content");
  const errorPanel = byId("dashboard-error");
  const now = new Date();
  const hours = now.getHours();
  const greeting = hours < 12 ? "Good morning" : hours < 18 ? "Good afternoon" : "Good evening";
  const name = getDisplayName();
  byId("dashboard-greeting").textContent = document.body.dataset.view === "dashboard" ? greeting + (name ? ", " + name : "") : document.body.dataset.title;
  byId("dashboard-date").textContent = dashboardDate.format(now);
  status.textContent = "Loading your study data…";
  errorPanel.hidden = true;
  error.hidden = true;
  content.hidden = true;
  try {
    const [exams, history, me] = await Promise.all([listExams(), getHistory(), getMe()]);
    byId("admin-link").hidden = me.role !== "admin";
    if (byId("admin-option")) byId("admin-option").hidden = me.role !== "admin";
    const details = await Promise.all(exams.map(async (exam, index) => {
      const [plan, progress] = await Promise.all([getPlan(exam.id), getProgress(exam.id)]);
      return { ...exam, plan, progress, accent: index % 6 };
    }));
    dashboardExams = details;
    dashboardHistory = history;
    applyDashboardFilters();
    content.hidden = false;
    status.textContent = message;
  } catch (failure) {
    status.textContent = "";
    showError(failure);
    errorPanel.hidden = false;
    byId("dashboard-retry").focus();
  } finally {
    dashboardLoading = false;
  }
}

async function setupExam(form) {
  const chapters = byId("chapters");
  const course = byId("exam-course");
  const title = form.elements.namedItem("title");
  populateCourses(course);
  for (let semester = 1; semester <= 12; semester++) {
    byId("exam-semester").add(new Option("Semester " + semester, semester));
  }
  course.addEventListener("change", () => {
    if (course.value) title.value = course.value;
  });
  const parameters = new URLSearchParams(location.search);
  const editing = parameters.has("examId");
  if (!editing) {
    const semester = parameters.get("semester");
    const assessment = parameters.get("assessment");
    const courseName = parameters.get("course");
    if (/^(?:[1-9]|1[0-2])$/.test(semester || "")) form.elements.semester.value = semester;
    if (["CT", "MID", "FINAL", "OTHER"].includes(assessment)) form.elements.assessment.value = assessment;
    if (courseName && courseName.length <= 255) {
      if (![...course.options].some((option) => option.value === courseName)) course.add(new Option(courseName, courseName));
      course.value = courseName;
      title.value = courseName + " · " + form.elements.assessment.selectedOptions[0].textContent;
    }
  }
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
      for (const key of ["title", "examDate", "dailyMinutes", "semester", "assessment"]) form.elements.namedItem(key).value = exam[key];
      course.value = exam.courseName || "";
      if (exam.courseName && course.value !== exam.courseName) course.add(new Option(exam.courseName, exam.courseName, true, true));
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
      semester: Number(fields.semester),
      assessment: fields.assessment,
      courseName: fields.courseName,
      chapters: Array.from(chapters.children, (row) => ({
        id: row.dataset.chapterId ? Number(row.dataset.chapterId) : undefined,
        name: row.querySelector('[name="chapterName"]').value,
        difficulty: row.querySelector('[name="difficulty"]').value,
        estimatedMinutes: Number(row.querySelector('[name="estimatedMinutes"]').value),
      })),
    };
    await (editing ? updateExam(examId, payload) : createExam(payload));
    form.hidden = true;
    byId("exam-cancel").hidden = true;
    byId("exam-save-message").textContent = payload.title + (editing ? " has been updated." : " has been saved.");
    byId("exam-save-result").hidden = false;
    const heading = document.querySelector("main h1");
    heading.textContent = editing ? "Exam updated" : "Exam saved";
    heading.tabIndex = -1;
    heading.focus();
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
    const [plan, exam] = await Promise.all([getPlan(examId), getExam(examId)]);
    const { sessions, unscheduled } = plan;
    const countdown = byId("plan-countdown");
    countdown.replaceChildren(dashNode("strong", exam.title + " · " + exam.assessment + " · " + exam.examDate), countdownNode(exam));
    renderChapterGroups(byId("plan-active-chapters"), [{ ...exam, plan }], false);
    renderChapterGroups(byId("plan-completed-chapters"), [{ ...exam, plan }], true);
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
    labelTableRows(table);
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
    labelTableRows(table);
    byId("history-status").textContent = history.length ? "" : "No completed or missed sessions yet.";
  } catch (failure) {
    progressStatus.textContent = "";
    showError(failure);
  }
}

function labelTableRows(table) {
  const labels = [...table.querySelectorAll("thead th")].map((cell) => cell.textContent);
  for (const row of table.tBodies[0].rows) {
    [...row.cells].forEach((cell, index) => { cell.dataset.label = labels[index]; });
  }
}

const signOut = byId("sign-out");
if (signOut && requireLogin()) {
  signOut.addEventListener("click", async () => {
    await endSession();
    location.replace("/login.html");
  });
  window.addEventListener("pageshow", (event) => {
    if (event.persisted && requireLogin()) {
      if (byId("dashboard-retry")) loadDashboard();
      if (byId("plan-table")) loadPlan();
      if (byId("history-table")) location.reload();
    }
  });
  if (byId("dashboard-retry")) {
    if (byId("course-exam-form")) setupCoursePicker();
    document.addEventListener("visibilitychange", () => {
      if (!document.hidden && requireLogin()) loadDashboard();
    });
    if (byId("semester-filter")) {
      for (let semester = 1; semester <= 12; semester++) {
        byId("semester-filter").add(new Option("Semester " + semester, semester));
      }
      byId("semester-filter").addEventListener("change", applyDashboardFilters);
      byId("assessment-filter").addEventListener("change", applyDashboardFilters);
    }
    byId("dashboard-retry").addEventListener("click", () => loadDashboard());
    loadDashboard();
  }
  if (byId("exam-form")) setupExam(byId("exam-form"));
  if (byId("plan-table")) loadPlan();
  if (byId("history-table")) loadProgress();
}

if (byId("login-form")) setupLogin(byId("login-form"));
if (byId("dashboard-retry") || byId("plan-countdown")) {
  let countdownDay = dateKey(new Date());
  setInterval(() => {
    if (document.hidden) return;
    refreshCountdowns();
    const today = dateKey(new Date());
    if (today !== countdownDay) {
      countdownDay = today;
      if (byId("dashboard-retry")) loadDashboard();
    }
  }, 1000);
  document.addEventListener("visibilitychange", () => { if (!document.hidden) refreshCountdowns(); });
}
if (byId("register-form")) {
  handleSubmit(byId("register-form"), async (details) => {
    await register(details);
    location.replace("/login.html?registered=1");
  });
}

function setupCoursePicker() {
  const courseForm = byId("course-exam-form");
  populateCourses(byId("dashboard-course"));
  for (let semester = 1; semester <= 12; semester++) {
    byId("course-semester").add(new Option("Semester " + semester, semester));
  }
  courseForm.addEventListener("submit", (event) => {
    event.preventDefault();
    const parameters = new URLSearchParams(new FormData(courseForm));
    location.assign("/new-exam.html?" + parameters.toString());
  });
}

const menuButton = byId("workspace-menu-toggle");
if (menuButton) {
  menuButton.addEventListener("click", () => {
    const open = byId("workspace-nav").classList.toggle("is-open");
    menuButton.setAttribute("aria-expanded", String(open));
  });
}

if (byId("reminder-unread")) {
  const refreshReminderCount = async () => {
    if (!requireLogin()) return;
    try {
      const { unread } = await getReminders();
      byId("reminder-unread").textContent = unread > 99 ? "99+" : String(unread);
      byId("reminder-unread").hidden = unread === 0;
      document.querySelector(".reminder-bell").setAttribute("aria-label", "Reminders, " + unread + " unread");
    } catch { /* Reminder availability must not block the study dashboard. */ }
  };
  refreshReminderCount();
  setInterval(() => { if (!document.hidden) refreshReminderCount(); }, 60000);
}
