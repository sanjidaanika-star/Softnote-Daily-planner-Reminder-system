import { answerQuestion, createQuiz, getQuiz, getQuizScore, listNotes, requireLogin, uploadNote } from "./api.js";

const element = (id) => document.getElementById(id);
const start = element("quiz-start");
const count = start.elements.namedItem("count");
const notes = start.elements.namedItem("note");
const create = start.querySelector("button");
const status = element("quiz-status");
const retry = element("quiz-retry");
const next = element("question-next");
const finish = element("quiz-finish");
const resume = element("quiz-resume");
const upload = element("note-upload");
let quiz;
let pendingId;
let busy = false;
let index = 0;

function showError(message) {
  const error = element("quiz-error");
  error.textContent = message;
  error.hidden = !message;
}

function setBusy(value) {
  busy = value;
  create.disabled = value || notes.options.length === 0;
  retry.disabled = value;
}

async function refreshNotes(selected) {
  const items = await listNotes();
  notes.replaceChildren(...items.map((note) => new Option(note.title, note.id)));
  if (selected) notes.value = String(selected);
  if (!items.length) status.textContent = "Upload a PDF to create your first quiz.";
  create.disabled = busy || !items.length;
}

async function poll(id) {
  status.textContent = "Generating...";
  const deadline = Date.now() + 60_000;
  while (Date.now() < deadline) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), deadline - Date.now());
    let result;
    try {
      result = await getQuiz(id, controller.signal);
    } catch (failure) {
      if (!controller.signal.aborted) throw failure;
      break;
    } finally {
      clearTimeout(timer);
    }
    if (result.status === "ready") {
      quiz = result;
      pendingId = null;
      const unanswered = quiz.questions.findIndex((question) => !question.answer);
      status.textContent = quiz.questions.length < quiz.requestedCount
        ? "Only " + quiz.questions.length + " valid, distinct questions were available from these notes." : "";
      if (unanswered === -1) await showScore();
      else showQuestion(unanswered);
      return;
    }
    if (result.status === "failed") {
      pendingId = null;
      throw new Error(result.failureReason || "Quiz generation failed.");
    }
    await new Promise((resolve) => setTimeout(resolve, Math.min(2000, Math.max(0, deadline - Date.now()))));
  }
  throw new Error("This quiz is taking longer than expected. Retry to check its status.");
}

async function generate(existingId) {
  if (busy || (!existingId && !notes.value)) return;
  showError("");
  retry.hidden = true;
  setBusy(true);
  try {
    if (!existingId) {
      element("question-panel").hidden = true;
      element("score-panel").hidden = true;
      status.textContent = "Starting quiz…";
    }
    pendingId = existingId ?? (await createQuiz(notes.value, Number(count.value))).id;
    const url = new URL(location.href);
    url.searchParams.set("quizId", pendingId);
    history.replaceState(null, "", url);
    await poll(pendingId);
  } catch (failure) {
    status.textContent = "";
    showError(failure.message);
    retry.hidden = false;
  } finally {
    setBusy(false);
  }
}

function showQuestion(position) {
  index = position;
  const question = quiz.questions[position];
  element("score-panel").hidden = true;
  element("question-panel").hidden = false;
  element("question-position").textContent = "Question " + (position + 1) + " of " + quiz.questions.length;
  const heading = element("question-title");
  heading.textContent = question.question;
  const options = element("question-options");
  const buttons = question.options.map((option, chosen) => {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "dash-secondary";
    button.textContent = option;
    button.addEventListener("click", async () => {
      if (busy) return;
      setBusy(true);
      buttons.forEach((item) => { item.disabled = true; });
      showError("");
      try {
        question.answer = await answerQuestion(quiz.id, question.id, chosen);
        showFeedback(question.answer, buttons);
      } catch (failure) {
        showError(failure.message);
        buttons.forEach((item) => { item.disabled = false; });
      } finally {
        setBusy(false);
      }
    });
    return button;
  });
  options.replaceChildren(...buttons);
  element("question-feedback").textContent = "";
  next.hidden = true;
  finish.hidden = true;
  if (question.answer) showFeedback(question.answer, buttons);
  else heading.focus();
}

function showFeedback(answer, buttons) {
  buttons.forEach((button) => { button.disabled = true; });
  buttons[answer.correctIndex].classList.add("quiz-correct");
  if (!answer.is_correct) buttons[answer.chosenIndex].classList.add("quiz-wrong");
  element("question-feedback").textContent = (answer.is_correct ? "Correct. " : "Incorrect. Correct answer: "
    + buttons[answer.correctIndex].textContent + ". ") + answer.explanation;
  const remaining = quiz.questions.slice(index + 1).some((question) => !question.answer);
  next.textContent = remaining ? "Next" : "Show score";
  next.hidden = false;
  finish.hidden = !remaining;
  next.focus();
}

async function showScore() {
  next.disabled = true;
  finish.disabled = true;
  create.disabled = true;
  try {
    const score = await getQuizScore(quiz.id);
    element("quiz-score").textContent = score.correct + " of " + score.totalAnswered + " answered correctly ("
      + score.percentage + "%). " + (quiz.questions.length - score.totalAnswered) + " unanswered.";
    const wrong = quiz.questions.filter((question) => question.answer && !question.answer.is_correct).map((question) => {
      const item = document.createElement("li");
      const answer = question.answer;
      item.textContent = question.question + " You chose: " + question.options[answer.chosenIndex]
        + ". Correct: " + question.options[answer.correctIndex] + ". " + answer.explanation;
      return item;
    });
    if (!wrong.length) {
      const item = document.createElement("li");
      item.textContent = "No wrong answers.";
      wrong.push(item);
    }
    element("wrong-answers").replaceChildren(...wrong);
    resume.hidden = !quiz.questions.some((question) => !question.answer);
    element("question-panel").hidden = true;
    element("score-panel").hidden = false;
    element("score-heading").focus();
  } catch (failure) {
    showError(failure.message);
  } finally {
    next.disabled = false;
    finish.disabled = false;
    create.disabled = busy || notes.options.length === 0;
  }
}

if (requireLogin()) {
  refreshNotes().catch((failure) => showError(failure.message));
  const saved = new URLSearchParams(location.search).get("quizId");
  if (saved && /^[1-9][0-9]*$/.test(saved)) generate(saved);
}

upload.addEventListener("submit", async (event) => {
  event.preventDefault();
  const button = upload.querySelector("button");
  button.disabled = true;
  showError("");
  try {
    const result = await uploadNote(upload.elements.namedItem("note").files[0]);
    await refreshNotes(result.id);
    upload.reset();
    if (!busy) status.textContent = "Note uploaded.";
  } catch (failure) {
    showError(failure.message);
  } finally {
    button.disabled = false;
  }
});
count.addEventListener("change", () => { create.textContent = "Create " + count.value + " questions"; });
start.addEventListener("submit", (event) => { event.preventDefault(); generate(null); });
retry.addEventListener("click", () => generate(pendingId));
next.addEventListener("click", () => {
  const following = quiz.questions.findIndex((question, position) => position > index && !question.answer);
  if (following === -1) showScore();
  else showQuestion(following);
});
finish.addEventListener("click", showScore);
resume.addEventListener("click", () => showQuestion(quiz.questions.findIndex((question) => !question.answer)));
