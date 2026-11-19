import { requireLogin, getMe, adminGet, setAccountActive, issueReset, endSession } from './api.js';
const byId = (id) => document.getElementById(id);
const node = (tag, text) => Object.assign(document.createElement(tag), { textContent: text });
let users = [];
function fail(error) { byId('error').textContent = error.message; byId('error').hidden = false; }
function renderUsers() {
  const search = byId('account-search').value.trim().toLowerCase();
  const rows = users.filter((u) => (u.name + ' ' + u.email).toLowerCase().includes(search)).map((user) => {
    const row = document.createElement('tr');
    for (const text of [user.name + '\n' + user.email, user.created_at, user.role, user.exams + ' / ' + user.notes, user.active ? 'Active' : 'Disabled']) row.append(node('td', text));
    const action = node('td', '');
    if (user.role !== 'admin') {
      const button = node('button', user.active ? 'Disable account' : 'Enable account');
      button.addEventListener('click', async () => {
        if (!confirm((user.active ? 'Disable ' : 'Enable ') + user.email + '?')) return;
        button.disabled = true;
        try { await setAccountActive(user.id, !user.active); await load(); }
        catch (error) { fail(error); button.disabled = false; }
      });
      action.append(button);
    } else action.textContent = 'Administrator';
    row.append(action);
    return row;
  });
  byId('accounts').replaceChildren(...rows);
  labelCells('accounts');
  if (!rows.length) { const row = node('tr', ''); const cell = node('td', 'No matching accounts.'); cell.colSpan = 6; row.append(cell); byId('accounts').append(row); }
}
function labelCells(id) {
  const body = byId(id);
  const labels = [...body.closest('table').querySelectorAll('th')].map((th) => th.textContent);
  for (const row of body.rows) [...row.cells].forEach((cell, index) => { cell.dataset.label = labels[index]; });
}
async function load() {
  byId('error').hidden = true;
  byId('refresh').disabled = true;
  try {
    const me = await getMe();
    if (me.role !== 'admin') throw new Error('Administrator access is required. Use the study dashboard for your account.');
    const [summary, accounts, resets, audit] = await Promise.all(['summary', 'users', 'resets', 'audit'].map(adminGet));
    users = accounts;
    byId('admin-stats').replaceChildren(...[['Accounts', summary.users], ['Active accounts', summary.active], ['Exams', summary.exams], ['Notes', summary.notes], ['Quizzes', summary.quizzes], ['Failed quizzes', summary.failedQuizzes], ['Pending resets', summary.pendingResets]].map(([label, value], i) => {
      const card = node('div', ''); card.className = 'dash-card'; card.dataset.accent = i % 6;
      card.append(node('dt', label), node('dd', value)); return card;
    }));
    renderUsers();
    byId('resets').replaceChildren(...resets.map((request) => {
      const row = node('tr', '');
      for (const text of [request.email, request.requested_at, request.status]) row.append(node('td', text));
      const action = node('td', '');
      if (['pending', 'issued'].includes(request.status)) {
        const button = node('button', request.status === 'issued' ? 'Replace reset link' : 'Issue reset link');
        button.addEventListener('click', async () => {
          if (!confirm('Have you verified the identity of ' + request.email + '?')) return;
          button.disabled = true;
          try {
            const result = await issueReset(request.id);
            const output = byId('reset-result'); output.replaceChildren(node('span', 'Send this link privately to ' + request.email + '. Expires in 30 minutes. '));
            const input = document.createElement('input'); input.readOnly = true; input.value = location.origin + result.resetPath; input.setAttribute('aria-label', 'One-time password reset link'); output.append(input); output.hidden = false; input.select();
            await load();
          } catch (error) { fail(error); button.disabled = false; }
        }); action.append(button);
      } else action.textContent = 'Closed';
      row.append(action); return row;
    }));
    if (!resets.length) { const row = node('tr', ''); const cell = node('td', 'No reset requests.'); cell.colSpan = 4; row.append(cell); byId('resets').append(row); }
    labelCells('resets');
    byId('audit').replaceChildren(...audit.map((entry) => node('li', entry.created_at + ' · ' + entry.actor + ' · ' + entry.action + ' · ' + entry.target)));
    if (!audit.length) byId('audit').append(node('li', 'No administration actions yet.'));
    byId('admin-content').hidden = false; byId('message').textContent = 'Updated ' + new Date().toLocaleTimeString();
  } catch (error) { fail(error); byId('message').textContent = ''; }
  finally { byId('refresh').disabled = false; }
}
byId('refresh').addEventListener('click', load);
byId('account-search').addEventListener('input', renderUsers);
byId('sign-out').addEventListener('click', async () => { await endSession(); location.replace('/login.html'); });
if (requireLogin()) load();
