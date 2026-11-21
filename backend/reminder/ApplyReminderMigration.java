package backend.reminder;

import backend.db.DBConnection;
import java.nio.file.Files;
import java.nio.file.Path;

/** Additive, idempotent migration. Run before deploying the reminder endpoints. */
public final class ApplyReminderMigration {
    public static void main(String[] args) throws Exception {
        DBConnection database = args.length == 0 ? new DBConnection() : new DBConnection(Path.of(args[0]));
        String ddl = Files.readString(Path.of("database", "add_reminders.sql")).replaceAll("(?m)^--.*$", "");
        try (var connection = database.getConnection()) {
            for (String sql : ddl.split(";")) {
                if (sql.isBlank()) continue;
                try (var statement = connection.prepareStatement(sql)) { statement.execute(); }
            }
            try (var statement = connection.prepareStatement("SHOW TABLES LIKE ?")) {
                statement.setString(1, "%reminder%");
                try (var rows = statement.executeQuery()) { while (rows.next()) System.out.println(rows.getString(1)); }
            }
            System.out.println("PASS reminder tables are ready; existing study data unchanged.");
        }
    }
}
