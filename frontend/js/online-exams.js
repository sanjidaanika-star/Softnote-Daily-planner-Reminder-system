import { requireLogin, getMe, endSession, onlineExams, publishOnlineExam, onlineExamReport, viewOnlineExam, startOnlineExam, saveOnlineAnswers, submitOnlineExam } from './api.js';

const byId = id => document.getElementById(id);
const node = (tag, text, className) => Object.assign(document.createElement(tag), {textContent:text ?? '', className:className ?? ''});
const date = epoch => new Date(epoch * 1000).toLocaleString();
let admin = false, paper, answers = [], questionNumber = 0, busy = false, saveQueue = Promise.resolve(), clockOffset = 0;
function fail(error) { byId('error').textContent = error.message; byId('error').hidden = false; }
function clearError() { byId('error').hidden = true; }
function localInput(date) { return new Date(date.getTime() - date.getTimezoneOffset()*60000).toISOString().slice(0,16); }
function confirmAction(message) {
  return new Promise(resolve => {
    const dialog=document.createElement('dialog');dialog.className='delete-exam-dialog';
    const heading=node('h2','Confirm action');heading.id='exam-confirm-heading';dialog.setAttribute('aria-labelledby',heading.id);
    const actions=node('div','','online-actions'),cancel=node('button','Cancel','dash-secondary'),proceed=node('button','Continue');
    cancel.type=proceed.type='button';
    const finish=value=>{dialog.close();dialog.remove();resolve(value);};
    cancel.addEventListener('click',()=>finish(false));proceed.addEventListener('click',()=>finish(true));
    dialog.addEventListener('cancel',event=>{event.preventDefault();finish(false);});
    actions.append(cancel,proceed);dialog.append(heading,node('p',message),actions);document.body.append(dialog);dialog.showModal();cancel.focus();
  });
}

function questionEditor() {
  if (byId('question-editor').children.length >= 50) return;
  const id = ++questionNumber, field = node('fieldset', '', 'online-question');
  field.append(node('legend', 'Question ' + id));
  const label = node('label', 'Question text'), text = document.createElement('textarea');
  text.required = true; text.maxLength = 1000; text.name = 'text'; label.append(text); field.append(label);
  const options = node('div', '', 'online-form-grid');
  for (let i=0;i<4;i++) {
    const optionLabel = node('label','Option '+String.fromCharCode(65+i)), input = document.createElement('input');
    input.name = 'option'; input.maxLength = 500; input.required = true; optionLabel.append(input); options.append(optionLabel);
  }
  field.append(options);
  const correctLabel = node('label','Correct option'), select = document.createElement('select'); select.name = 'correctIndex';
  for (let i=0;i<4;i++) select.add(new Option(String.fromCharCode(65+i),i));
  correctLabel.append(select); field.append(correctLabel);
  const remove = node('button','Remove question','dash-secondary'); remove.type='button';
  remove.addEventListener('click',()=>field.remove()); field.append(remove); byId('question-editor').append(field);
}

async function load() {
  clearError(); byId('refresh-exams').disabled=true; byId('online-status').textContent='Loading exams…';
  try {
    const items = await onlineExams(admin);
    byId('online-exam-list').replaceChildren();
    for (const exam of items) {
      const card = node('article','','online-exam-item'); card.append(node('h3',exam.title));
      card.append(node('p',exam.courseName + (exam.semester?' · Semester '+exam.semester:''),'dash-muted'));
      card.append(node('p',date(exam.opensAt)+' — '+date(exam.closesAt)));
      card.append(node('p',exam.durationMinutes+' minutes · '+exam.questionCount+' questions · '+(admin?'Published':exam.submitted?'Submitted':exam.started?'In progress':'Not started')));
      const button=node('button',admin?'View submissions':exam.submitted?'View result':exam.started?'Resume exam':'View exam','dash-secondary');button.type='button';
      button.addEventListener('click',()=>admin?report(exam):open(exam.id));card.append(button);byId('online-exam-list').append(card);
    }
    if(!items.length)byId('online-exam-list').append(node('p',admin?'No exams published yet.':'No online exams assigned yet. New exams will appear here when an administrator publishes them.','dash-muted'));
    byId('online-status').textContent='';
  }catch(error){fail(error);byId('online-status').textContent='Could not load exams. Use Refresh to retry.';}
  finally{byId('refresh-exams').disabled=false;}
}

async function report(exam) {
  clearError();
  try {
    const rows=await onlineExamReport(exam.id);byId('submission-report').hidden=false;
    byId('submission-heading').textContent=exam.title+' — submissions';byId('submission-rows').replaceChildren();
    const submitted=rows.filter(row=>row.submittedAt!=null);
    byId('report-summary').textContent=submitted.length+' of '+rows.length+' students submitted. Each question is worth one mark.';
    for(const row of rows){const tr=node('tr');for(const text of [row.name+' · '+row.email,row.submittedAt!=null?'Submitted':row.startedAt!=null?'In progress':'Not started',row.submittedAt!=null?date(row.submittedAt):'—',row.score!=null?row.score+' / '+exam.questionCount:'—'])tr.append(node('td',text));byId('submission-rows').append(tr);}
    if(!rows.length){const tr=node('tr'),td=node('td','No students were active when this exam was published.');td.colSpan=4;tr.append(td);byId('submission-rows').append(tr);}
    byId('submission-report').scrollIntoView({block:'start'});
  }catch(error){fail(error);}
}

async function open(id) {
  if(busy || (paper?.startedAt && !paper.submittedAt && !await confirmAction('Leave this exam view? The timer continues and saved answers will be submitted at the deadline.')))return;
  clearError();
  try{await saveQueue;render(await viewOnlineExam(id));byId('exam-attempt').scrollIntoView({block:'start'});}catch(error){fail(error);}
}
function render(data) {
  paper=data;clockOffset=data.serverNow*1000-Date.now();answers=(data.questions??[]).map(q=>q.chosenIndex??null);
  byId('exam-attempt').hidden=false;byId('paper-title').textContent=data.title;
  byId('paper-info').textContent=data.courseName+' · '+data.durationMinutes+' minutes · Opens '+date(data.opensAt)+' · Closes '+date(data.closesAt);
  byId('start-exam').hidden=data.startedAt!=null || data.serverNow>=data.closesAt;
  byId('start-exam').disabled=data.serverNow<data.opensAt;
  byId('attempt-notice').textContent=data.submittedAt!=null?'Submitted '+date(data.submittedAt):data.startedAt!=null?'Answers save as you select them. At the deadline the server submits the last saved answers, even if you close this page.':data.serverNow>=data.closesAt?'This exam has closed.':data.serverNow<data.opensAt?'The exam has not opened yet.':'Start when ready. Your timer begins immediately.';
  byId('answer-form').hidden=data.startedAt==null || data.submittedAt!=null;
  byId('exam-result').hidden=data.submittedAt==null;
  byId('save-state').textContent='';byId('retry-save').hidden=true;
  const target=data.submittedAt!=null?byId('exam-result'):byId('answer-questions');target.replaceChildren();
  if(data.submittedAt!=null)target.append(node('h3','Score: '+data.score+' / '+data.questionCount+' ('+Math.round(data.percentage)+'%)'));
  for(const [i,q] of (data.questions??[]).entries()){
    const field=node('fieldset','','online-question');field.append(node('legend',(i+1)+'. '+q.text));
    for(const [index,option] of q.options.entries()){
      const label=node('label','','online-answer'),input=document.createElement('input');input.type='radio';input.name='question-'+i;input.value=index;input.checked=answers[i]===index;input.disabled=data.submittedAt!=null;
      label.append(input,node('span',option));field.append(label);
      input.addEventListener('change',()=>{answers[i]=index;queueSave();});
    }
    if(data.submittedAt!=null)field.append(node('p','Your answer: '+(q.chosenIndex==null?'Not answered':q.options[q.chosenIndex])+'. Correct answer: '+q.options[q.correctIndex]+'.',q.chosenIndex===q.correctIndex?'online-correct':'online-incorrect'));
    target.append(field);
  }
  tick();
}
function queueSave() {
  const id=paper.id,snapshot=[...answers];byId('save-state').textContent='Saving answers…';
  saveQueue=saveQueue.then(async()=>{
    try{
      const result=await saveOnlineAnswers(id,snapshot);
      if(paper.id!==id)return;
      if(result.submittedAt!=null){render(result);return;}
      byId('save-state').textContent='Answers saved.';byId('retry-save').hidden=true;
    }catch(error){byId('save-state').textContent='Answers could not be saved. Retry before the timer ends.';byId('retry-save').hidden=false;fail(error);}
  });
}
async function submit(automatic=false) {
  if(!paper || paper.submittedAt!=null || busy)return;
  if(!automatic && !await confirmAction('Submit this exam? Answers cannot be changed after submission.'))return;
  busy=true;byId('submit-exam').disabled=true;
  const id=paper.id;
  try{await saveQueue;const result=await submitOnlineExam(id,[...answers]);render(result);await load();}
  catch(error){fail(error);byId('attempt-notice').textContent='Submission could not be confirmed. Retry; saved answers remain on the server.';}
  finally{busy=false;byId('submit-exam').disabled=false;}
}
function tick() {
  if(!paper)return;
  const now=Math.floor((Date.now()+clockOffset)/1000);
  if(paper.startedAt==null){byId('start-exam').disabled=now<paper.opensAt || now>=paper.closesAt;byId('exam-timer').textContent=now<paper.opensAt?'Opens in '+Math.ceil((paper.opensAt-now)/60)+' min':'';return;}
  if(paper.submittedAt!=null){byId('exam-timer').textContent='Submitted';return;}
  const left=Math.max(0,paper.deadline-now);byId('exam-timer').textContent=Math.floor(left/60)+':'+String(left%60).padStart(2,'0')+' remaining';
  if(left===0){for(const input of byId('answer-questions').querySelectorAll('input'))input.disabled=true;if(!busy && now%5===0)submit(true);}
}
byId('add-question').addEventListener('click',questionEditor);
byId('refresh-exams').addEventListener('click',load);
byId('retry-save').addEventListener('click',queueSave);
byId('answer-form').addEventListener('submit',event=>{event.preventDefault();submit();});
byId('start-exam').addEventListener('click',async()=>{byId('start-exam').disabled=true;try{render(await startOnlineExam(paper.id));await load();}catch(error){fail(error);tick();}});
byId('sign-out').addEventListener('click',async()=>{if(paper?.startedAt && !paper.submittedAt && !await confirmAction('Sign out? The exam timer keeps running.'))return;await saveQueue;await endSession();location.replace('/login.html');});
byId('publish-form').addEventListener('submit',async event=>{
  event.preventDefault();clearError();const form=event.currentTarget,button=byId('publish-exam');
  const values=Object.fromEntries(new FormData(form));
  const questions=[...byId('question-editor').children].map(field=>({text:field.querySelector('textarea').value,options:[...field.querySelectorAll('input[name="option"]')].map(input=>input.value),correctIndex:Number(field.querySelector('select').value)}));
  if(!questions.length){fail(new Error('Add at least one question.'));return;}
  if(!await confirmAction('Publish this exam and notify all currently active students? The published questions cannot be edited.'))return;
  button.disabled=true;
  try{
    const result=await publishOnlineExam({...values,semester:Number(values.semester),durationMinutes:Number(values.durationMinutes),opensAt:Math.floor(new Date(values.opensAt).getTime()/1000),closesAt:Math.floor(new Date(values.closesAt).getTime()/1000),questions});
    form.reset();questionNumber=0;byId('question-editor').replaceChildren();questionEditor();await load();byId('online-status').textContent='Exam published. '+result.notifiedStudents+' students received an in-app notification.';
  }catch(error){fail(error);}finally{button.disabled=false;}
});
if(requireLogin()){
  try{
    const me=await getMe();admin=me.role==='admin';byId('publisher').hidden=!admin;
    byId('workspace-role').textContent=admin?'Admin workspace':'Student workspace';
    if(admin)document.title='Admin · Online exams | Softnote';
    byId('account-home').href=byId('back-home').href=admin?'/admin.html':'/dashboard.html';byId('back-home').textContent=admin?'Admin panel':'Dashboard';
    byId('exam-list-heading').textContent=admin?'Published exams':'Your online exams';
    for(let i=1;i<=12;i++)byId('online-semester').add(new Option('Semester '+i,i));
    byId('publish-form').elements.opensAt.value=localInput(new Date(Date.now()+5*60000));byId('publish-form').elements.closesAt.value=localInput(new Date(Date.now()+65*60000));
    questionEditor();await load();const id=new URLSearchParams(location.search).get('examId');if(id && !admin)await open(id);
    setInterval(tick,1000);
  }catch(error){fail(error);byId('online-status').textContent='Could not load your account. Refresh to retry.';}
}
