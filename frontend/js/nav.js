// Shared sidebar behaviour for signed-in pages: the phone menu toggle,
// the administrator link, and (where the page opts in) signing out.
import { endSession, getMe, requireLogin } from "./api.js";

const byId = (id) => document.getElementById(id);

const menuButton = byId("workspace-menu-toggle");
const menu = byId("workspace-nav");
if (menuButton && menu) {
  menuButton.addEventListener("click", () => {
    const open = menu.classList.toggle("is-open");
    menuButton.setAttribute("aria-expanded", String(open));
  });
  document.addEventListener("keydown", (event) => {
    if (event.key === "Escape" && menu.classList.contains("is-open")) {
      menu.classList.remove("is-open");
      menuButton.setAttribute("aria-expanded", "false");
      menuButton.focus();
    }
  });
}

const signOut = document.querySelector("#sign-out[data-nav-signout]");
if (signOut) {
  signOut.addEventListener("click", async () => {
    await endSession();
    location.replace("/login.html");
  });
}

const adminLink = byId("admin-link");
if (adminLink && requireLogin()) {
  getMe().then((me) => { adminLink.hidden = me.role !== "admin"; }).catch(() => {});
}
