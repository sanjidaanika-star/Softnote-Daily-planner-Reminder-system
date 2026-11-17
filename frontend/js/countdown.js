export function countdownText(date, now = new Date()) {
  const deadline = new Date(date + "T00:00:00");
  const remaining = deadline.getTime() - now.getTime();
  if (!Number.isFinite(remaining)) return "Date unavailable";
  if (remaining <= 0) {
    const today = new Date(now.getFullYear(), now.getMonth(), now.getDate());
    return deadline.getTime() === today.getTime() ? "Exam day" : "Exam date passed";
  }
  const seconds = Math.ceil(remaining / 1000);
  const days = Math.floor(seconds / 86400);
  const hours = Math.floor(seconds % 86400 / 3600);
  const minutes = Math.floor(seconds % 3600 / 60);
  return days + "d " + String(hours).padStart(2, "0") + "h "
    + String(minutes).padStart(2, "0") + "m " + String(seconds % 60).padStart(2, "0") + "s";
}

export function refreshCountdowns(root = document) {
  const now = new Date();
  for (const node of root.querySelectorAll("[data-countdown-date]")) {
    node.textContent = countdownText(node.dataset.countdownDate, now);
  }
}
