const DAY = 86_400_000;

function remainingParts(date, now) {
  const deadline = new Date(date + "T00:00:00");
  const remaining = deadline.getTime() - now.getTime();
  if (!Number.isFinite(remaining)) return { label: "Date unavailable" };
  if (remaining <= 0) {
    const today = new Date(now.getFullYear(), now.getMonth(), now.getDate());
    return { label: deadline.getTime() === today.getTime() ? "Exam day" : "Exam date passed" };
  }
  const seconds = Math.ceil(remaining / 1000);
  return {
    days: Math.floor(seconds / 86400),
    hours: Math.floor(seconds % 86400 / 3600),
    minutes: Math.floor(seconds % 3600 / 60),
    seconds: seconds % 60,
  };
}

export function countdownText(date, now = new Date()) {
  const parts = remainingParts(date, now);
  if (parts.label) return parts.label;
  return parts.days + "d " + String(parts.hours).padStart(2, "0") + "h "
    + String(parts.minutes).padStart(2, "0") + "m " + String(parts.seconds).padStart(2, "0") + "s";
}

/** Renders the countdown as labelled boxes: 3 days · 11 hrs · 05 min · 32 sec. */
function renderBlocks(node, date, now) {
  const parts = remainingParts(date, now);
  if (parts.label) {
    node.textContent = parts.label;
    node.setAttribute("aria-label", parts.label);
    return;
  }
  const units = [
    [parts.days, parts.days === 1 ? "day" : "days"],
    [String(parts.hours).padStart(2, "0"), "hrs"],
    [String(parts.minutes).padStart(2, "0"), "min"],
    [String(parts.seconds).padStart(2, "0"), "sec"],
  ];
  node.replaceChildren(...units.map(([value, unit]) => {
    const box = document.createElement("span");
    box.className = "cd-box";
    const number = document.createElement("b");
    number.textContent = value;
    const label = document.createElement("small");
    label.textContent = unit;
    box.append(number, label);
    return box;
  }));
  node.setAttribute("aria-label", parts.days + " days, " + parts.hours + " hours and " + parts.minutes + " minutes left");
}

export function renderCountdown(node, date, now = new Date()) {
  if (node.dataset.countdownStyle === "blocks") renderBlocks(node, date, now);
  else node.textContent = countdownText(date, now);
}

export function refreshCountdowns(root = document) {
  const now = new Date();
  for (const node of root.querySelectorAll("[data-countdown-date]")) {
    renderCountdown(node, node.dataset.countdownDate, now);
  }
}

/** "2026-11-29" → "Sun, 29 Nov" (the year is added when it is not this year). */
export function friendlyDate(iso, now = new Date()) {
  const date = new Date(iso + "T00:00:00");
  if (Number.isNaN(date.getTime())) return iso;
  const options = { weekday: "short", day: "numeric", month: "short" };
  if (date.getFullYear() !== now.getFullYear()) options.year = "numeric";
  return new Intl.DateTimeFormat(undefined, options).format(date);
}

/** Whole calendar days from today until the given date. */
export function daysUntil(iso, now = new Date()) {
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate());
  return Math.round((new Date(iso + "T00:00:00") - today) / DAY);
}

export function relativeDay(iso, now = new Date()) {
  const days = daysUntil(iso, now);
  if (days === 0) return "Today";
  if (days === 1) return "Tomorrow";
  if (days === -1) return "Yesterday";
  return days > 0 ? "In " + days + " days" : Math.abs(days) + " days ago";
}
