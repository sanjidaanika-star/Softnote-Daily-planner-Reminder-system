# Project status

Softnote is a Java 17 / JDBC / MySQL application with a plain HTML, CSS and JavaScript frontend. Vercel serves the frontend and proxies authenticated API requests to the Java service on Railway.

## Implemented

- Registration, salted password hashing, bearer sessions, owner-only exam/plan access and a separate administrator role.
- Administrator-published timed MCQ exams, notification fan-out to currently active students, automatic answer saving, server-side auto-submit, one final submission and administrator score reports. Apply `database/add_online_exams.sql` before deploying to an existing database.
- Semester/course/assessment selection, exam creation/editing/deletion, difficulty-weighted plans, revisions, missed-session rescheduling and visible unscheduled work.
- Dashboard analytics and navigation, today's study, weekly planner, completed chapters, progress/history and roadmap/report pages.
- Reminder preferences and inbox, timezone-aware scheduling, persistent delivery retries, browser push and Brevo HTTPS email delivery code. Optional SMTP delivery is also implemented.
- Browser subscription reassignment when switching accounts. Explicit sign-out disconnects the device; expired authentication preserves the reminder subscription.

## Hidden in the interface

Email reminders are not being activated, so the frontend hides the email option. The backend code is unchanged.

- **Email reminders:** the email option on `reminders.html` sits in a hidden block, and `reminders.js` always saves it as off. Browser push and the in-app inbox still work.

PDF-upload quiz generation (Google Gemini) was removed entirely rather than hidden: there is no backend code, database table, route or frontend page for it anymore.

## External activation (not planned)

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

## Verification

Run `scripts/test.ps1` as documented in the README. It compiles all backend sources and runs seven Java test programs plus five frontend session tests. Integration tests use a dedicated test database, never the live development/production data. Tests cover scheduling, overflow, authentication, administrator access, reminder scheduling/retries, browser subscription reassignment, transactional exam deletion/rollback and timed online exam publication/submission.

The editable system diagram is `docs/architecture.drawio`.
