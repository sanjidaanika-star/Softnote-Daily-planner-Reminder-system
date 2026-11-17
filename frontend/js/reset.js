import { requestPasswordReset, confirmPasswordReset, clearToken } from './api.js';
const form = document.getElementById('reset-form');
const error = document.getElementById('error');
const notice = document.getElementById('notice');
const token = location.hash.slice(1);
if (token) {
  history.replaceState(null, '', location.pathname);
  document.getElementById('reset-heading').textContent = 'Choose a new password';
  document.getElementById('reset-intro').textContent = 'Use at least 8 characters. You will sign in again after saving.';
  document.getElementById('email-label').hidden = true; form.elements.email.disabled = true;
  for (const id of ['password-label', 'confirm-label']) document.getElementById(id).hidden = false;
  for (const field of [form.elements.password, form.elements.confirmation]) { field.disabled = false; field.required = true; }
  form.querySelector('button').textContent = 'Save password';
}
form.addEventListener('submit', async (event) => {
  event.preventDefault(); error.hidden = true; notice.hidden = true;
  const button = form.querySelector('button'); button.disabled = true;
  try {
    if (token && form.elements.password.value !== form.elements.confirmation.value) throw new Error('Passwords must match.');
    const result = token ? await confirmPasswordReset(token, form.elements.password.value) : await requestPasswordReset(form.elements.email.value);
    notice.textContent = result.message; notice.hidden = false;
    if (token) { clearToken(); form.hidden = true; }
  } catch (failure) { error.textContent = failure.message; error.hidden = false; }
  finally { button.disabled = false; }
});
