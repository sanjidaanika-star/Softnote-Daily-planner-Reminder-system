import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";

const source = await readFile(new URL("../frontend/js/api.js", import.meta.url), "utf8");
let sequence = 0;

async function setup(t, respond) {
  const values = new Map([["softnote.token", "old-token"]]);
  const calls = [], redirects = [];
  let unsubscribed = 0;
  const subscription = {
    endpoint: "https://fcm.googleapis.com/test-device",
    toJSON: () => ({ endpoint: "https://fcm.googleapis.com/test-device", keys: { p256dh: "test-key", auth: "test-auth" } }),
    unsubscribe: async () => { unsubscribed++; return true; },
  };
  const globals = {
    sessionStorage: { getItem: key => values.get(key) ?? null, setItem: (key, value) => values.set(key, value), removeItem: key => values.delete(key) },
    navigator: { serviceWorker: { getRegistration: async () => ({ pushManager: { getSubscription: async () => subscription } }) } },
    location: { origin: "https://softnote.test", replace: url => redirects.push(String(url)) },
    fetch: async (path, options) => {
      calls.push({ path, ...options });
      const [status, body] = await respond(path, options);
      return { ok: status >= 200 && status < 300, status, json: async () => body };
    },
  };
  for (const [name, value] of Object.entries(globals)) {
    const original = Object.getOwnPropertyDescriptor(globalThis, name);
    Object.defineProperty(globalThis, name, { configurable: true, writable: true, value });
    t.after(() => original ? Object.defineProperty(globalThis, name, original) : delete globalThis[name]);
  }
  const api = await import("data:text/javascript;base64," + Buffer.from(source).toString("base64") + "#" + sequence++);
  return { api, values, calls, redirects, get unsubscribed() { return unsubscribed; } };
}

test("expired token clears login but keeps browser reminders subscribed", async t => {
  const fixture = await setup(t, () => [401, { error: "Sign in again" }]);
  await assert.rejects(() => fixture.api.getMe(), /Sign in again/);
  assert.equal(fixture.values.has("softnote.token"), false);
  assert.equal(fixture.unsubscribed, 0);
  assert.match(fixture.redirects[0], /expired=1/);
});

test("login rebinds the existing subscription with the new account token", async t => {
  const fixture = await setup(t, path => path === "/login"
    ? [200, { id: 2, name: "Second user", token: "new-owner-token" }]
    : [200, { message: "Connected" }]);
  await fixture.api.login({ email: "second@example.test", password: "test-password" });
  const binding = fixture.calls.find(call => call.path === "/reminders/subscription");
  assert.equal(binding.method, "POST");
  assert.equal(binding.headers.Authorization, "Bearer new-owner-token");
  assert.equal(JSON.parse(binding.body).endpoint, "https://fcm.googleapis.com/test-device");
  assert.equal(fixture.unsubscribed, 0);
});

test("if account rebinding fails, the previous browser subscription is removed", async t => {
  const fixture = await setup(t, path => path === "/login"
    ? [200, { id: 2, name: "Second user", token: "new-token" }]
    : [503, { error: "Push unavailable" }]);
  await fixture.api.login({ email: "second@example.test", password: "test-password" });
  assert.equal(fixture.unsubscribed, 1);
});

test("explicit sign-out disconnects server and browser before clearing the login", async t => {
  const fixture = await setup(t, () => [200, { message: "Disconnected" }]);
  await fixture.api.endSession();
  const removal = fixture.calls.find(call => call.method === "DELETE");
  assert.equal(removal.path, "/reminders/subscription");
  assert.equal(removal.headers.Authorization, "Bearer old-token");
  assert.equal(fixture.unsubscribed, 1);
  assert.equal(fixture.values.has("softnote.token"), false);
});

test("sign-out still removes the local subscription when the backend is unavailable", async t => {
  const fixture = await setup(t, () => { throw Error("Offline"); });
  await fixture.api.endSession();
  assert.equal(fixture.unsubscribed, 1);
  assert.equal(fixture.values.has("softnote.token"), false);
});
