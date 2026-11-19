import { requireLogin, endSession, getReminders, saveReminders, connectPush, disconnectPush, testReminder, readReminder } from "./api.js";

const byId = (id) => document.getElementById(id);
let data, loading = false;
for (const zone of Intl.supportedValuesOf?.("timeZone") || []) {
  if (![...byId("timeZone").options].some((option) => option.value === zone)) byId("timeZone").add(new Option(zone, zone));
}
function status(message, error = false) {
  const node = byId(error ? "reminder-error" : "reminder-status");
  node.textContent = message;
  node.hidden = !message;
}
function controlState() {
  for (const id of ["push-connect", "push-disconnect", "reminder-test", "reminder-save"]) byId(id).disabled = loading;
}
async function action(work) {
  if (loading) return;
  loading = true; controlState(); status("", true); status("");
  try { await work(); } catch (error) { status(error.message, true); }
  finally { loading = false; controlState(); }
}
function node(tag, text, className) {
  const element = document.createElement(tag);
  if (text) element.textContent = text;
  if (className) element.className = className;
  return element;
}
async function load(fill = true) {
  data = await getReminders();
  if (fill) {
    for (const [key, value] of Object.entries(data.settings)) {
      const input = byId(key);
      if (typeof value === "boolean") input.checked = value;
      else input.value = value;
    }
    byId("email-address").textContent = data.email;
    byId("sender-state").textContent = data.emailAvailable
      ? "Email sender is connected. Delivery status appears below after sending."
      : "Email delivery is waiting for the administrator to connect the email service. In-app reminders work independently.";
  }
  const items = byId("reminder-items"); items.replaceChildren();
  byId("inbox-count").textContent = data.unread + " unread";
  if (!data.items.length) items.append(node("li", "No reminders yet. Your scheduled reminders will appear here.", "reminder-empty"));
  for (const reminder of data.items) {
    const item = node("li", null, "reminder-item");
    item.append(node("h3", reminder.title), node("p", reminder.message), node("small",
      new Date(reminder.createdAtEpoch * 1000).toLocaleString(undefined, { timeZone: data.settings.timeZone }), "dash-muted"));
    const delivery = [];
    if (reminder.emailStatus) delivery.push("Email: " + reminder.emailStatus + (reminder.emailFailure ? " — " + reminder.emailFailure : ""));
    if (reminder.pushStatus) delivery.push("Browser: " + reminder.pushStatus);
    if (delivery.length) item.append(node("p", delivery.join(" · "), "dash-muted"));
    const actions = node("div", null, "dash-actions");
    const link = node("a", "Open study plan", "dash-secondary"); link.href = reminder.path; actions.append(link);
    if (!reminder.isRead) {
      const read = node("button", "Mark as read", "dash-secondary"); read.type = "button";
      read.setAttribute("aria-label", "Mark " + reminder.title + " as read");
      read.addEventListener("click", () => action(async () => { await readReminder(reminder.id); await load(false); }));
      actions.append(read);
    }
    item.append(actions); items.append(item);
  }
  await browserStatus();
}
async function browserStatus() {
  if (!("serviceWorker" in navigator) || !("PushManager" in window) || !("Notification" in window)) {
    byId("browser-state").textContent = "Push notifications are unavailable in this browser. You can use email and the reminder inbox.";
    return;
  }
  const registration = await navigator.serviceWorker.getRegistration("/");
  const subscription = await registration?.pushManager.getSubscription();
  byId("browser-state").textContent = subscription ? "This browser is connected."
    : Notification.permission === "denied" ? "Notifications are blocked. Allow them in this site's browser settings."
    : "This browser is not connected yet.";
}
if (requireLogin()) {
  load().catch((error) => status(error.message, true));
  byId("reminder-form").addEventListener("submit", (event) => {
    event.preventDefault();
    action(async () => {
      const settings = {};
      if (!data) throw new Error("Settings could not be loaded. Refresh the page and try again.");
      for (const key of Object.keys(data?.settings || {})) settings[key] = byId(key).type === "checkbox" ? byId(key).checked : byId(key).value;
      await saveReminders(settings); status("Reminder settings saved."); await load();
    });
  });
  byId("push-connect").addEventListener("click", () => action(async () => {
    if (!data?.pushAvailable) throw new Error("Browser push is waiting for server configuration.");
    if (!("Notification" in window) || !("serviceWorker" in navigator) || !("PushManager" in window)) throw new Error("This browser does not support push notifications.");
    // Request permission only after the user's explicit Enable action.
    if (await Notification.requestPermission() !== "granted") throw new Error("Allow notifications to enable browser reminders.");
    await navigator.serviceWorker.register("/sw.js", { scope: "/" });
    const registration = await navigator.serviceWorker.ready;
    let subscription = await registration.pushManager.getSubscription();
    if (!subscription) {
      const base64 = data.publicKey.replace(/-/g, "+").replace(/_/g, "/");
      const key = Uint8Array.from(atob(base64 + "=".repeat((4 - base64.length % 4) % 4)), (letter) => letter.charCodeAt(0));
      subscription = await registration.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: key });
    }
    await connectPush(subscription.toJSON());
    byId("pushEnabled").checked = true;
    status("Browser connected. Save settings to start browser reminders."); await browserStatus();
  }));
  byId("push-disconnect").addEventListener("click", () => action(async () => {
    const registration = await navigator.serviceWorker?.getRegistration("/");
    const subscription = await registration?.pushManager.getSubscription();
    if (subscription) { await disconnectPush(subscription.endpoint); await subscription.unsubscribe(); }
    status("This browser is disconnected. Other devices keep their existing settings."); await browserStatus();
  }));
  byId("reminder-test").addEventListener("click", () => action(async () => {
    status((await testReminder()).message); await load(false);
  }));
  byId("reminder-refresh").addEventListener("click", () => action(() => load(false)));
  byId("sign-out").addEventListener("click", async () => { await endSession(); location.replace("/login.html"); });
  byId("workspace-menu-toggle").addEventListener("click", () => {
    const open = byId("workspace-nav").classList.toggle("is-open");
    byId("workspace-menu-toggle").setAttribute("aria-expanded", String(open));
  });
  // Only refresh the inbox; never overwrite settings the user is currently editing.
  setInterval(() => { if (!document.hidden && !loading) action(() => load(false)); }, 30000);
}
