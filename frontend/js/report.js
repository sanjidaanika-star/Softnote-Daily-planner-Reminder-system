import { requireLogin, listExams, getPlan, getProgress } from './api.js';
const byId = (id) => document.getElementById(id);
const node = (tag, text, className = '') => Object.assign(document.createElement(tag), { textContent: text, className });
let exams = [];
function render() {
  const selected = exams.filter((exam) => (byId('semester-filter').value === 'all' || String(exam.semester) === byId('semester-filter').value)
    && (byId('assessment-filter').value === 'all' || exam.assessment === byId('assessment-filter').value));
  const completed = selected.reduce((sum, exam) => sum + exam.progress.completedMinutes, 0);
  const total = selected.reduce((sum, exam) => sum + exam.progress.totalPlannedMinutes, 0);
  const remaining = selected.reduce((sum, exam) => sum + exam.plan.unscheduled.reduce((s, item) => s + item.minutes, 0), 0);
  byId('report-stats').replaceChildren(...[['Assessments', selected.length], ['Completed minutes', completed], ['Completion', (total ? Math.round(100 * completed / total) : 0) + '%'], ['Unscheduled minutes', remaining]].map(([label, value], index) => {
    const card = node('div', '', 'dash-card'); card.dataset.accent = index; card.append(node('dt', label), node('dd', value)); return card;
  }));
  byId('roadmap').replaceChildren(...selected.map((exam) => {
    const item = node('li', '');
    const next = exam.plan.sessions.filter((s) => s.status === 'planned').sort((a, b) => a.sessionDate.localeCompare(b.sessionDate))[0];
    const link = node('a', 'Open study plan'); link.href = '/plan.html?examId=' + exam.id;
    const date = node('time', exam.examDate); date.dateTime = exam.examDate;
    item.append(date, node('h3', exam.title), node('p', (exam.semester ? 'Semester ' + exam.semester : 'Unassigned semester') + ' · ' + exam.assessment, 'dash-muted'),
      node('p', next ? 'Next study · ' + next.sessionDate + ' · ' + next.chapterName + ' · ' + next.plannedMinutes + ' min' : 'No planned session remaining.'),
      node('p', Math.max(0, exam.progress.totalPlannedMinutes - exam.progress.completedMinutes) + ' min remaining · ' + exam.progress.percentage + '% complete'), link);
    return item;
  }));
  byId('report-rows').replaceChildren(...selected.map((exam) => {
    const row = node('tr', '');
    for (const value of [(exam.semester || 'Unassigned') + ' · ' + (exam.courseName || exam.title), exam.assessment, exam.examDate,
      exam.progress.completedMinutes + ' / ' + exam.progress.totalPlannedMinutes, exam.progress.percentage + '%', exam.plan.unscheduled.reduce((sum, item) => sum + item.minutes, 0)]) row.append(node('td', value));
    return row;
  }));
  const labels = [...byId('report-rows').closest('table').querySelectorAll('th')].map((cell) => cell.textContent);
  for (const row of byId('report-rows').rows) [...row.cells].forEach((cell, index) => { cell.dataset.label = labels[index]; });
  byId('report-status').textContent = selected.length ? 'Generated ' + new Date().toLocaleString() : 'No assessments in this selection. Add an exam from the dashboard.';
  byId('report-content').hidden = false;
}
async function load() {
  byId('error').hidden = true; byId('report-retry').hidden = true;
  try {
    const list = await listExams();
    exams = await Promise.all(list.map(async (exam) => {
      const [plan, progress] = await Promise.all([getPlan(exam.id), getProgress(exam.id)]);
      return { ...exam, plan, progress };
    }));
    exams.sort((a, b) => a.examDate.localeCompare(b.examDate)); render();
  } catch (error) { byId('error').textContent = error.message; byId('error').hidden = false; byId('report-status').textContent = ''; byId('report-retry').hidden = false; }
}
for (let semester = 1; semester <= 12; semester++) byId('semester-filter').add(new Option('Semester ' + semester, semester));
for (const id of ['semester-filter', 'assessment-filter']) byId(id).addEventListener('change', render);
byId('print-report').addEventListener('click', () => window.print());
byId('report-retry').addEventListener('click', load);
if (requireLogin()) load();
