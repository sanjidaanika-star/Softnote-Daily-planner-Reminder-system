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
