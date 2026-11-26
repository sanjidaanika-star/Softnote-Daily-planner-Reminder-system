# Softnote: Daily planner & reminder system

Softnote is an exam study planner. You add an exam with its date, chapters and how many minutes you can study each day. Softnote splits the chapters into daily sessions (harder chapters get more time), counts down to every CT, Mid and Final, and moves missed sessions to the free days you have left.

Live site: <https://softnote-daily-planner-reminder-sys.vercel.app>

## Features

- **Study plans:** difficulty-weighted daily sessions, revision sessions, one-click Done / Missed, automatic rescheduling of missed work, and a list of work that does not fit before the exam.

- **Dashboard:** today's sessions, the closest exam, overall progress and simple charts. New users see a three-step getting-started guide.
- **Exams and courses:** semester (1–12), course and assessment type (CT, Mid, Final, Other) for every exam, with a live countdown.
- **Chapters, history and report:** finished and remaining chapters per course, a study history, and a printable roadmap and report.
- **Reminders:** a daily reminder for unfinished sessions and a reminder the day before each exam, in an in-app inbox and as browser push notifications.
- **Online exams:** administrators publish timed MCQ exams; students answer with autosave, the server auto-submits at the deadline, and administrators see a score report.
- **Administration:** account list, enable or disable accounts, password-reset links and an activity log.

Unscheduled work includes minutes that do not fit the first plan, as well as missed-session overflow. Initial shortfalls are calculated from saved chapter targets and sessions, so they survive reloads without duplicate overflow records and remain included in progress totals.

One feature is built but hidden in the interface because it needs an outside service: email reminders (Brevo or SMTP). See [Optional features](#optional-features).

## Tech stack

| Part | Technology |
| --- | --- |
| Backend | Java 17, built-in `HttpServer`, JDBC |
| Database | MySQL 8 |
| Frontend | Plain HTML, CSS and JavaScript modules (no build step) |
| Hosting | Vercel (frontend, proxies API calls) and Railway (Java API in Docker) |

## Project structure

```text
backend/    Java source: api, db, model, scheduler, reminder, service, test
frontend/   HTML pages, css/style.css and js/ modules
database/   schema.sql for new databases and add_*.sql migrations
config/     *.example files; real config files are git-ignored
lib/        Bundled jars (Gson, web push, mail)
scripts/    test.ps1 runs every test
docs/       Project status, dependency notes and the architecture diagram
```

## Run locally

You need JDK 17 or later, MySQL 8 and the MySQL Connector/J jar. Run these commands in PowerShell from the project root.

1. Create the database and load the schema. Replace `root` with your MySQL admin account if needed.

   ```powershell
   mysql --user=root --password --execute="CREATE DATABASE softnote_dev CHARACTER SET utf8mb4"
   Get-Content -Raw database/schema.sql | mysql --user=root --password --database=softnote_dev
   ```

2. Create your database config and fill in `db.user` and `db.password`.

   ```powershell
   Copy-Item config/database.example.properties config/database.properties
   notepad config/database.properties
   ```

   The account needs SELECT, INSERT and UPDATE on `softnote_dev`, plus DELETE on `chapters`, `study_sessions`, `exams`, `unscheduled_sessions`, `reminders` and `push_subscriptions`.

3. Compile and start the server. Set `$connectorJar` to the full path of your Connector/J jar.

   ```powershell
   $connectorJar = 'C:\path\to\mysql-connector-j.jar'
   New-Item -ItemType Directory -Path out -Force | Out-Null
   javac --release 17 -cp "lib/*" -d out (Get-ChildItem backend -Recurse -Filter *.java).FullName
   java -cp "out;lib/*;$connectorJar" backend.api.ApiServer
   ```

4. Open <http://127.0.0.1:8080/>, create an account and sign in. Press Ctrl+C to stop the server.

The server listens on `127.0.0.1:8080`. You can pass a different port and a properties file path as command-line arguments.

### Updating an existing database

New databases get everything from `schema.sql`. For an older database, apply the migrations it is missing, once each, with an account that can create tables:

| Migration | Adds |
| --- | --- |
| `add_unscheduled_sessions.sql` | Work that does not fit before the exam |
| `add_reminders.sql` | Reminder settings, inbox, browser subscriptions and deliveries |
| `add_admin_and_course_metadata.sql` | Roles, semester, course and assessment fields |
| `add_online_exams.sql` | Online exams and attempts |

```powershell
Get-Content -Raw database/add_online_exams.sql | mysql --user=root --password --database=softnote_dev
```

## Administrators

Registration always creates a student account. To make an existing account an administrator, run this in MySQL with the account's email:

```sql
UPDATE users SET role = 'admin' WHERE email = 'name@example.com';
```

After signing in, administrators go to `/admin.html` and students to `/dashboard.html`. The server checks the role on every protected request. Administrators can still open the study dashboard for their own exams.

**Password resets:** a user requests a reset on `/reset-password.html`. After checking the person's identity outside the app, an administrator issues a one-time link from the admin panel and shares it privately. Links expire after 30 minutes, and saving a new password signs the user out everywhere.

## Reminders

Open **Reminders** from the sidebar, choose a daily time and time zone, and switch on unfinished-session reminders, exam-day-before reminders or both. Every reminder appears in the in-app inbox. The server checks once a minute and must stay running.

**Browser push** needs a VAPID key pair. Generate it once after compiling; the keys are written to the git-ignored `config/reminders.properties`:

```powershell
java -cp "out;lib/*" backend.reminder.GeneratePushKeys
```

Then open **Reminders → Enable this browser** and allow notifications. Failed deliveries are retried every five minutes, up to three times. Signing out removes the browser subscription.

## Online exams

Administrators open **Online exams**, enter a title, course, semester, opening and closing time, duration (1–240 minutes) and 1–50 questions with four options each. Publishing sends the exam to every active student and adds an in-app notification. Published papers cannot be edited.

Students start the exam inside its window. Answers save automatically, and refreshing resumes the same attempt. The server ends the attempt at the closing time or when the duration runs out, whichever comes first, and grades it even if the browser is closed. Correct answers are shown only after submission.

## Deployment

The Vercel project serves `frontend/` and forwards API routes to Railway (see `frontend/vercel.json`). Railway builds the `Dockerfile`, which compiles the backend and downloads Connector/J 9.7.0 with a checksum.

Set these variables on the Railway service:

| Variable | Value |
| --- | --- |
| `SOFTNOTE_DB_URL` | JDBC URL of the MySQL database |
| `SOFTNOTE_DB_USER` | Database user |
| `SOFTNOTE_DB_PASSWORD` | Database password |
| `SOFTNOTE_APP_URL` | Public site URL, used in reminder links |
| `SOFTNOTE_PUSH_PUBLICKEY` | VAPID public key |
| `SOFTNOTE_PUSH_PRIVATEKEY` | VAPID private key (server only) |
| `SOFTNOTE_PUSH_SUBJECT` | Contact URI, for example `mailto:you@example.com` |

Keep keys in Railway variables or git-ignored files. Never commit them.

## Optional features

This is implemented in the backend but hidden in the interface. To turn it back on, configure it and undo the matching change described in [docs/project-status.md](docs/project-status.md).

**Email reminders.** Set `SOFTNOTE_MAIL_PROVIDER=brevo`, `SOFTNOTE_MAIL_FROM` (a verified sender) and `SOFTNOTE_BREVO_APIKEY`. SMTP is also supported (`SOFTNOTE_MAIL_PROVIDER=smtp` with `SOFTNOTE_SMTP_USER`, `SOFTNOTE_SMTP_FROM` and `SOFTNOTE_SMTP_PASSWORD`), but Railway's Free, Trial and Hobby plans block outbound SMTP. See `config/reminders.properties.example`.

## Tests

Create a test database, `softnote_test`, from `database/schema.sql`, and point a git-ignored properties file at it. Never point tests at a real database; the integration tests check the database name before changing data.

```powershell
./scripts/test.ps1 -ConnectorJar C:\path\to\mysql-connector-j.jar -TestConfig config/database.test.properties
```

This compiles the backend with Java 17, runs the seven Java test programs (scheduling, rescheduling, authentication, administration, reminders, exam deletion and online exams) and runs the Node frontend session tests. Tests use fake email and push clients, so nothing is sent.

## More documentation

- [Project status](docs/project-status.md): what is built and what is hidden
- [Reminder dependencies](docs/reminder-dependencies.md): extra jars and their checksums
- [Architecture diagram](docs/architecture.drawio): open in draw.io
