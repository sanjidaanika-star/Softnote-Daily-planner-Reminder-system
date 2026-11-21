# Softnote : Daily planner & Reminder system

## Study reminders and email notifications

Open **Reminders** from the dashboard bell or sidebar. Choose a daily time and IANA time zone, enable unfinished-study and/or next-day exam reminders, and save. Every scheduled reminder appears in the account's notification inbox. Daily reminders exclude completed and missed sessions. Exam reminders cover all assessment types including CT, Mid and Final. The server checks once per minute and catches up within two hours of today's chosen time. It must remain running. Settings are opt-in, and are isolated per account. Login expiry preserves push subscriptions. A new login reassigns the current browser subscription to the newly signed-in account; failed reassignment disconnects it. Explicit sign-out removes the browser subscription from the server and browser.

Apply `database/add_reminders.sql` once to an existing database, using a migration account with CREATE permission. Fresh installs include these tables in `database/schema.sql`. The migration only adds reminder settings, browser subscriptions, reminder events and delivery records. It does not edit exam or session data.

Copy `config/reminders.properties.example` to `config/reminders.properties` (ignored by git). On Railway, use service Variables instead:

| Variable | Value |
| --- | --- |
| `SOFTNOTE_APP_URL` | `https://softnote-daily-planner-reminder-sys.vercel.app` |
| `SOFTNOTE_MAIL_PROVIDER` | `brevo` (default, HTTPS API) or `smtp` |
| `SOFTNOTE_MAIL_FROM` | Verified Brevo sender email |
| `SOFTNOTE_BREVO_APIKEY` | Brevo API key; never put it in frontend code |
| `SOFTNOTE_PUSH_PUBLICKEY` | Stable VAPID public key |
| `SOFTNOTE_PUSH_PRIVATEKEY` | Stable VAPID private key, server only |
| `SOFTNOTE_PUSH_SUBJECT` | Contact URI, for example `mailto:your-email@example.com` |

For **Brevo**, create a free account at [Brevo](https://www.brevo.com/), verify a sender under Senders & Domains, create an **API key** (not an SMTP key), and set the variables above on the Java backend service. Complete any account review required by the provider. A custom authenticated domain improves deliverability; free sender addresses may be replaced by Brevo with a compliant address. Recipients can still use Gmail. The [Free plan](https://help.brevo.com/hc/en-us/articles/208580669-FAQs-What-are-the-limits-of-the-Free-plan) currently allows 300 sends/day. Keys stay on the server. Email goes only to the signed-in account's registered email; reserved test addresses cannot enable email delivery.

For **Gmail SMTP**, set `SOFTNOTE_MAIL_PROVIDER=smtp`, `SOFTNOTE_SMTP_USER`, `SOFTNOTE_SMTP_FROM` (the same sender Gmail), and `SOFTNOTE_SMTP_PASSWORD` (a dedicated [App Password](https://support.google.com/accounts/answer/185833), which requires 2-Step Verification). The defaults are `smtp.gmail.com:587`, mandatory STARTTLS and certificate identity validation. **Railway Free, Trial and Hobby disable outbound SMTP**; use the HTTPS provider on those plans. See [Railway's outbound networking documentation](https://docs.railway.com/networking/outbound-networking).

Generate VAPID keys once locally, after compiling:

```powershell
java -cp "out;lib/*" backend.reminder.GeneratePushKeys
```

The keys are written to the ignored `config/reminders.properties`, without printing the private key. Set the contact URI and public app URL. Transfer the keys securely to the backend environment and retain them across deployments. On the Reminders page, select **Enable this browser**, grant browser notification permission, check browser delivery and save settings. This installs a local service worker, without intercepting or caching existing requests. Push can arrive with the tab closed when supported by the browser/OS; it requires permission, internet and supported background delivery. Signing out unsubscribes the browser. Other devices remain independent.

Use **Send a test reminder**, then **Refresh** to inspect delivery status. `sent` means the SMTP/API/push service accepted the message, not that it was placed in the Gmail inbox or displayed by the OS. Check Spam and provider logs if needed. Failed deliveries retry at five-minute intervals, up to three attempts. Stale browser subscriptions are removed. The persistent outbox and unique event keys prevent routine repeat sends across restarts; SMTP is at-least-once and an interrupted connection after acceptance can still result in a duplicate. Brevo requests use a stable idempotency key.

New bearer-protected endpoints are `GET /reminders`, `PUT /reminders/settings`, `POST` and `DELETE /reminders/subscription`, `POST /reminders/test`, and `PATCH /reminders/{id}/read`. The last route returns 404 for another account's reminder. Test requests are limited to one per account per minute. No recipient override is accepted.

Run the new integration tests against `softnote_test` after applying the migration:

```powershell
javac --release 17 -cp "lib/*" -d out (Get-ChildItem backend -Recurse -Filter *.java).FullName
java -cp "out;lib/*;$connectorJar" backend.test.ReminderTest
```

Tests use fake transports and never send real email/push. They cover time zones, invalid inputs, authentication, inbox ownership, deduplication, completed-session exclusion, opt-out, retries, expired subscriptions, AES128GCM encryption and VAPID signing. Additional jars and their Maven coordinates/checksums are documented in `docs/reminder-dependencies.md`; their license notices are included in the jars.

## Administration and semester planning

For an existing installation, apply `database/add_admin_and_course_metadata.sql` once before deploying this version. Fresh installations use `database/schema.sql`, which includes the same changes.

Each exam can store a semester from 1 to 12, a course, and a CT, Mid, Final or Other assessment. Existing exams appear as unassigned until edited. Course semester assignments are chosen by the student. The dashboard filters all panels by semester and assessment. `/report.html` shows a chronological roadmap and study totals, with a Print / save PDF button.

Every assessment has a live countdown in days, hours, minutes and seconds. Since exams store a date, the deadline is midnight at the start of exam day in the browser's local timezone. The dashboard refreshes its day-dependent panels when the calendar day changes.

The dashboard groups active and completed chapters by course and semester. Opening a course in Completed chapters reveals the chapter names and their CT/Mid/Final assessment. Completion is derived from saved completed study sessions against the chapter's difficulty-weighted study target. A partially studied or capacity-limited chapter stays active. Revision minutes are shown separately and do not delay main-study completion. Saving a session updates the lists automatically, and returning to a visible dashboard reloads server data.

Administrators and students use the same public home and login page. After a successful login, the server-returned role sends administrators to `/admin.html` and users to `/dashboard.html`. Administrators can still choose **Study dashboard** to use their own study tools. The server checks the role and account status on every protected request; the redirect grants no permission. Registration always creates a user. To appoint an administrator, an operator must update the chosen existing account in MySQL using a prepared statement equivalent to `UPDATE users SET role = 'admin' WHERE email = ?`. The public API cannot assign administrator roles.

The administration page shows account creation dates, active status, exam and note counts, quiz failure counts, password reset requests, and an audit log. Disabling a user immediately invalidates their sessions. Administrator accounts cannot be disabled through this panel.

Password recovery uses an administrator assisted workflow. The user submits their email on `/reset-password.html`. After verifying the owner's identity outside the app, the administrator issues a 30 minute reset link and privately shares it with that owner. Tokens are stored as SHA-256 hashes and can be used once. Replacing a link invalidates the previous link. Saving a password invalidates all of that user's login sessions. Email delivery is manual because SMTP has not been configured.

The hosted Java API reads `SOFTNOTE_DB_URL`, `SOFTNOTE_DB_USER`, and `SOFTNOTE_DB_PASSWORD`. Local development continues to use the ignored `config/database.properties`. The Dockerfile includes Connector/J 9.7.0 from Maven Central with a SHA-256 checksum. Vercel proxies API routes to Railway through `frontend/vercel.json`.

Softnote is an adaptive exam study planner. Its goal is to help students organize exam preparation, track their progress, and adjust what they study next based on that progress.

## Tech stack

- Backend: Java
- Frontend: HTML, CSS, and JavaScript
- Database: MySQL, accessed from Java through JDBC

## Local database setup

Run these PowerShell commands from the project root. Install JDK 17 or later, start MySQL 8.4, and have the MySQL client and Connector/J JAR available. Replace `root` with your MySQL administrator account if needed.

```powershell
mysql --user=root --password --execute="CREATE DATABASE softnote_dev CHARACTER SET utf8mb4"
Get-Content -Raw database/schema.sql | mysql --user=root --password --database=softnote_dev
Copy-Item config/database.example.properties config/database.properties
notepad config/database.properties
```

In the properties file, keep `db.url=jdbc:mysql://localhost:3306/softnote_dev` if MySQL uses the default port. Set `db.user` and `db.password` to your local MySQL account. The account needs SELECT, INSERT, and UPDATE on `softnote_dev`, plus DELETE on `chapters` and `study_sessions` for exam edits. Save the file, then replace the example Connector/J path below with your absolute path.

```powershell
$connectorJar = 'C:\path\to\mysql-connector-j.jar'
New-Item -ItemType Directory -Path out -Force | Out-Null
$sources = Get-ChildItem -LiteralPath backend -Recurse -File -Filter '*.java'
javac --release 17 -cp "lib/*" -d out $sources.FullName
java -cp "out;lib/*;$connectorJar" backend.api.ApiServer
```

Keep the server running and open [Softnote](http://127.0.0.1:8080/). Use **Create an account** to open registration, sign in to reach the dashboard, and use **Add an exam** to create an exam and open its plan. The plan links to **Progress and history**. Press Ctrl+C in PowerShell to stop the server.

For an existing Phase 5 database, run the migration once in place of loading the fresh schema into that database.

```powershell
Get-Content -Raw database/add_unscheduled_sessions.sql | mysql --user=root --password --database=softnote_dev
```

## Authentication integration tests

Create a separate MySQL database named `softnote_test` and run `database/schema.sql` in it. Copy the example configuration to `config/database.test.properties` and point it at `softnote_test` with a user that can read, insert, and delete test rows. Run these PowerShell commands from the project root after setting `$connectorJar` to the path of your Connector/J JAR.

```powershell
New-Item -ItemType Directory -Path out -Force | Out-Null
$sources = Get-ChildItem -LiteralPath backend -Recurse -File -Filter '*.java'
javac --release 17 -cp "lib/*" -d out $sources.FullName
java -cp "out;lib/*;$connectorJar" backend.test.AuthServiceIntegrationTest
```

## Local HTTP API

After compiling, run the scheduler tests with:

```powershell
java -cp out backend.test.DifficultyPlanStrategyTest
java -cp out backend.test.ReschedulerTest
```

The server listens on `127.0.0.1:8080`. Pass a port and an optional properties file path as command-line arguments to override the defaults.

| Endpoint | JSON request or query | Success |
| --- | --- | --- |
| `POST /register` | `name`, `email`, `password` | `201` with user ID |
| `POST /login` | `email`, `password` | `200` with user profile and token |
| `GET /exams` | Bearer token | `200` with the signed-in user's exams |
| `DELETE /exams/{id}` | Bearer token; owner only | `200` after deleting the exam, chapters, sessions/history, unscheduled work and linked exam reminders in one transaction; other owners get `404` |
| `POST /exams` | `title`, ISO `examDate`, integer `dailyMinutes`, and a `chapters` array of objects with `name`, `difficulty`, and integer `estimatedMinutes` | `201` with exam ID |
| `GET /plan?examId=...` | Exam ID from the creation response | `200` with `sessions` and `unscheduled` arrays. The first request generates the plan. |
| `PATCH /sessions/{id}/status` | `status` set to `completed` or `missed` | `200` with the updated session |
| `GET /progress?examId=...` | Exam ID | `200` with total planned minutes, completed minutes, and percentage |
| `GET /history` | Bearer token | `200` with completed and missed sessions, newest session date first |

Send JSON bodies with `Content-Type: application/json`. Send the login token in `Authorization: Bearer <token>` for exam, plan, status, progress, and history requests. Tokens are held in memory for up to eight hours and are cleared when the server stops.

To remove an incorrect exam/plan, open **My exams → Delete**. A confirmation explains that deletion is permanent. Cancel keeps the exam unchanged. Uploaded notes, quizzes and other exams are preserved. Run `backend.test.DeleteExamTest` against `softnote_test` to verify ownership, related-row cleanup and rollback on failure.

Marking a session missed preserves the original row for history. Replacement sessions use free time starting today. Progress counts replacement and unscheduled minutes once, so a missed session does not inflate the total.

## Notes and Gemini quizzes

Apply `database/add_notes_and_quizzes.sql` once to an existing Softnote database. Fresh installations get the notes and quiz tables from `database/schema.sql`. The bundled jars in `lib/` are Gson 2.13.2 for JSON and Apache PDFBox 3.0.6 for local PDF text extraction.

Copy `config/gemini.properties.example` to `config/gemini.properties`. Set `gemini.apiKey` locally, set `gemini.model` to a JSON-output-capable model available in your Google AI Studio project, and set `gemini.timeoutSeconds` to a value such as `45` (allowed range 1–55 seconds). The key stays in the ignored server config. Check your project's active model and limits in [Google AI Studio](https://aistudio.google.com/usage), Google's [pricing](https://ai.google.dev/gemini-api/docs/pricing), and [rate limits](https://ai.google.dev/gemini-api/docs/rate-limits).

For the Railway/Docker deployment, no config file is required. Set `SOFTNOTE_GEMINI_API_KEY`, `SOFTNOTE_GEMINI_MODEL`, and `SOFTNOTE_GEMINI_TIMEOUT_SECONDS=45` on the backend service. Environment values take precedence over the ignored properties file. The model name stays configurable and is not hardcoded. Without a configured key/model, the rest of Softnote works, while quiz generation returns a configuration error.

Open **Notes & quizzes** from the dashboard. Upload a text-based PDF of up to 8 MB, choose a question count, and select **Create questions**. Softnote stores the extracted text and sends up to 60,000 characters to Gemini. The PDF stays local to the upload request. Gemini's free tier can use submitted content to improve its products, so use notes you are comfortable sharing with Google.

| Endpoint | Request |
| --- | --- |
| `GET /notes` | List the signed-in user's notes |
| `POST /notes` | Raw PDF body with `Content-Type: application/pdf` and a percent-encoded `X-Note-Title` header |
| `POST /notes/{id}/quizzes` | JSON with `count` from 15 to 20 |
| `GET /quizzes/{id}` | Read status and questions. Answers appear only after an attempt |
| `POST /quizzes/{id}/questions/{qid}/answer` | JSON with `chosenIndex` from 0 to 3. Retries return the original answer |
| `GET /quizzes/{id}/score` | Read the score for submitted answers |

All of these endpoints require the login bearer token. To run the quiz integration tests, load `database/schema.sql` into a separate database named `softnote_quiz_test` and point a local properties file at it. The tests create fixture records in that database and use a fake generator.

```powershell
java -cp "out;lib/*;$connectorJar" backend.test.QuizTest C:\path\to\quiz-test.properties
```

## Run all checks

Create the separate `softnote_test` and `softnote_quiz_test` databases from `database/schema.sql`. Use a local migration/test user with DDL privileges for the delete rollback test's disposable guard table. Never point these tests at production. Store their connection settings in ignored local files or outside the repository. The integration tests validate the database name before changing test data. Quiz tests leave disposable fixture records in `softnote_quiz_test`; recreate that test database from the schema when a fresh test environment is needed.

```powershell
./scripts/test.ps1 -ConnectorJar C:\path\to\mysql-connector-j.jar -TestConfig config/database.test.properties -QuizConfig C:\path\to\quiz-test.properties
```

This compiles every Java file with Java 17, runs all nine Java test programs, and runs five Node tests for login expiry, browser subscription ownership, and explicit sign-out. See [project status](docs/project-status.md) for implemented features and external activation requirements. The editable [architecture diagram](docs/architecture.drawio) opens in draw.io.

## Administrator-published online exams

Existing installations must apply `database/add_online_exams.sql` before deploying this version. Fresh `schema.sql` installations include the same tables. Online assessments are separate from personal study-plan exams.

Open **Admin panel → Online exams** to enter a title, course, semester, opening/closing time, duration (1–240 minutes) and 1–50 MCQ questions. Each question has four distinct options, one correct answer and one mark. Publishing freezes the paper and enrolls all currently active student accounts; accounts registered later are not automatically enrolled. Semester and course are labels, not recipient filters in this version. Publishing and in-app notifications commit or roll back together. External email/browser delivery requires the existing provider configuration and each recipient's opt-in; opening an exam does not opt anyone in.

Students open **Dashboard → Online exams**, start within the published window, and answer with automatic saving. Refreshing resumes the same attempt and deadline. The Java server enforces the earlier of closing time or start + duration. At expiry, a background worker grades saved answers (checked every five seconds), including when the browser is closed. Late unsaved answers are not accepted. Submit is idempotent: concurrent/retried submissions do not change a final score. The result shows correct answers only after submission. Admin reports show invited students, started/submitted state, submission time and score. There are no written questions, proctoring or anti-cheating guarantees.

| Endpoint | Access / purpose |
| --- | --- |
| `GET, POST /admin/online-exams` | Admin list / publish and notify |
| `GET /admin/online-exams/{id}/report` | Admin submission report |
| `GET /online-exams` | Signed-in student's assigned exams |
| `GET /online-exams/{id}` | Own attempt; question paper only after starting |
| `POST /online-exams/{id}/start` | Begin/resume one timed attempt |
| `PUT /online-exams/{id}/answers` | Save own answers as an array of 0–3 or null |
| `POST /online-exams/{id}/submit` | Final answers and automatic MCQ score |

Run `backend.test.OnlineExamTest` against `softnote_test` to verify role checks, recipient privacy, notification rollback, server-enforced deadlines, answer secrecy, autosave/resume, concurrent submission, expired auto-submit and reports. The test never delivers external notifications.
