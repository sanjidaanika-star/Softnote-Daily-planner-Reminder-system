# Softnote : Daily planner & Reminder system

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
Get-Content -Raw database/phase6.sql | mysql --user=root --password --database=softnote_dev
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
| `POST /exams` | `title`, ISO `examDate`, integer `dailyMinutes`, and a `chapters` array of objects with `name`, `difficulty`, and integer `estimatedMinutes` | `201` with exam ID |
| `GET /plan?examId=...` | Exam ID from the creation response | `200` with `sessions` and `unscheduled` arrays. The first request generates the plan. |
| `PATCH /sessions/{id}/status` | `status` set to `completed` or `missed` | `200` with the updated session |
| `GET /progress?examId=...` | Exam ID | `200` with total planned minutes, completed minutes, and percentage |
| `GET /history` | Bearer token | `200` with completed and missed sessions, newest session date first |

Send JSON bodies with `Content-Type: application/json`. Send the login token in `Authorization: Bearer <token>` for exam, plan, status, progress, and history requests. Tokens are held in memory for up to eight hours and are cleared when the server stops.

Marking a session missed preserves the original row for history. Replacement sessions use free time starting today. Progress counts replacement and unscheduled minutes once, so a missed session does not inflate the total.

## Notes and Gemini quizzes

Apply `database/phase8b.sql` once to an existing Softnote database. Fresh installations get the notes and quiz tables from `database/schema.sql`. The bundled jars in `lib/` are Gson 2.13.2 for JSON and Apache PDFBox 3.0.6 for local PDF text extraction.

Copy `config/gemini.properties.example` to `config/gemini.properties`. Set `gemini.apiKey` locally, set `gemini.model` to `gemini-3.5-flash-lite` if available for your project, and set `gemini.timeoutSeconds` to a value such as `45`. The key stays in the ignored server config. Check the model's active requests-per-minute, tokens-per-minute, and requests-per-day limits in [Google AI Studio](https://aistudio.google.com/usage). Google's [pricing](https://ai.google.dev/gemini-api/docs/pricing) lists this model on the free tier, and [rate limits](https://ai.google.dev/gemini-api/docs/rate-limits) depend on the project and tier.

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
