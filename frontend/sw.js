// No caching or fetch interception. Authentication and existing page requests stay unchanged.
self.addEventListener("push", (event) => {
  let data;
  try { data = event.data.json(); } catch { data = {}; }
  const url = typeof data.url === "string" && /^\/(study\.html|plan\.html\?examId=\d+)$/.test(data.url)
    ? data.url : "/reminders.html";
  event.waitUntil(self.registration.showNotification(data.title || "Softnote reminder", {
    body: data.body || "Open Softnote to view your study reminder.",
    tag: data.tag || "softnote-reminder", data: { url },
  }));
});
self.addEventListener("notificationclick", (event) => {
  event.notification.close();
  const destination = new URL(event.notification.data?.url || "/reminders.html", self.location.origin);
  if (destination.origin !== self.location.origin) return;
  event.waitUntil((async () => {
    const windows = await self.clients.matchAll({ type: "window", includeUncontrolled: true });
    const existing = windows.find((client) => new URL(client.url).origin === destination.origin);
    if (existing) { await existing.navigate(destination.href); return existing.focus(); }
    return self.clients.openWindow(destination.href);
  })());
});
