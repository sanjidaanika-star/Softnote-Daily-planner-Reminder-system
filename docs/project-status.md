# Project status

Softnote is a Java 17 / JDBC / MySQL application with a plain HTML, CSS and JavaScript frontend. Vercel serves the frontend and proxies authenticated API requests to the Java service on Railway.

## Implemented

- Registration, salted password hashing, bearer sessions, owner-only exam/plan/note/quiz access and a separate administrator role.
- Administrator-published timed MCQ exams, notification fan-out to currently active students, automatic answer saving, server-side auto-submit, one final submission and administrator score reports. Apply `database/add_online_exams.sql` before deploying to an existing database.
- Semester/course/assessment selection, exam creation/editing/deletion, difficulty-weighted plans, revisions, missed-session rescheduling and visible unscheduled work.
- Dashboard analytics and navigation, today's study, weekly planner, completed chapters, progress/history and roadmap/report pages.
- PDF text extraction, asynchronous Gemini quiz generation, response validation, hidden answers until submission and saved scores.
- Reminder preferences and inbox, timezone-aware scheduling, persistent delivery retries, browser push and Brevo HTTPS email delivery code. Optional SMTP delivery is also implemented.
- Browser subscription reassignment when switching accounts. Explicit sign-out disconnects the device; expired authentication preserves the reminder subscription.

## External activation still required

### Email reminders

Email delivery is not activated until the owner creates a Brevo account, verifies the sender and configures these variables on the Railway **Java backend service**:

```text
SOFTNOTE_MAIL_PROVIDER=brevo
SOFTNOTE_MAIL_FROM=<verified sender email>
SOFTNOTE_BREVO_APIKEY=<private Brevo API key>
```

1. Create and verify an account at https://onboarding.brevo.com/account/register.
2. In Brevo, open Settings → Senders, Domains & Dedicated IPs → Senders, add a sender, and verify the email.
3. Open Settings → SMTP & API → API Keys & MCP and generate an API key.
4. Paste the key directly into Railway Variables, save and deploy. Never commit it or send it in chat.
5. Sign in to Softnote with a real deliverable email address, enable email reminders and use the test reminder button. Check both the inbox and the delivery status. Reserved `.test` addresses cannot receive email.

Brevo may require transactional sending activation. Provider approval and actual recipient delivery cannot be replaced by automated tests. Browser push is configured separately and requires each browser's permission.

### Gemini

The server supports an ignored local properties file or environment-only deployment. Set `SOFTNOTE_GEMINI_API_KEY`, `SOFTNOTE_GEMINI_MODEL` and `SOFTNOTE_GEMINI_TIMEOUT_SECONDS=45`. Select a JSON-output-capable model available in the owner's Google AI Studio project and check its current quota. A real generation check remains dependent on a valid key and quota; automated quiz tests use a fake generator and never call Gemini.

## Verification

Run `scripts/test.ps1` as documented in the README. It compiles all backend sources and runs nine Java test programs plus five frontend session tests. Integration tests use dedicated test databases, never the live development/production data. Tests cover scheduling, overflow, authentication, administrator access, quiz ownership, reminder scheduling/retries, browser subscription reassignment, transactional exam deletion/rollback and timed online exam publication/submission.

The editable system diagram is `docs/architecture.drawio`.
