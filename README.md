# Softnote

Softnote is an adaptive exam study planner. Its goal is to help students organize exam preparation, track their progress, and adjust what they study next based on that progress.

## Planned tech stack

- Backend: Java
- Frontend: HTML, CSS, and JavaScript
- Database: MySQL, accessed from Java through JDBC

## Local database setup

1. Create a MySQL database named `softnote_dev` and run `database/schema.sql` in it.
2. Copy `config/database.example.properties` to `config/database.properties`. Set the JDBC URL, database user, and password for your local database.
3. Include the MySQL Connector/J JAR on the Java runtime classpath.

## Authentication integration tests

Create a separate MySQL database named `softnote_test` and run `database/schema.sql` in it. Copy the example configuration to `config/database.test.properties` and point it at `softnote_test` with a user that can read, insert, and delete test rows. Run these PowerShell commands from the project root after setting `$connectorJar` to the path of your Connector/J JAR.

```powershell
New-Item -ItemType Directory -Path out -Force | Out-Null
$sources = Get-ChildItem -LiteralPath backend -Recurse -File -Filter '*.java'
javac -d out $sources.FullName
java -cp "out;$connectorJar" backend.test.AuthServiceIntegrationTest
```

## Local HTTP API

Compile the backend with the commands above, then run the scheduler test and start the server.

```powershell
java -cp out backend.test.DifficultyPlanStrategyTest
java -cp "out;$connectorJar" backend.api.ApiServer
```

The server listens on `127.0.0.1:8080`. Pass a port and an optional properties file path as command-line arguments to override the defaults.

Run the server from the project root and open http://127.0.0.1:8080/ to use the frontend.

| Endpoint | JSON request or query | Success |
| --- | --- | --- |
| `POST /register` | `name`, `email`, `password` | `201` with user ID |
| `POST /login` | `email`, `password` | `200` with user profile and token |
| `GET /exams` | Bearer token | `200` with the signed-in user's exams |
| `POST /exams` | `title`, ISO `examDate`, integer `dailyMinutes`, and a `chapters` array of objects with `name`, `difficulty`, and integer `estimatedMinutes` | `201` with exam ID |
| `GET /plan?examId=...` | Exam ID from the creation response | `200` with a JSON array of saved sessions. The first request generates the plan. |

Send JSON bodies with `Content-Type: application/json`. Send the login token in `Authorization: Bearer <token>` for exam creation and plan requests. Tokens are held in memory for up to eight hours and are cleared when the server stops.
